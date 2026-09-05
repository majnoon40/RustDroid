package dev.rustdroid.ide.projects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Resolves ACTION_VIEW intents for .rs sources and lands them in the
 * "imported" project (see [ProjectRepository.ensureImportProject]). All
 * Android ContentResolver/Uri work lives here so the repository stays
 * pure-JVM and unit-testable.
 */
class RsImport(
    private val context: Context,
    private val repo: ProjectRepository,
) {

    /** Where the editor should land after importing [uri]: project + file. */
    data class Target(val projectName: String, val relativePath: String)

    /**
     * Reads the source behind [uri] (content:// from SAF, file:// from
     * legacy managers), writes it into the imported project and returns the
     * open target. Call on a background dispatcher.
     */
    fun import(uri: Uri): Target {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException("cannot determine file name for $uri")
        val content = readText(uri)
        val dir = repo.ensureImportProject()
        val rel = repo.importRsContent(dir, name, content)
        return Target(repo.importProjectName, rel)
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
}
