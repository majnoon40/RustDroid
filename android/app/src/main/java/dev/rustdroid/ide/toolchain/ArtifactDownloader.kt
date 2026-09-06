package dev.rustdroid.ide.toolchain

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Streaming downloader for the toolchain bundle with SHA-256 verification.
 * Progress callback fires roughly every 256 KB. Pure JVM.
 *
 * Built for big-file-over-flaky-network:
 *  - the derived client has NO whole-call timeout: OkHttp's callTimeout
 *    budgets headers + the entire body, which killed the ~117 MB bundle
 *    on links slower than ~390 KB/s at exactly 5:00. Only per-read and
 *    connect gaps are bounded now.
 *  - retries (4 attempts, 1s/3s/7s backoff) and RESUMES via HTTP Range:
 *    the .part file survives network/truncation failures, the prefix
 *    already on disk is re-hashed (with progress reported so the pause is
 *    visible, not a hang), and the request continues from there (GitHub
 *    release assets are S3-backed and support ranges).
 *  - RESUME VALIDATION: the first response's ETag + total size are saved
 *    in a `<file>.part.meta` sidecar. Resumes send `If-Range: <etag>`:
 *    if the asset changed server-side, the answer is 200 (full body) or a
 *    206 whose Content-Range total no longer matches — both are detected
 *    and the partial is DISCARDED so the transfer restarts cleanly, in
 *    the same attempt, instead of appending new bytes onto a stale prefix
 *    and failing the checksum (the old failure loop).
 *  - a checksum mismatch deletes the .part file (and its sidecar) —
 *    corrupt data must never be resumed.
 */
