package dev.rustdroid.ide.projects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Reads ACTION_VIEW .rs sources WITHOUT writing anywhere: the open-file
 * dialog asks the user where the file should go (new project vs. existing
 * project) before anything lands on disk, so intake is split — this class
 * only reads (name + content), [ProjectRepository.importRsContent] places
 * it once a destination is chosen. All Android ContentResolver/Uri work
 * lives here so the repository stays pure-JVM and unit-testable.
 */
class RsImport(private val context: Context) {

    /** An incoming .rs source: its display name and full text. */
    data class Source(val fileName: String, val content: String)

    /**
     * Reads the source behind [uri] (content:// from SAF, file:// from
     * legacy managers). Call on a background dispatcher.
     */
    fun peek(uri: Uri): Source {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException("cannot determine file name for $uri")
        return Source(name, readText(uri))
    }

    /** True when the intent is an ACTION_VIEW we can handle (see the manifest filters). */
    fun handles(uri: Uri?, mimeType: String?): Boolean =
        uri != null && (uri.toString().endsWith(".rs") || mimeType == "text/x-rust")

    private fun readText(uri: Uri): String {
        // Same 4 MiB cap as ProjectRepository.readFile (larger files are
        // rejected by the editor anyway); manual copy because readNBytes is
        // not available without core library desugaring.
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open $uri")
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > 4 * 1024 * 1024) throw IOException("file too large to edit")
                out.write(buf, 0, n)
            }
            out.toByteArray().decodeToString()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    companion object {
        /**
         * Cargo package-name suggestion for the "new project" dialog field:
         * the basename minus `.rs`, whitespace runs folded to `-`, cargo
         * charset only (letters/digits/-/_), a letter forced up front
         * ("2048.rs" → "rs2048" — cargo names must start with a letter),
         * "project" as the last-resort fallback. Pure string work —
         * unit-testable.
         */
        fun suggestProjectName(fileName: String): String {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\')
                .removeSuffix(".rs")
                .replace(Regex("\\s+"), "-")
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(32)
            if (base.isEmpty()) return "project"
            return if (base[0].isLetter()) base else "rs$base"
        }
    }
}
