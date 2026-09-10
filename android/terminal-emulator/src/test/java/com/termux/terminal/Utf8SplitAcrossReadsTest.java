package com.termux.terminal;

import junit.framework.AssertionFailedError;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8 split-across-reads regression test (RustDroid addition, plan
 * §6.3 / review Part 6).
 *
 * TerminalEmulator.processByte is a stateful incremental UTF-8 decoder
 * (mUtf8ToFollow / mUtf8InputBuffer persist across append() calls) — the
 * upstream design satisfies the split requirement, but the
 * split-boundary behavior was unpinned by an explicit test. This pins
 * it: every multi-byte sequence fed through append() at EVERY possible
 * split offset (across two and three calls) must render identically to
 * the unsplit feed, AND the decoder must return to its resting state
 * after each complete reassembly (mUtf8ToFollow == 0 and the pending
 * byte buffer empty — asserted so a decoder regression is
 * distinguishable from a grid-rendering change; the fields are private,
 * so this reads them via reflection).
 *
 * The same input fed with a corrupted continuation byte must yield
 * EXACTLY one replacement character (U+FFFD).
 */
public class Utf8SplitAcrossReadsTest extends TerminalTestCase {

    private static final int WIDTH = 40;

    private static final String TEXT =
            "a\u00e9b\u20acc\ud83d\ude00d"; // 2-, 3- and 4-byte sequences, ASCII interleaved

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private int decoderField(String name) throws Exception {
        Field f = TerminalEmulator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getByte(mTerminal);
    }

    /** Decoder resting state: nothing pending (mUtf8ToFollow == 0, buffer fill == 0). */
    private void assertDecoderResting(String context) throws Exception {
        assertEquals(context + ": mUtf8ToFollow", 0, decoderField("mUtf8ToFollow"));
        assertEquals(context + ": mUtf8Index (pending buffer fill)", 0, decoderField("mUtf8Index"));
    }

    private void assertRenderedRow(String expected, String context) {
        // assertLineIs compares the full row; a fresh row is space-padded
        // to the terminal width. A replacement character anywhere in the
        // text region (or beyond it) breaks this comparison.
        String full = expected + " ".repeat(WIDTH - expected.length());
        try {
            assertLineIs(0, full);
        } catch (AssertionFailedError e) {
            throw new AssertionFailedError(context + ": " + e.getMessage());
        }
    }

    /** Feeds [bytes] through append() in chunks delimited by [cuts] (ascending offsets). */
    private void feedSplit(byte[] bytes, int... cuts) {
        int from = 0;
        for (int cut : cuts) {
            byte[] chunk = new byte[cut - from];
            System.arraycopy(bytes, from, chunk, 0, cut - from);
            mTerminal.append(chunk, chunk.length);
            from = cut;
        }
        byte[] rest = new byte[bytes.length - from];
        System.arraycopy(bytes, from, rest, 0, rest.length);
        mTerminal.append(rest, rest.length);
    }

    // ------------------------------------------------------------------
    // Every split offset, two calls
    // ------------------------------------------------------------------

    public void testEverySplitOffsetRendersIdentically() throws Exception {
        byte[] all = utf8(TEXT);
        for (int cut = 1; cut < all.length; cut++) {
            withTerminalSized(WIDTH, 2);
            feedSplit(all, cut);
            assertRenderedRow(TEXT, "cut at byte " + cut);
            assertDecoderResting("after reassembly, cut at byte " + cut);
        }
    }

    // ------------------------------------------------------------------
    // Three calls: cut inside EVERY multi-byte sequence at each internal
    // boundary AND at pairs of boundaries (first-byte/continuation and
    // continuation/continuation splits in the same feed)
    // ------------------------------------------------------------------

    public void testThreeWaySplitsInsideMultiByteSequences() throws Exception {
        byte[] all = utf8(TEXT);
        // locate the multi-byte sequences in the byte stream
        int i = 0;
        while (i < all.length) {
            int len = sequenceLength(all[i]);
            if (len > 1) {
                for (int first = 1; first < len; first++) {
                    for (int second = first + 1; second < len; second++) {
                        withTerminalSized(WIDTH, 2);
                        feedSplit(all, i + first, i + second);
                        assertRenderedRow(TEXT, "seq at " + i + " len " + len
                                + " cuts " + (i + first) + "," + (i + second));
                        assertDecoderResting("after 3-way reassembly, seq at " + i);
                    }
                }
            }
            i += Math.max(len, 1);
        }
    }

    // ------------------------------------------------------------------
    // Corrupted continuation byte -> exactly one replacement char
    // ------------------------------------------------------------------

    public void testCorruptedContinuationYieldsExactlyOneReplacementChar() throws Exception {
        // "\u00e9" = C3 A9; corrupt the continuation byte (A9 -> 41 'A').
        withTerminalSized(WIDTH, 2);
        byte[] corrupted = {(byte) 0xC3, 'A', 'x'};
        mTerminal.append(corrupted, corrupted.length);
        // The decoder emits one replacement for the broken sequence and
        // reprocesses the stray byte as input: U+FFFD 'A' 'x'.
        assertRenderedRow("\ufffdAx", "corrupted continuation");
        assertDecoderResting("after corrupted sequence");
    }

    public void testCorruptedSplitSequenceYieldsExactlyOneReplacementChar() throws Exception {
        // Same corruption, but the broken sequence arrives SPLIT across
        // two append() calls — the split must not multiply the
        // replacement character.
        withTerminalSized(WIDTH, 2);
        byte[] first = {(byte) 0xC3};
        byte[] second = {'A', 'y'};
        mTerminal.append(first, first.length);
        mTerminal.append(second, second.length);
        assertRenderedRow("\ufffdAy", "corrupted split continuation");
        assertDecoderResting("after corrupted split sequence");
    }

    public void testTruncatedSequenceAtEndOfReadDoesNotRender() throws Exception {
        // A 3-byte sequence with only 2 bytes delivered: nothing renders
        // yet, decoder holds state — then the last byte completes it.
        byte[] euro = utf8("\u20ac"); // E2 82 AC
        withTerminalSized(WIDTH, 2);
        mTerminal.append(new byte[]{euro[0], euro[1]}, 2);
        assertDecoderRestingIsFalse("pending 3-byte sequence");
        mTerminal.append(new byte[]{euro[2]}, 1);
        assertRenderedRow("\u20ac", "completed after split");
        assertDecoderResting("after completion");
    }

    private void assertDecoderRestingIsFalse(String context) throws Exception {
        assertTrue(context + ": decoder should hold pending state",
                decoderField("mUtf8ToFollow") != 0 || decoderField("mUtf8Index") != 0);
    }

    /** Byte-length of the UTF-8 sequence starting with [b] (1 for ASCII/invalid). */
    private static int sequenceLength(byte b) {
        if ((b & 0b10000000) == 0) return 1;
        if ((b & 0b11100000) == 0b11000000) return 2;
        if ((b & 0b11110000) == 0b11100000) return 3;
        if ((b & 0b11111000) == 0b11110000) return 4;
        return 1;
    }
}