class ArtifactDownloader(baseClient: OkHttpClient) {

    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS) // no whole-call budget
        .readTimeout(120, TimeUnit.SECONDS)    // per-read gap on slow links
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed class DownloadState {
        data class Progress(val bytes: Long, val total: Long?) : DownloadState()
        data class Done(val file: File, val sha256: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    /** ETag + total size of the transfer a .part file belongs to. */
    internal data class PartMeta(val etag: String?, val total: Long?)

    internal fun metaFile(part: File): File = File(part.parentFile, part.name + ".meta")

    internal fun readMeta(part: File): PartMeta? {
        val f = metaFile(part)
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        val etag = lines.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val total = lines.getOrNull(1)?.toLongOrNull()
        return if (etag == null && total == null) null else PartMeta(etag, total)
    }

    internal fun writeMeta(part: File, etag: String?, total: Long?) {
        val f = metaFile(part)
        if (etag == null && total == null) {
            f.delete()
            return
        }
        runCatching { f.writeText("${etag ?: ""}\n${total ?: ""}\n") }
    }

    internal fun discardPart(part: File) {
        part.delete()
        metaFile(part).delete()
    }

    /**
     * Decision for a response when a partial file exists — testable in
     * isolation from OkHttp. Returns true when the transfer may resume.
     */
    internal fun mayResume(
        haveBytes: Long,
        responseCode: Int,
        meta: PartMeta?,
        contentRangeTotal: Long?,
    ): Boolean {
        if (haveBytes <= 0) return false
        if (responseCode == 206) {
            // resumable — unless the object's size changed under us
            val savedTotal = meta?.total
            if (savedTotal != null && contentRangeTotal != null && savedTotal != contentRangeTotal) {
                return false
            }
            return true
        }
        // 200 with a Range request: If-Range mismatch (asset changed) or a
        // server that ignores ranges — either way, restart clean
        return false
    }

    fun downloadBlocking(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")

        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                attemptOnce(url, tmp, dest, expectedSha256, onProgress)
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt - 1])
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        throw IOException(
            "download failed after $MAX_ATTEMPTS attempts: ${lastError?.message ?: "unknown"}"
        )
    }

    private fun attemptOnce(
        url: String,
        tmp: File,
        dest: File,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        // A clean restart loops here at most once (partial discarded ->
        // next iteration has haveBytes == 0 and requests the full body).
        while (true) {
            val resumeFrom = if (tmp.isFile) tmp.length() else 0L
            if (resumeFrom == 0L) discardPart(tmp)
            val meta = readMeta(tmp)

            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "RustDroid/0.1 (Android IDE)")
            if (resumeFrom > 0) {
                builder.header("Range", "bytes=$resumeFrom-")
                // If-Range: a 200 answer means the asset changed — restart
                meta?.etag?.takeIf { it.isNotEmpty() }?.let { builder.header("If-Range", it) }
            }

            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw IOException("network error: ${e.message}")
            }
            val outcome = response.use { resp ->
                handleResponse(resp, tmp, dest, expectedSha256, onProgress, resumeFrom, meta)
            }
            if (outcome == Outcome.RESTART) continue
            return
        }
    }

    private enum class Outcome { DONE, RESTART }

    private fun handleResponse(
        resp: okhttp3.Response,
        tmp: File,
        dest: File,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit,
        resumeFrom: Long,
        meta: PartMeta?,
    ): Outcome {
        when {
            // Range not satisfiable — the .part file is already at full
            // size from a run whose rename never happened; the retry loop
            // starts the next attempt clean.
            resp.code == 416 && resumeFrom > 0 -> {
                discardPart(tmp)
                throw IOException("server rejected resume (HTTP 416) — restarting from zero")
            }
            !resp.isSuccessful -> throw IOException("HTTP ${resp.code} fetching bundle")
        }

        val body = resp.body ?: throw IOException("empty response body")
        val contentRangeTotal: Long? = resp.header("Content-Range")
            ?.substringAfterLast('/')
            ?.toLongOrNull()?.takeIf { it > 0 }

        if (!mayResume(resumeFrom, resp.code, meta, contentRangeTotal)) {
            if (resumeFrom > 0) {
                // asset changed server-side (or no range support): discard
                // the stale prefix and take the full body — same attempt,
                // no retry budget burned
                discardPart(tmp)
                return Outcome.RESTART
            }
            // fresh start: record ETag + total for future resumes
            val total0 = body.contentLength().takeIf { it > 0 }
            writeMeta(tmp, resp.header("ETag"), total0 ?: contentRangeTotal)
        }
        val resuming = resumeFrom > 0 && resp.code == 206
        val total: Long? = if (resuming) contentRangeTotal
        else body.contentLength().takeIf { it > 0 } ?: contentRangeTotal

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        if (resuming) {
            // re-hash the already-downloaded prefix, then append to it.
            // Progress keeps flowing during the re-hash so the
            // multi-second pause is visibly work, not a hang.
            var rehashReport = 0L
            tmp.inputStream().use { ins ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(b)
                    if (n < 0) break
                    digest.update(b, 0, n)
                    written += n
                    if (written - rehashReport >= 256 * 1024) {
                        rehashReport = written
                        onProgress(written, total)
                    }
                }
            }
            if (written != resumeFrom) {
                discardPart(tmp)
                throw IOException("resume prefix unreadable — restarting from zero")
            }
        }

        var lastReport = written
        FileOutputStream(tmp, resuming).use { out ->
            val input = body.byteStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                written += n
                if (written - lastReport >= 256 * 1024) {
                    lastReport = written
                    onProgress(written, total)
                }
            }
            out.flush()
        }
        if (total != null && written != total) {
            // keep .part + sidecar — the next attempt resumes here
            throw IOException("truncated download: $written of $total bytes")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && expectedSha256.length == 64 && sha != expectedSha256) {
            discardPart(tmp) // corrupt data must never be resumed
            throw IOException(
                "checksum mismatch: got $sha, expected $expectedSha256 — " +
                    "the download was corrupted or the release changed"
            )
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            discardPart(tmp)
            throw IOException("cannot finalize download at ${dest.path}")
        }
        metaFile(tmp).delete()
        onProgress(written, total)
        return Outcome.DONE
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        val BACKOFF_MS = longArrayOf(1_000L, 3_000L, 7_000L)
    }
}
