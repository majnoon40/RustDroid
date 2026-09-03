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
 *    already on disk is re-hashed, and the request continues from there
 *    (GitHub release assets are S3-backed and support ranges).
 *  - a checksum mismatch deletes the .part file — corrupt data must
 *    never be resumed.
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
        val resumeFrom = if (tmp.isFile) tmp.length() else 0L

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "RustDroid/0.1 (Android IDE)")
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw IOException("network error: ${e.message}")
        }
        response.use { resp ->
            when {
                // Range not satisfiable — the .part file is already at full
                // size from a run whose rename never happened; start clean.
                resp.code == 416 && resumeFrom > 0 -> {
                    tmp.delete()
                    throw IOException("server rejected resume (HTTP 416) — restarting from zero")
                }
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code} fetching bundle")
            }
            val body = resp.body ?: throw IOException("empty response body")
            val resuming = resumeFrom > 0 && resp.code == 206
            val total: Long? = if (resuming) {
                // "Content-Range: bytes <start>-<end>/<total>" — <total> is
                // the FULL object size, which is what progress wants
                resp.header("Content-Range")?.substringAfterLast('/')
                    ?.toLongOrNull()?.takeIf { it > 0 }
            } else {
                body.contentLength().takeIf { it > 0 }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            if (resuming) {
                // re-hash the already-downloaded prefix, then append to it
                tmp.inputStream().use { ins ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(b)
                        if (n < 0) break
                        digest.update(b, 0, n)
                        written += n
                    }
                }
                if (written != resumeFrom) {
                    tmp.delete()
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
                // keep .part — the next attempt resumes from here
                throw IOException("truncated download: $written of $total bytes")
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && expectedSha256.length == 64 && sha != expectedSha256) {
                tmp.delete() // corrupt data must never be resumed
                throw IOException(
                    "checksum mismatch: got $sha, expected $expectedSha256 — " +
                        "the download was corrupted or the release changed"
                )
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("cannot finalize download at ${dest.path}")
            }
            onProgress(written, total)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        val BACKOFF_MS = longArrayOf(1_000L, 3_000L, 7_000L)
    }
}
