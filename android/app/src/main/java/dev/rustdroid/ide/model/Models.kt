package dev.rustdroid.ide.model

import kotlinx.serialization.Serializable

/** Which stream a console line came from. */
enum class Stream { STDOUT, STDERR, SYSTEM }

/** One line of process output, timestamped, colors preserved for display. */
data class ConsoleLine(
    val stream: Stream,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Severity of a compiler diagnostic. */
enum class Severity { ERROR, WARNING, NOTE }

/**
 * A rustc/cargo diagnostic, normalized.
 * [file], [line], [col] are 1-based; null when unknown.
 */
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val code: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val col: Int? = null,
) {
    /** Resolved absolute path for jump-to-line, given the project root. */
    fun resolvePath(projectRoot: java.io.File): java.io.File? {
        val f = file ?: return null
        return when {
            f.startsWith("/") -> java.io.File(f)
            else -> java.io.File(projectRoot, f)
        }
    }
}

/** Result of one subprocess invocation. */
data class RunResult(
    val exitCode: Int,
    val cancelled: Boolean,
    val durationMs: Long,
) {
    val success: Boolean get() = !cancelled && exitCode == 0
}

/** One verification check row, mirroring the CI verify.sh pattern. */
enum class CheckStatus { PENDING, RUNNING, PASS, FAIL }

data class VerifyCheck(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String? = null,
)

/** Toolchain install state machine (Gate screen). */
sealed class ToolchainState {
    /** No install found, nothing in flight. */
    data object NotInstalled : ToolchainState()

    /** Download in progress: [bytes] received, [total] if known. */
    data class Downloading(val bytes: Long, val total: Long?) : ToolchainState()

    /** Unpacking + placing files: [done] entries of [total] (total may grow). */
    data class Extracting(val done: Int, val total: Int?) : ToolchainState()

    /** Health verification running; [checks] shown live in the UI. */
    data class Verifying(val checks: List<VerifyCheck>) : ToolchainState()

    /** Installed and verified. [rustcVersion] e.g. "rustc 1.85.0 (...)". */
    data class Ready(val rustcVersion: String, val cargoVersion: String) : ToolchainState()

    /** Install or verify failed; [stage] names where, [message] is actionable. */
    data class Failed(val stage: String, val message: String) : ToolchainState()
}

/** A cargo project on the Home screen. */
data class ProjectSummary(
    val name: String,
    val dir: java.io.File,
    val lastModifiedMs: Long,
    val dependencyCount: Int,
)

/** A node in the editor file tree. */
data class FileNode(
    val file: java.io.File,
    val relativePath: String,
    val isDirectory: Boolean,
    val depth: Int,
)

/** An open editor tab. Content is owned by the editor view; VM caches text. */
class EditorTab(
    val relativePath: String,
    val file: java.io.File,
    initialText: String,
) {
    var cachedText: String = initialText
    var dirty: Boolean = false
}

/** A pending jump request (from tapping a problem). */
data class JumpRequest(val relativePath: String, val line: Int, val col: Int)

/** crates.io search result row. */
@Serializable
data class CrateSummary(
    val name: String,
    val max_version: String = "",
    val description: String? = null,
    val downloads: Long = 0,
    val recent_downloads: Long? = null,
)

@Serializable
private data class CratesResponse(val crates: List<CrateSummary> = emptyList())

fun parseCratesResponse(json: String): List<CrateSummary> =
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(CratesResponse.serializer(), json).crates

/** Toolchain bundle manifest embedded in the app bundle zip. */
@Serializable
data class BundleManifest(
    val format: Int = 1,
    val rust_version: String = "",
    val target: String = "aarch64-linux-android",
    val created: String = "",
    val source_run: Long = 0,
    val source_commit: String = "",
    val components: Map<String, ComponentInfo> = emptyMap(),
) {
    @Serializable
    data class ComponentInfo(
        val file: String = "",
        val sha256: String = "",
        val size: Long = 0,
    )
}

fun parseBundleManifest(json: String): BundleManifest =
    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(BundleManifest.serializer(), json)
