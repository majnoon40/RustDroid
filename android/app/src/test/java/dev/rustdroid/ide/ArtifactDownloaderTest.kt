package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArtifactDownloader
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Pure-logic tests of the resume machinery (decision table + sidecars),
 * plus BEHAVIORAL tests of the real download loop with a scripted HTTP
 * exchange (no sockets — the loop is parameterized over the exchange in
 * [ArtifactDownloader.attemptLoop]): restart/retry discipline, 416
 * recovery, corrupted prefixes, successful resume, and discard failures.
 */
class ArtifactDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloader = ArtifactDownloader(OkHttpClient())

    // ------------------------------------------------------------------
    // Decision table
    // ------------------------------------------------------------------

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

        assertTrue(downloader.discardPart(part))
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

    @Test
    fun `discardPart reports failure when the partial cannot be deleted`() {
        // an undeletable .part: a non-empty DIRECTORY at the same path
        // (File.delete() on a non-empty dir returns false)
        val part = File(tmp.root, "stuck.part")
        part.mkdirs()
        File(part, "child.bin").writeText("x")
        assertFalse(downloader.discardPart(part))
    }

    @Test
    fun `discardPartOrThrow turns an undeletable partial into an IOException`() {
        val part = File(tmp.root, "stuck2.part")
        part.mkdirs()
        File(part, "child.bin").writeText("x")
        try {
            downloader.discardPartOrThrow(part)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cannot discard"))
        }
    }

    // ------------------------------------------------------------------
    // Behavioral tests: the real loop, scripted exchanges
    // ------------------------------------------------------------------

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val baseRequest = Request.Builder().url("http://localhost/fixture").build()

    /** OkHttp [Response] built entirely in-memory. */
    private fun httpResponse(
        code: Int,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(baseRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fixture")
            .body(body.toResponseBody(null))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /** A body that DECLARES [declared] bytes but yields only [actual]. */
    private fun truncatedBody(declared: Long, actual: ByteArray): ResponseBody =
        object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = declared
            override fun source() = actual.toResponseBody(null).source()
        }

    /** One scripted exchange plus the Range/If-Range headers it saw. */
    private class Exchange(
        responses: List<(seenRange: String?, seenIfRange: String?) -> Response>,
    ) {
        val seen = mutableListOf<Pair<String?, String?>>()
        private val queue = ArrayDeque(responses)
        val count: Int get() = seen.size

        val next: (Request.Builder) -> Response = { builder ->
            val req = builder.build()
            seen += req.header("Range") to req.header("If-Range")
            if (queue.isEmpty()) throw IOException("script exhausted")
            queue.removeFirst()(req.header("Range"), req.header("If-Range"))
        }
    }

    private fun progress() = { _: Long, _: Long? -> }

    @Test
    fun `clean zero-byte download finishes in one request`() {
        val full = "hello world, this is the whole artifact!".toByteArray()
        val dest = File(tmp.root, "fresh.zip")
        val ex = Exchange(listOf { _, _ -> httpResponse(200, full, mapOf("ETag" to "\"v1\"")) })

        downloader.attemptLoop("http://localhost/f", File(tmp.root, "fresh.zip.part"), dest, sha256(full), progress(), ex.next)

        assertEquals(1, ex.count)
        assertEquals(full.toString(Charsets.ISO_8859_1), dest.readText())
        assertFalse(File(tmp.root, "fresh.zip.part").exists())
    }

    @Test
    fun `successful resume completes the file from a partial prefix`() {
        val full = "0123456789abcdef".toByteArray()
        val part = File(tmp.root, "resume.zip.part")
        part.writeBytes(full.copyOfRange(0, 8))
        downloader.writeMeta(part, "\"v1\"", 16L)
        val ex = Exchange(
            listOf(
                // first exchange: resume request answered with the tail
                { range, ifRange ->
                    assertEquals("bytes=8-", range)
                    assertEquals("\"v1\"", ifRange)
                    httpResponse(
                        206, full.copyOfRange(8, full.size),
                        mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 8-15/16"),
                    )
                },
            )
        )

        val dest = File(tmp.root, "resume.zip")
        downloader.attemptLoop("http://localhost/r", part, dest, sha256(full), progress(), ex.next)

        assertEquals(1, ex.count)
        assertEquals(full.toString(Charsets.ISO_8859_1), dest.readText())
        assertFalse(part.exists())
        assertFalse(downloader.metaFile(part).exists())
    }

    @Test
    fun `416 recovery restarts from zero without burning network retries`() {
        val full = "0123456789abcdef".toByteArray()
        val part = File(tmp.root, "out.zip.part")
        // pre-existing full-size partial + sidecar -> first request is a Range
        part.writeBytes(full)
        downloader.writeMeta(part, "\"v1\"", 16L)
        val ex = Exchange(
            listOf(
                { range, _ -> assertEquals("bytes=16-", range); httpResponse(416) },
                { range, _ -> assertNull(range); httpResponse(200, full, mapOf("ETag" to "\"v1\"")) },
            )
        )

        val dest = File(tmp.root, "out.zip")
        downloader.attemptLoop("http://localhost/f", part, dest, sha256(full), progress(), ex.next)

        // 416 + 200 = exactly 2 requests: NO retry-budget consumption
        assertEquals(2, ex.count)
        assertEquals(full.toString(Charsets.ISO_8859_1), dest.readText())
    }

    @Test
    fun `corrupted resume prefix restarts cleanly on If-Range mismatch`() {
        val full = "0123456789abcdef".toByteArray()
        val part = File(tmp.root, "corrupt.zip.part")
        // same length, different content = undetectable by size alone
        part.writeBytes("XXXXXXXX".toByteArray(Charsets.ISO_8859_1))
        downloader.writeMeta(part, "\"v1\"", 16L)
        val ex = Exchange(
            listOf(
                // server answers the Range request with a full-body 200
                { range, ifRange ->
                    assertEquals("bytes=8-", range)
                    assertEquals("\"v1\"", ifRange)
                    httpResponse(200, full, mapOf("ETag" to "\"v2\""))
                },
                // restart: clean full-body request completes the file
                { range, _ -> assertNull(range); httpResponse(200, full, mapOf("ETag" to "\"v2\"")) },
            )
        )

        val dest = File(tmp.root, "corrupt.zip")
        downloader.attemptLoop("http://localhost/c", part, dest, sha256(full), progress(), ex.next)

        // final content is the pristine full body, not prefix+append
        assertEquals(full.toString(Charsets.ISO_8859_1), dest.readText())
        assertEquals(2, ex.count)
    }

    @Test
    fun `asset change (206 with drifted total) restarts inside the attempt`() {
        val v1 = "0123456789abcdef".toByteArray() // 16 bytes
        val v2 = "0123456789abcdefghijk".toByteArray() // 21 bytes — different!
        val part = File(tmp.root, "drift.zip.part")
        part.writeBytes(v1.copyOfRange(0, 8))
        downloader.writeMeta(part, "\"v1\"", 16L)
        val ex = Exchange(
            listOf(
                // server now serves v2: the resume's Content-Range total (21)
                // no longer matches the sidecar total (16) -> clean restart
                { _, _ ->
                    httpResponse(
                        206, v2.copyOfRange(8, v2.size),
                        mapOf("ETag" to "\"v2\"", "Content-Range" to "bytes 8-20/21"),
                    )
                },
                { range, _ -> assertNull(range); httpResponse(200, v2, mapOf("ETag" to "\"v2\"")) },
            )
        )

        val dest = File(tmp.root, "drift.zip")
        downloader.attemptLoop("http://localhost/d", part, dest, sha256(v2), progress(), ex.next)

        assertEquals(v2.toString(Charsets.ISO_8859_1), dest.readText())
        // 206-reject + 200-full = 2 requests total: no retry budget burned
        assertEquals(2, ex.count)
    }

    @Test
    fun `repeated restarts are bounded - a rejecting server fails loud`() {
        // Pathological server: every Range request answers 416, every full
        // request answers with a 200 whose body can never match (content
        // half) — each write leaves a partial that forces another restart.
        val full = "0123456789abcdef".toByteArray()
        val part = File(tmp.root, "loop.zip.part")
        part.writeBytes(full) // full-size partial keeps forcing 416-restarts
        downloader.writeMeta(part, "\"flip\"", 16L)
        val ex = Exchange(
            List(20) { idx ->
                { range: String?, _: String? ->
                    if (range != null) httpResponse(416)
                    else httpResponse(200, full, mapOf("ETag" to "\"flip\""))
                }
            }
        )

        val dest = File(tmp.root, "loop.zip")
        try {
            // wrong checksum -> every completion fails; the loop must
            // terminate through the bounded restart budget, not hang
            downloader.attemptLoop("http://localhost/l", part, dest, sha256("mismatch".toByteArray()), progress(), ex.next)
            throw AssertionError("expected IOException from bounded restarts")
        } catch (e: IOException) {
            val msg = e.message ?: ""
            assertTrue(
                "unexpected failure: $msg",
                msg.contains("restarted from zero") || msg.contains("checksum mismatch"),
            )
        }
        // bounded: far fewer requests than the script offers
        assertTrue("expected bounded requests, got ${ex.count}", ex.count in 2..8)
    }

    @Test
    fun `failed discard surfaces as a local filesystem IOException`() {
        // undeletable .part (non-empty directory): any restart path that
        // must discard it fails LOUD instead of looping forever
        val part = File(tmp.root, "stuck.zip.part")
        part.mkdirs()
        File(part, "junk.bin").writeText("x")
        val ex = Exchange(
            listOf(
                // never reached: the undeletable partial is detected at
                // loop entry, before any request is issued
                { _, _ -> httpResponse(416) },
            )
        )
        val dest = File(tmp.root, "stuck.zip")
        try {
            downloader.attemptLoop("http://localhost/s", part, dest, null, progress(), ex.next)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue("unexpected: ${e.message}", e.message!!.contains("cannot discard"))
        }
        // the loop aborts BEFORE any request: local failure, not network
        assertEquals(0, ex.count)
    }

    @Test
    fun `truncated body keeps the partial for the next attempt`() {
        val full = "0123456789abcdef".toByteArray()
        val part = File(tmp.root, "trunc.zip.part")
        val ex = Exchange(
            listOf(
                // declares 16 bytes but delivers only half (connection cut)
                { _, _ ->
                    Response.Builder()
                        .request(baseRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(truncatedBody(16, full.copyOfRange(0, 8)))
                        .header("ETag", "\"v1\"")
                        .build()
                },
            )
        )
        val dest = File(tmp.root, "trunc.zip")
        try {
            downloader.attemptLoop("http://localhost/t", part, dest, sha256(full), progress(), ex.next)
            throw AssertionError("expected truncated-download IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("truncated download"))
        }
        // the partial survives for the next attempt's resume
        assertEquals(8L, part.length())
        assertEquals("\"v1\"", downloader.readMeta(part)!!.etag)
    }
}
