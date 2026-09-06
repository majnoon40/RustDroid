package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArtifactDownloader
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-logic tests of the resume machinery: the mayResume decision table
 * and the .part/.meta sidecar handling. No HTTP involved.
 */
class ArtifactDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloader = ArtifactDownloader(OkHttpClient())

    @Test
    fun `nothing to resume`() {
        assertFalse(downloader.mayResume(0L, 200, null, null))
        assertFalse(downloader.mayResume(0L, 206, null, 1000L))
    }

    @Test
    fun `206 with matching totals resumes`() {
        assertTrue(
            downloader.mayResume(100L, 206, ArtifactDownloader.PartMeta("\"v1\"", 1000L), 1000L)
        )
    }

    @Test
    fun `206 with drifted total restarts cleanly (asset changed size)`() {
        assertFalse(
            downloader.mayResume(100L, 206, ArtifactDownloader.PartMeta("\"v1\"", 1000L), 999L)
        )
    }

    @Test
    fun `200 answer to a Range request restarts (If-Range mismatch)`() {
        // the classic stale-prefix loop: old code appended the new full body
        // onto the old prefix and failed the checksum for 4 attempts
        assertFalse(downloader.mayResume(100L, 200, ArtifactDownloader.PartMeta("\"v1\"", 1000L), 1000L))
    }

    @Test
    fun `no sidecar meta keeps legacy resume behavior`() {
        // .part files from older app versions resume on 206, restart on 200
        assertTrue(downloader.mayResume(100L, 206, null, null))
        assertFalse(downloader.mayResume(100L, 200, null, null))
    }

    @Test
    fun `unknown content-range total cannot prove drift - resume`() {
        assertTrue(
            downloader.mayResume(100L, 206, ArtifactDownloader.PartMeta("\"v1\"", 1000L), null)
        )
    }

    @Test
    fun `meta sidecar roundtrips and discards with the part file`() {
        val part = File(tmp.root, "bundle.zip.part")
        part.writeText("partial")

        assertNull(downloader.readMeta(part))

        downloader.writeMeta(part, "\"etag-abc\"", 1000L)
        val meta = downloader.readMeta(part)
        assertEquals("\"etag-abc\"", meta!!.etag)
        assertEquals(1000L, meta.total)

        downloader.discardPart(part)
        assertFalse(part.exists())
        assertFalse(downloader.metaFile(part).exists())
        assertNull(downloader.readMeta(part))
    }

    @Test
    fun `meta without etag or total is not emitted`() {
        val part = File(tmp.root, "b.part")
        part.writeText("partial")
        downloader.writeMeta(part, null, null)
        assertNull(downloader.readMeta(part))
        assertFalse(downloader.metaFile(part).exists())
    }
}
