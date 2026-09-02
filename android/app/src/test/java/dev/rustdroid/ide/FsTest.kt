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
