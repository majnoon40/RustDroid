package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.Diagnostic
import dev.rustdroid.ide.model.Severity

/**
 * Parses rustc / cargo diagnostics from streamed console lines.
 *
 * Recognized shapes (ANSI stripped first):
 *
 *   error[E0308]: mismatched types
 *    --> src/main.rs:2:24
 *
 *   warning: unused variable: `y`
 *    --> src/main.rs:3:9
 *
 *   error: could not compile `smoke2` (bin "smoke2") due to 1 previous error
 *
 * Summary lines (could-not-compile / warnings-emitted) are ignored so the
 * Problems list stays jumpable. Pure JVM — heavily unit-tested.
 */
class DiagnosticsParser {

    private val diagnostics = mutableListOf<Diagnostic>()

    // e.g. "error[E0308]: mismatched types" / "warning: unused ..." / "note: ..."
    private val header = Regex(
        "^(error|warning|note)(\\[(E\\d+|rustc\\d+)])?:\\s*(.*)$"
    )
    // e.g. "  --> src/main.rs:2:24" (also handles absolute paths)
    private val arrow = Regex(
        "^\\s*-+>\\s*(.+?):(\\d+)(?::(\\d+))?\\s*$"
    )

    /** Feed one raw (possibly ANSI-colored) line. */
    fun feed(rawLine: String) {
        val line = Ansi.strip(rawLine)

        // Location arrow: attach to the most recent header in the list.
        arrow.matchEntire(line)?.let { m ->
            val idx = diagnostics.indexOfLast { it.file == null }
            if (idx >= 0) {
                val cur = diagnostics[idx]
                diagnostics[idx] = cur.copy(
                    file = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull(),
                    col = m.groupValues[3].toIntOrNull(),
                )
            }
            return
        }

        val h = header.matchEntire(line) ?: return
        val severityStr = h.groupValues[1]
        val code = h.groupValues[2].removeSurrounding("[", "]")
        val message = h.groupValues[4].trim()

        if (message.isEmpty()) return
        if (message.startsWith("aborting due to") ||
            message.startsWith("could not compile") ||
            message.endsWith("warnings emitted") ||
            message.startsWith("build failed") ||
            message.contains("generated ")
        ) {
            return
        }

        val severity = when (severityStr) {
            "error" -> Severity.ERROR
            "warning" -> Severity.WARNING
            else -> Severity.NOTE
        }
        diagnostics += Diagnostic(
            severity = severity,
            message = message,
            code = code.ifEmpty { null },
        )
    }

    /** All diagnostics collected so far, errors first then warnings. */
    fun snapshot(): List<Diagnostic> = diagnostics.sortedWith(
        compareBy<Diagnostic> { it.severity }
            .thenBy { it.file ?: "" }
            .thenBy { it.line ?: 0 }
    )

    fun errorCount(): Int = diagnostics.count { it.severity == Severity.ERROR }
    fun warningCount(): Int = diagnostics.count { it.severity == Severity.WARNING }

    fun reset() {
        diagnostics.clear()
    }

    companion object {
        /** Convenience for one-shot parsing of complete output. */
        fun parseAll(lines: List<String>): List<Diagnostic> =
            DiagnosticsParser().apply { lines.forEach { feed(it) } }.snapshot()
    }
}
