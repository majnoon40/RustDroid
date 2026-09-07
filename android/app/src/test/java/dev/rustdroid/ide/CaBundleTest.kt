package dev.rustdroid.ide

import dev.rustdroid.ide.runtime.CaBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `asset fallback builds a usable bundle when no system store is readable`() {
        val files = tmp.newFolder("files5")
        val prefix = tmp.newFolder("usr5")
        val missing = File(tmp.root, "does-not-exist-5")
        val asset =
            "-----BEGIN CERTIFICATE-----\nASSETCERT\n-----END CERTIFICATE-----\n"
                .toByteArray()

        val bundle = CaBundle.ensure(
            files, prefix, sources = listOf(missing), assetProvider = { asset },
        )

        assertNotNull(bundle)
        assertEquals(File(files, "home/.ssl/cacert.pem"), bundle)
        assertTrue(CaBundle.isUsable(bundle!!))
        assertTrue(bundle.readText().contains("ASSETCERT"))
        // probe mirror exists at prefix/etc/tls/cert.pem with same content
        assertEquals(bundle.readText(), CaBundle.probeFile(prefix).readText())
    }

    @Test
    fun `corrupt on-disk bundle self-heals from the asset`() {
        val files = tmp.newFolder("files6")
        val prefix = tmp.newFolder("usr6")
        val asset =
            "-----BEGIN CERTIFICATE-----\nHEALED\n-----END CERTIFICATE-----\n"
                .toByteArray()

        // simulate the error-77 state: a bundle file with no PEM content
        val target = CaBundle.bundleFile(files)
        target.parentFile?.mkdirs()
        target.writeText("truncated garbage without pem markers")

        val bundle = CaBundle.ensure(
            files, prefix,
            sources = listOf(File(tmp.root, "nope-6")),
            assetProvider = { asset },
        )

        assertNotNull(bundle)
        assertTrue(CaBundle.isUsable(bundle!!))
        assertTrue(bundle.readText().contains("HEALED"))
    }

    @Test
    fun `bundleStatus describes missing and usable bundles`() {
        val files = tmp.newFolder("files7")
        // missing
        assertTrue(CaBundle.bundleStatus(files).startsWith("MISSING:"))

        // usable: build from asset, then report OK with cert count
        val prefix = tmp.newFolder("usr7")
        val asset =
            "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----\n" +
            "-----BEGIN CERTIFICATE-----\nB\n-----END CERTIFICATE-----\n"
        CaBundle.ensure(
            files, prefix,
            sources = listOf(File(tmp.root, "nope-7")),
            assetProvider = { asset.toByteArray() },
        )
        val status = CaBundle.bundleStatus(files)
        assertTrue("got: $status", status.startsWith("OK: 2 cert(s)"))
        assertTrue("got: $status", status.contains(".ssl/cacert.pem"))
    }

    @Test
    fun `isUsable rejects missing empty and non-pem files`() {
        assertFalse(CaBundle.isUsable(File(tmp.root, "absent.pem")))
        val empty = tmp.newFile("empty.pem")
        assertFalse(CaBundle.isUsable(empty))
        val garbage = tmp.newFile("garbage.pem")
        garbage.writeText("random bytes, no markers")
        assertFalse(CaBundle.isUsable(garbage))
        val good = tmp.newFile("good.pem")
        good.writeText("-----BEGIN CERTIFICATE-----\nX\n-----END CERTIFICATE-----\n")
        assertTrue(CaBundle.isUsable(good))
    }

    // ------------------------------------------------------------------
    // Probe-mirror integrity (digest, not length)
    // ------------------------------------------------------------------

    private fun builtWithAsset(prefixName: String): Pair<File, File> {
        val files = tmp.newFolder("files-$prefixName")
        val prefix = tmp.newFolder("prefix-$prefixName")
        val asset =
            "-----BEGIN CERTIFICATE-----\nMIRRORTTESTCERT1\n-----END CERTIFICATE-----\n" +
                "-----BEGIN CERTIFICATE-----\nMIRRORTTESTCERT2\n-----END CERTIFICATE-----\n"
        val bundle = CaBundle.ensure(
            files, prefix,
            sources = listOf(File(tmp.root, "nope-$prefixName")),
            assetProvider = { asset.toByteArray() },
        )
        assertNotNull(bundle)
        return files to prefix
    }

    @Test
    fun `missing mirror is created and matches the canonical bundle`() {
        val (files, prefix) = builtWithAsset("create")
        val probe = CaBundle.probeFile(prefix)
        val canonical = CaBundle.bundleFile(files)
        assertTrue(probe.isFile)
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
        assertEquals(canonical.readText(), probe.readText())
    }

    @Test
    fun `truncated mirror is detected and repaired`() {
        val (files, prefix) = builtWithAsset("trunc")
        val probe = CaBundle.probeFile(prefix)
        val canonical = CaBundle.bundleFile(files)
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
        // truncate: same file, fewer bytes
        probe.writeText(canonical.readText().substring(0, 40))
        assertFalse(CaBundle.mirrorMatches(probe, canonical))
        // the next ensure() self-heals the mirror
        CaBundle.ensure(files, prefix, sources = emptyList())
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
    }

    @Test
    fun `same-size corrupted mirror is detected and repaired`() {
        val (files, prefix) = builtWithAsset("corrupt")
        val probe = CaBundle.probeFile(prefix)
        val canonical = CaBundle.bundleFile(files)
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
        // same length, different bytes: the OLD length check called this valid
        val text = canonical.readText()
        probe.writeText(text.replace("MIRRORTTESTCERT1", "XXXXXXXXXXXXXXX1"))
        assertEquals(canonical.length(), probe.length())
        assertFalse(CaBundle.mirrorMatches(probe, canonical))
        CaBundle.ensure(files, prefix, sources = emptyList())
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
    }

    @Test
    fun `interrupted copy leaves no partial mirror`() {
        val (files, prefix) = builtWithAsset("partial")
        val probe = CaBundle.probeFile(prefix)
        val canonical = CaBundle.bundleFile(files)
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
        // a torn write: the mirror is truncated AND a partial temp file
        // (from the interrupted attempt) sits next to the target
        probe.writeText(canonical.readText().substring(0, 30))
        val tmpFile = File(probe.parentFile, probe.name + ".rdtmp")
        tmpFile.writeText("-----BEGIN CERTIF")
        // the next ensure() rewrites atomically: the partial temp is
        // replaced and renamed into a complete, verifying mirror
        CaBundle.ensure(files, prefix, sources = emptyList())
        assertTrue(CaBundle.mirrorMatches(probe, canonical))
        assertTrue(CaBundle.isUsable(probe))
        val litter = probe.parentFile?.listFiles()
            ?.filter { it.name.endsWith(".rdtmp") } ?: emptyList()
        assertTrue(litter.isEmpty())
    }

    @Test
    fun `valid mirror is left untouched (no rewrite churn)`() {
        val (files, prefix) = builtWithAsset("stable")
        val probe = CaBundle.probeFile(prefix)
        val canonical = CaBundle.bundleFile(files)
        val before = probe.lastModified()
        // the canonical's mtime is older than the probe's; a re-ensure must
        // not rewrite a digest-matching mirror
        Thread.sleep(50)
        CaBundle.ensure(files, prefix, sources = emptyList())
        assertEquals(before, probe.lastModified())
    }
}
