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
}
