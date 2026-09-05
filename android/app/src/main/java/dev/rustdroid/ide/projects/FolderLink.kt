package dev.rustdroid.ide.projects

import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import java.io.File

/**
 * Translates a SAF folder-pick (`ACTION_OPEN_DOCUMENT_TREE` result) into the
 * real filesystem path RustDroid edits in place. The translation itself is
 * pure and unit-tested ([ProjectRepository.documentIdToPath]); this class
 * is the thin Android shell that pulls the document id out of the Uri.
 *
 * RustDroid targets SDK 28 (the Termux strategy — exec from app data), so
 * with WRITE_EXTERNAL_STORAGE granted the plain File API works on shared
 * storage; cargo needs real POSIX paths anyway, so SAF's DocumentFile
 * indirection is a non-starter for an IDE.
 */
class FolderLink {

    /**
     * The folder behind a picked tree Uri, or null when the pick is not on
     * local storage (cloud provider, USB, …) — those have no path the
     * toolchain could use, and the caller surfaces that clearly.
     */
    fun toFolder(treeUri: Uri): File? {
        // "com.android.externalstorage.documents" — the constant is hidden
        // API, the string is stable forever (it's the provider authority
        // every device's DocumentsUI resolves local storage through).
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
            return null
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return ProjectRepository.documentIdToPath(documentId, primary)?.let(::File)
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
