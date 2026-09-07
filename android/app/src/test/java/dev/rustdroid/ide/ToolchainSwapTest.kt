package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ToolchainSwap
import dev.rustdroid.ide.toolchain.ToolchainTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ToolchainSwapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `swap installs staging when no old prefix exists`() {
        val prefix = File(tmp.root, "usr")
        val staging = File(tmp.root, "usr.new").apply { mkdirs() }
        File(staging, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }

        ToolchainSwap.swap(prefix, staging, File(tmp.root, "usr.old"))

        assertTrue(File(prefix, "bin/rustc").isFile)
        assertFalse(staging.exists())
        // no previous install: nothing to retain in aside
        assertFalse(File(tmp.root, "usr.old").exists())
    }

    @Test
    fun `swap replaces a working old install and RETAINS it until commit`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("old") }
        val staging = File(tmp.root, "usr.new").apply { mkdirs() }
        File(staging, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }
        val aside = File(tmp.root, "usr.old")

        ToolchainSwap.swap(prefix, staging, aside)

        assertEquals("new", File(prefix, "bin/rustc").readText())
        // THE transactional property: the old install is still on disk for
        // rollback — it only disappears at commit (after verification)
        assertEquals("old", File(aside, "bin/rustc").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun `commit discards the retained old install`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }
        val aside = File(tmp.root, "usr.old").apply { mkdirs() }
        File(aside, "bin/rustc").apply { parentFile.mkdirs(); writeText("old") }

        assertTrue(ToolchainSwap.commit(aside))

        assertEquals("new", File(prefix, "bin/rustc").readText())
        assertFalse(aside.exists())
    }

    @Test
    fun `commit is a no-op without an aside`() {
        assertTrue(ToolchainSwap.commit(File(tmp.root, "absent")))
    }

    @Test
    fun `rollback restores the previous known-good install over a bad new one`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("broken-new") }
        val aside = File(tmp.root, "usr.old").apply { mkdirs() }
        File(aside, "bin/rustc").apply { parentFile.mkdirs(); writeText("good-old") }

        ToolchainSwap.rollback(prefix, aside)

        assertEquals("good-old", File(prefix, "bin/rustc").readText())
        assertFalse(aside.exists())
    }

    @Test
    fun `rollback is a no-op when nothing was retained`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }
        ToolchainSwap.rollback(prefix, File(tmp.root, "absent"))
        assertEquals("new", File(prefix, "bin/rustc").readText())
    }

    @Test
    fun `swap refuses missing staging without touching the old install`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("old") }

        try {
            ToolchainSwap.swap(prefix, File(tmp.root, "usr.new"), File(tmp.root, "usr.old"))
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        // the working install survived untouched
        assertEquals("old", File(prefix, "bin/rustc").readText())
    }

    @Test
    fun `stale aside directory from a previous failed swap is cleaned`() {
        val prefix = File(tmp.root, "usr")
        val aside = File(tmp.root, "usr.old").apply { mkdirs() }
        File(aside, "junk.bin").writeText("stale")
        val staging = File(tmp.root, "usr.new").apply { mkdirs() }
        File(staging, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }

        ToolchainSwap.swap(prefix, staging, aside)

        assertTrue(File(prefix, "bin/rustc").isFile)
        assertFalse(File(aside, "junk.bin").exists())
    }

    @Test
    fun `an unclearable aside slot is a hard swap failure`() {
        // the aside path occupied by a file the delete cannot clear
        // (read-only parent directory blocks unlink)
        val parent = tmp.newFolder("blocked")
        val prefix = File(parent, "usr").apply { mkdirs() }
        val staging = File(parent, "usr.new").apply { mkdirs() }
        val aside = File(parent, "usr.old").apply { mkdirs(); File(this, "x").writeText("y") }
        parent.setWritable(false)
        try {
            try {
                ToolchainSwap.swap(prefix, staging, aside)
                throw AssertionError("expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("cannot clear stale"))
            }
        } finally {
            parent.setWritable(true)
        }
    }

    @Test
    fun `rollback failure when the bad prefix cannot be removed`() {
        val parent = tmp.newFolder("blocked2")
        val prefix = File(parent, "usr").apply { mkdirs(); File(this, "bad").writeText("x") }
        val aside = File(parent, "usr.old").apply { mkdirs(); File(this, "good").writeText("y") }
        parent.setWritable(false)
        try {
            try {
                ToolchainSwap.rollback(prefix, aside)
                throw AssertionError("expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("cannot remove the unverified install"))
            }
        } finally {
            parent.setWritable(true)
        }
    }

    // ------------------------------------------------------------------
    // Transaction marker
    // ------------------------------------------------------------------

    @Test
    fun `pending marker roundtrips and clears`() {
        val marker = ToolchainTransaction.markerFile(tmp.root)
        assertNull(ToolchainTransaction.read(marker))

        ToolchainTransaction.begin(marker, "toolchain-1.85.0-aarch64")
        var pending = ToolchainTransaction.read(marker)
        assertNotNull(pending)
        assertEquals("toolchain-1.85.0-aarch64", pending!!.dist)
        assertEquals("pending", pending.stage)
        assertTrue(pending.startedMs > 0)

        ToolchainTransaction.advance(marker, "verify")
        pending = ToolchainTransaction.read(marker)
        assertEquals("verify", pending!!.stage)

        assertTrue(ToolchainTransaction.clear(marker))
        assertNull(ToolchainTransaction.read(marker))
    }

    @Test
    fun `garbage marker content reads as no pending install`() {
        val marker = ToolchainTransaction.markerFile(tmp.root)
        marker.writeText("total garbage\nno keys here\n")
        assertNull(ToolchainTransaction.read(marker))
    }

    // ------------------------------------------------------------------
    // Recovery decision table
    // ------------------------------------------------------------------

    private val pending = ToolchainTransaction.PendingInstall("dist", 1L, "verify")

    @Test
    fun `no marker means nothing to recover`() {
        assertEquals(
            ToolchainTransaction.Action.NONE,
            ToolchainTransaction.recoveryAction(null, false, false, false),
        )
    }

    @Test
    fun `crash after verification keeps the verified install`() {
        // ready marker written, then crash before commit/clear
        assertEquals(
            ToolchainTransaction.Action.KEEP_VERIFIED,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = true, readyMarkerPresent = true, asideExists = true),
        )
        assertEquals(
            ToolchainTransaction.Action.KEEP_VERIFIED,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = true, readyMarkerPresent = true, asideExists = false),
        )
    }

    @Test
    fun `crash after swap but before verification restores the old install`() {
        // prefix = new-unverified (no ready marker), aside = known-good
        assertEquals(
            ToolchainTransaction.Action.RESTORE_ASIDE,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = true, readyMarkerPresent = false, asideExists = true),
        )
    }

    @Test
    fun `crash mid-swap (prefix missing, aside moved) restores the old install`() {
        assertEquals(
            ToolchainTransaction.Action.RESTORE_ASIDE,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = false, readyMarkerPresent = false, asideExists = true),
        )
    }

    @Test
    fun `crash before the swap keeps the still-valid old install`() {
        // the old prefix was never touched: still installed AND ready
        assertEquals(
            ToolchainTransaction.Action.KEEP_VERIFIED,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = true, readyMarkerPresent = true, asideExists = false),
        )
    }

    @Test
    fun `nothing usable is discarded`() {
        assertEquals(
            ToolchainTransaction.Action.DISCARD,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = false, readyMarkerPresent = false, asideExists = false),
        )
        // a present-but-unverified prefix with no aside has nothing to
        // restore either
        assertEquals(
            ToolchainTransaction.Action.DISCARD,
            ToolchainTransaction.recoveryAction(pending, prefixInstalled = true, readyMarkerPresent = false, asideExists = false),
        )
    }

    // ------------------------------------------------------------------
    // Crash-safe pending-marker invariant: install-pending.txt may only
    // be deleted after the transaction reached a known safe final state.
    // These tests assert the MARKER's existence, not just thrown
    // exceptions.
    // ------------------------------------------------------------------

    /** A fake prefix layout good enough for paths.isInstalled()-style checks. */
    private fun fakeInstall(root: File, rustc: String): File {
        val prefix = File(root, "usr").apply { mkdirs() }
        File(prefix, "bin").mkdirs()
        File(prefix, "bin/rustc").writeText(rustc)
        File(prefix, "bin/cargo").writeText(rustc)
        File(prefix, "lib").mkdirs()
        File(prefix, "lib/libc++_shared.so").writeText(rustc)
        File(prefix, "lib/rustdroid-link").mkdirs()
        return prefix
    }

    private fun isInstalledLike(prefix: File): Boolean =
        File(prefix, "bin/rustc").isFile && File(prefix, "bin/cargo").isFile &&
            File(prefix, "lib/rustdroid-link").isDirectory &&
            File(prefix, "lib/libc++_shared.so").isFile

    @Test
    fun `install rollback succeeds - pending marker is removed`() {
        val prefix = fakeInstall(tmp.root, "broken-new")
        val aside = File(tmp.root, "usr.old").apply { mkdirs() }
        File(aside, "bin").mkdirs()
        File(aside, "bin/rustc").writeText("good-old")
        val marker = ToolchainTransaction.markerFile(tmp.root)
        ToolchainTransaction.begin(marker, "dist")

        val failure = ToolchainTransaction.rollbackFailedInstall(marker, prefix, aside)

        assertNull("rollback must succeed", failure)
        // THE invariant: the marker is gone because the restore COMPLETED
        assertFalse("marker must be removed after a successful rollback", marker.exists())
        // and the known-good install is actually back in place
        assertEquals("good-old", File(prefix, "bin/rustc").readText())
        assertFalse(aside.exists())
    }

    @Test
    fun `install rollback fails - pending marker REMAINS for startup recovery`() {
        // the unverified prefix cannot be removed (read-only parent blocks
        // the delete) -> ToolchainSwap.rollback throws -> the marker must
        // survive so the next startup can retry the restore
        val parent = tmp.newFolder("rollback-kept")
        val prefix = fakeInstall(parent, "broken-new")
        val aside = File(parent, "usr.old").apply { mkdirs() }
        File(aside, "bin").mkdirs()
        File(aside, "bin/rustc").writeText("good-old")
        val marker = ToolchainTransaction.markerFile(parent)
        ToolchainTransaction.begin(marker, "dist")
        ToolchainTransaction.advance(marker, "verify")
        parent.setWritable(false)
        try {
            val failure = ToolchainTransaction.rollbackFailedInstall(marker, prefix, aside)
            // the rollback failure is RETURNED so the caller can attach it
            // to the original install/verification exception (both stay
            // visible); it must NOT have been swallowed
            assertNotNull("rollback must fail", failure)
            assertTrue(
                "unexpected failure: ${failure!!.message}",
                failure.message!!.contains("cannot remove the unverified install"),
            )
        } finally {
            parent.setWritable(true)
        }
        // THE invariant: an incomplete rollback NEVER clears the marker
        assertTrue(
            "install-pending.txt must remain when the rollback failed",
            marker.exists(),
        )
        // and the pending record is still complete enough to act on
        val still = ToolchainTransaction.read(marker)
        assertNotNull(still)
        assertEquals("verify", still!!.stage)
        // the known-good install is still retained for the retry
        assertTrue(File(aside, "bin/rustc").isFile)
    }

    @Test
    fun `startup recovery fails - pending marker REMAINS`() {
        // crash state: prefix = new-unverified, aside = old known-good ->
        // RESTORE_ASIDE; the restore itself fails (blocked prefix delete)
        val parent = tmp.newFolder("recovery-fail")
        val prefix = fakeInstall(parent, "broken-new")
        val staging = File(parent, "usr.new")
        val aside = File(parent, "usr.old").apply { mkdirs() }
        File(aside, "bin").mkdirs()
        File(aside, "bin/rustc").writeText("good-old")
        val marker = ToolchainTransaction.markerFile(parent)
        ToolchainTransaction.begin(marker, "dist")
        parent.setWritable(false)
        try {
            val safe = ToolchainTransaction.runRecovery(
                marker, prefix, staging, aside,
                readyMarker = File(prefix, ".rustdroid-verified"),
                isInstalled = { isInstalledLike(prefix) },
            )
            assertFalse("recovery must report incomplete", safe)
        } finally {
            parent.setWritable(true)
        }
        // THE invariant: recovery did NOT reach a safe final state, so
        // the marker survives — never silently promote an unresolved
        // install to a clean state
        assertTrue(
            "install-pending.txt must remain when recovery failed",
            marker.exists(),
        )
        assertTrue(File(aside, "bin/rustc").isFile) // retry material intact
    }

    @Test
    fun `a later startup retries recovery because the marker still exists`() {
        // first startup: recovery fails (transient filesystem problem)
        // second startup: the retained marker drives the retry, which
        // now succeeds and clears the marker
        val parent = tmp.newFolder("recovery-retry")
        val prefix = fakeInstall(parent, "broken-new")
        val staging = File(parent, "usr.new")
        val aside = File(parent, "usr.old").apply { mkdirs() }
        File(aside, "bin").mkdirs()
        File(aside, "bin/rustc").writeText("good-old")
        val marker = ToolchainTransaction.markerFile(parent)
        ToolchainTransaction.begin(marker, "dist")

        // ---- startup 1: the restore fails ----
        parent.setWritable(false)
        try {
            assertFalse(
                ToolchainTransaction.runRecovery(
                    marker, prefix, staging, aside,
                    readyMarker = File(prefix, ".rustdroid-verified"),
                    isInstalled = { isInstalledLike(prefix) },
                )
            )
        } finally {
            parent.setWritable(true)
        }
        assertTrue(marker.exists()) // the retry record survived

        // ---- startup 2: the marker still exists, so recovery runs again ----
        // (runRecovery only does anything BECAUSE the marker is present —
        // a cleared marker would have meant "nothing owed")
        assertNotNull(ToolchainTransaction.read(marker))
        val safe = ToolchainTransaction.runRecovery(
            marker, prefix, staging, aside,
            readyMarker = File(prefix, ".rustdroid-verified"),
            isInstalled = { isInstalledLike(prefix) },
        )

        assertTrue("second recovery must complete", safe)
        // restored, and the marker cleared exactly because it completed
        assertEquals("good-old", File(prefix, "bin/rustc").readText())
        assertFalse(aside.exists())
        assertFalse("marker must be removed once recovery completes", marker.exists())
    }

    @Test
    fun `fresh install with nothing to restore still clears the marker`() {
        // verification failed on a FIRST install (no previous toolchain,
        // no aside): a failed-but-terminal state — the marker is cleared
        // because there is nothing recovery could retry
        val prefix = fakeInstall(tmp.root, "broken-new")
        val marker = ToolchainTransaction.markerFile(tmp.root)
        ToolchainTransaction.begin(marker, "dist")

        val failure = ToolchainTransaction.rollbackFailedInstall(
            marker, prefix, File(tmp.root, "usr.old"),
        )

        assertNull(failure)
        assertFalse(marker.exists())
        // the unverified prefix itself is left for the next install to
        // replace (and initialState() reports NotInstalled: no ready
        // marker inside it)
        assertFalse(File(prefix, ".rustdroid-verified").exists())
    }

    @Test
    fun `startup recovery of a verified install finishes the transaction`() {
        // crash after VERIFY passed (ready marker written) but before
        // READY: recovery keeps the verified prefix, drops the aside and
        // clears the marker
        val prefix = fakeInstall(tmp.root, "verified-new")
        File(prefix, ".rustdroid-verified").writeText("verified=1\n")
        val aside = File(tmp.root, "usr.old").apply { mkdirs() }
        File(aside, "bin").mkdirs()
        File(aside, "bin/rustc").writeText("old")
        val marker = ToolchainTransaction.markerFile(tmp.root)
        ToolchainTransaction.begin(marker, "dist")

        val safe = ToolchainTransaction.runRecovery(
            marker, prefix, File(tmp.root, "usr.new"), aside,
            readyMarker = File(prefix, ".rustdroid-verified"),
            isInstalled = { isInstalledLike(prefix) },
        )

        assertTrue(safe)
        assertEquals("verified-new", File(prefix, "bin/rustc").readText())
        assertFalse(aside.exists())
        assertFalse(marker.exists())
    }
}
