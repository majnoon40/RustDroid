package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.CaBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeCert(name: String, serial: String): File =
        tmp.newFile(name).apply {
            writeText(
                "-----BEGIN CERTIFICATE-----\n$serial\n-----END CERTIFICATE-----\n"
            )
        }

    @Test
    fun `bundle is built from all readable sources`() {
        val store1 = tmp.newFolder("store1")
        val store2 = tmp.newFolder("store2")
        fakeCert("${store1.name}/a.pem", "AAA")
        fakeCert("${store2.name}/b.pem", "BBB")
        // noise that must be skipped: not a PEM
        tmp.newFile("${store1.name}/readme.txt").writeText("hello")

        val files = tmp.newFolder("files")
        val prefix = tmp.newFolder("usr")

        val bundle = CaBundle.ensure(files, prefix, sources = listOf(store1, store2))

        assertNotNull(bundle)
        assertEquals(File(files, "home/.ssl/cacert.pem"), bundle)
        val text = bundle!!.readText()
        assertEquals(2, Regex("-----BEGIN CERTIFICATE-----").findAll(text).count())
        assertTrue(text.contains("AAA"))
        assertTrue(text.contains("BBB"))
        // probe mirror exists at prefix/etc/tls/cert.pem with same content
        val probe = CaBundle.probeFile(prefix)
        assertTrue(probe.isFile)
        assertEquals(text, probe.readText())
    }

    @Test
    fun `missing newline between certs is repaired`() {
        val store = tmp.newFolder("store")
        tmp.newFile("${store.name}/x.pem").writeText(
            "-----BEGIN CERTIFICATE-----\nX1\n-----END CERTIFICATE-----"
        )
        tmp.newFile("${store.name}/y.pem").writeText(
            "-----BEGIN CERTIFICATE-----\nY1\n-----END CERTIFICATE-----\n"
        )
        val files = tmp.newFolder("files2")
        val prefix = tmp.newFolder("usr2")
        val bundle = CaBundle.ensure(files, prefix, sources = listOf(store))
        assertTrue(
            bundle!!.readText().contains("-----END CERTIFICATE-----\n-----BEGIN CERTIFICATE-----\n")
        )
    }

    @Test
    fun `ensure is idempotent and returns the same file`() {
        val store = tmp.newFolder("store3")
        fakeCert("${store.name}/c.pem", "CCC")
        val files = tmp.newFolder("files3")
        val prefix = tmp.newFolder("usr3")
        val first = CaBundle.ensure(files, prefix, sources = listOf(store))
        val second = CaBundle.ensure(files, prefix, sources = listOf(store))
        assertEquals(first, second)
        // and does not duplicate the cert on the second pass
        assertEquals(1, Regex("CCC").findAll(second!!.readText()).count())
    }

    @Test
    fun `no readable sources yields null`() {
        val files = tmp.newFolder("files4")
        val prefix = tmp.newFolder("usr4")
        val missing = File(tmp.root, "does-not-exist")
        assertNull(CaBundle.ensure(files, prefix, sources = listOf(missing)))
        assertNull(CaBundle.bundleFile(files).takeIf { it.exists() })
    }
}
