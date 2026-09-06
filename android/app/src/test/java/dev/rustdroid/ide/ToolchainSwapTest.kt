package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ToolchainSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(File(tmp.root, "usr.old").exists())
    }

    @Test
    fun `swap replaces a working old install and keeps it until success`() {
        val prefix = File(tmp.root, "usr").apply { mkdirs() }
        File(prefix, "bin/rustc").apply { parentFile.mkdirs(); writeText("old") }
        val staging = File(tmp.root, "usr.new").apply { mkdirs() }
        File(staging, "bin/rustc").apply { parentFile.mkdirs(); writeText("new") }
        val aside = File(tmp.root, "usr.old")

        ToolchainSwap.swap(prefix, staging, aside)

        assertEquals("new", File(prefix, "bin/rustc").readText())
        // the old install is GONE only after the new one is in place
        assertFalse(aside.exists())
        assertFalse(staging.exists())
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
}
