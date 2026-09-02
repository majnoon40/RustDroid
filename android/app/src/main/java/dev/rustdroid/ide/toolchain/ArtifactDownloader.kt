package dev.rustdroid.ide.toolchain

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Streaming downloader for the toolchain bundle with SHA-256 verification.
 * Progress callback fires roughly every 256 KB. Pure JVM.
 */
class ArtifactDownloader(private val client: OkHttpClient) {

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

        val request = Request.Builder().url(url).header("User-Agent", "RustDroid/0.1 (Android IDE)").build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("network error: ${e.message}")
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching bundle")
            val body = resp.body ?: throw IOException("empty response body")
            val total = body.contentLength().takeIf { it > 0 }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val buf = ByteArray(64 * 1024)
            var lastReport = 0L

            tmp.outputStream().use { out ->
                val input = body.byteStream()
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
                tmp.delete()
                throw IOException("truncated download: $written of $total bytes")
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && expectedSha256.length == 64 && sha != expectedSha256) {
                tmp.delete()
                throw IOException(
                    "checksum mismatch: got $sha, expected $expectedSha256 — the download was corrupted or the release changed"
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
}
