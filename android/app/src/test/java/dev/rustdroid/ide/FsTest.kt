package dev.rustdroid.ide

import dev.rustdroid.ide.util.Fs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `atomic write replaces content`() {
        val f = File(tmp.root, "a.txt")
        f.writeText("old")
        Fs.writeAtomic(f, "new content")
        assertEquals("new content", f.readText())
        assertFalse(File(f.parentFile, "a.txt.rdtmp").exists())
    }

    @Test
    fun `atomic write creates a missing file and needs no delete window`() {
        val f = File(tmp.root, "fresh.txt")
        Fs.writeAtomic(f, "first")
        assertEquals("first", f.readText())
        // double-write: the replace path (REPLACE_EXISTING) never leaves a
        // moment where the file is absent, and never leaves the tmp behind
        Fs.writeAtomic(f, "second")
        assertEquals("second", f.readText())
        assertFalse(File(tmp.root, "fresh.txt.rdtmp").exists())
    }

    @Test
    fun `atomic write replaces read-only targets because it renames rather than writes in place`() {
        // Review finding (full-repo audit): the previous version of this test
        // never actually made the target read-only, so it passed regardless of
        // whether read-only handling worked at all. This version really sets
        // the read-only bit and asserts writeAtomic still succeeds — which it
        // should, because ATOMIC_MOVE/REPLACE_EXISTING is a directory-entry
        // rename, not an in-place write, so the target file's own permission
        // bits never gate it (only the containing directory's write
        // permission matters, and that's untouched here).
        val f = File(tmp.root, "locked.txt")
        f.writeText("old")
        val honored = f.setReadOnly() && !f.canWrite()
        // Some CI runners execute as root or on filesystems that ignore
        // setReadOnly() for the owner; skip rather than false-fail there.
        assumeTrue("filesystem did not honor read-only for this run", honored)
        try {
            Fs.writeAtomic(f, "new")
            assertEquals("new", f.readText())
        } finally {
            f.setWritable(true)
        }
        assertFalse(File(tmp.root, "locked.txt.rdtmp").exists())
    }

    @Test
    fun `requireInside accepts real children and rejects escapes`() {
        val root = tmp.newFolder("root")
        Fs.requireInside(root, File(root, "bin/rustc")) // not yet existing: ok
        Fs.requireInside(root, File(File(root, "a"), "b"))
        try {
            Fs.requireInside(root, File(tmp.root, "elsewhere"))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("escapes"))
        }
    }

    @Test
    fun `requireInside catches writes routed through a symlink escape`() {
        val root = tmp.newFolder("symlink-root")
        val outside = tmp.newFolder("outside")
        val link = File(root, "jailbreak")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        // resolveChild passes (name is lexical), requireInside must not
        val dest = Fs.resolveChild(root, "jailbreak/payload.rs")
        try {
            Fs.requireInside(root, dest)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // the guard that stops symlink-based archive escapes
        }
    }

    @Test
    fun `resolveChild rejects traversal`() {
        val root = tmp.newFolder()
        try {
            Fs.resolveChild(root, "../escape")
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("traversal") || e.message!!.contains("illegal"))
        }
        try {
            Fs.resolveChild(root, "/abs")
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // ok
        }
    }

    @Test
    fun `resolveChild builds nested path`() {
        val root = tmp.newFolder()
        val child = Fs.resolveChild(root, "lib/rustdroid-link/bin/cc")
        assertEquals(
            File(File(File(File(root, "lib"), "rustdroid-link"), "bin"), "cc"),
            child,
        )
    }

    @Test
    fun `posix mode maps exec bit`() {
        val f = File(tmp.root, "exec.bin")
        f.writeBytes(byteArrayOf(1))
        // 0o755
        Fs.applyPosixMode(f, 0b111_101_101)
        assertTrue(f.canExecute())
        assertTrue(f.canRead())

        val g = File(tmp.root, "plain.bin")
        g.writeBytes(byteArrayOf(1))
        // 0o644
        Fs.applyPosixMode(g, 0b110_100_100)
        assertFalse(g.canExecute())
    }

    @Test
    fun `human bytes formats`() {
        assertEquals("512 B", Fs.humanBytes(512))
        assertEquals("1.0 KB", Fs.humanBytes(1024))
        assertEquals("1.5 MB", Fs.humanBytes(1024 * 1024 + 512 * 1024))
    }

    @Test
    fun `human count formats counts not bytes`() {
        // v0.2 regression: DepsScreen formatted crates.io download COUNTS
        // with humanBytes — "1.56 GB downloads" for rand whose count is
        // ~1.56 billion. Pin the count formatter (and that the billion-scale
        // case reads as B, never GB).
        assertEquals("42", Fs.humanCount(42))
        assertEquals("457K", Fs.humanCount(456_789))
        assertEquals("1K", Fs.humanCount(1_000))
        assertEquals("999K", Fs.humanCount(999_499))
        // rounding crosses the unit boundary: 999,999 reads as 1M, not 1000K
        assertEquals("1M", Fs.humanCount(999_999))
        assertEquals("13.4M", Fs.humanCount(13_400_000))
        assertEquals("1.6B", Fs.humanCount(1_560_000_000))
        assertEquals("23B", Fs.humanCount(23_000_000_000))
        // Review finding: the previous assertion here (comparing the last two
        // characters of "1.6B" against "GB") was true for any output this
        // formatter could structurally ever produce, since it never emits a
        // two-character suffix — decorative, not discriminating. This one
        // actually fails if a regression reintroduces byte-style "GB" output
        // at billion scale.
        assertFalse(Fs.humanCount(1_560_000_000).endsWith("GB"))
    }

    @Test
    fun `deleteRecursively removes trees`() {
        val d = tmp.newFolder("tree")
        File(d, "sub").mkdirs()
        File(d, "sub/f.txt").writeText("x")
        assertTrue(Fs.deleteRecursively(d))
        assertFalse(d.exists())
    }
}
