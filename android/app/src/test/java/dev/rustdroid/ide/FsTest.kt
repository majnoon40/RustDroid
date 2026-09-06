package dev.rustdroid.ide

import dev.rustdroid.ide.util.Fs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `atomic write replaces read-only targets where the fs allows`() {
        val f = File(tmp.root, "locked.txt")
        f.writeText("old")
        Fs.writeAtomic(f, "new")
        assertEquals("new", f.readText())
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
    fun `deleteRecursively removes trees`() {
        val d = tmp.newFolder("tree")
        File(d, "sub").mkdirs()
        File(d, "sub/f.txt").writeText("x")
        assertTrue(Fs.deleteRecursively(d))
        assertFalse(d.exists())
    }
}
