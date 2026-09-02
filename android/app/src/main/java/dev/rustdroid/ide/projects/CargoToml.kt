package dev.rustdroid.ide.projects

import org.tomlj.Toml
import org.tomlj.TomlParseResult
import java.io.File
import java.io.IOException

/**
 * Cargo.toml reading + surgical, format-preserving edits. Parsing uses
 * tomlj (strict); edits are line-based so comments and layout survive.
 * Pure JVM — heavily unit-tested.
 */
object CargoToml {

    data class Dependency(val name: String, val spec: String)

    /**
     * Reads the [dependencies] table: crate name -> version spec
     * (string form, e.g. `"1.0"` or `{ version = "1.0", features = [...] }`).
     */
    fun readDependencies(tomlFile: File): List<Dependency> {
        if (!tomlFile.isFile) return emptyList()
        val result: TomlParseResult = Toml.parse(tomlFile.toPath())
        if (result.hasErrors()) {
            throw IOException("Cargo.toml parse errors: " +
                result.errors().joinToString("; ") { it.message })
        }
        val table = result.getTable("dependencies") ?: return emptyList()
        return table.keySet()
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { key ->
                val raw = table.get(key) ?: ""
                Dependency(key, specToString(raw))
            }
            .sortedBy { it.name }
            .toList()
    }

    private fun specToString(raw: Any?): String = when (raw) {
        is String -> "\"$raw\""
        is Number -> raw.toString()
        else -> raw.toString()
    }

    /**
     * Adds `name = "version"` under [dependencies], creating the table if
     * absent. Replaces an existing entry with the same name. Preserves all
     * other lines byte-for-byte.
     */
    fun addDependency(tomlFile: File, name: String, version: String) {
        require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "invalid crate name: $name" }
        val line = "$name = \"$version\""

        val text = if (tomlFile.isFile) tomlFile.readText() else ""
        val lines = text.lines().toMutableList()

        val depIdx = sectionHeaderIndex(lines, "dependencies")
        val depSpec = Regex("^\\s*$name\\s*=")

        if (depIdx == null) {
            // no [dependencies] table: append one (with a blank line if needed)
            var insertAt = lines.size
            while (insertAt > 0 && lines[insertAt - 1].isBlank()) insertAt--
            if (insertAt != 0) lines.add(insertAt, "")
            lines.add("[dependencies]")
            lines.add(line)
        } else {
            val next = nextSectionStart(lines, depIdx)
            // replace existing entry?
            val existing = (depIdx + 1 until next).indexOfFirst { depSpec.matches(lines[it]) }
            if (existing >= 0) {
                lines[depIdx + 1 + existing] = line
            } else {
                // insert at the END of the table body, before the blank line
                var insertAt = next
                while (insertAt > depIdx + 1 && lines[insertAt - 1].isBlank()) insertAt--
                lines.add(insertAt, line)
            }
        }
        tomlFile.writeText(lines.joinToString("\n") + "\n")
    }

    /** Removes the `name = ...` entry from [dependencies] if present. */
    fun removeDependency(tomlFile: File, name: String): Boolean {
        if (!tomlFile.isFile) return false
        val lines = tomlFile.readText().lines().toMutableList()
        val depIdx = sectionHeaderIndex(lines, "dependencies") ?: return false
        val next = nextSectionStart(lines, depIdx)
        val depSpec = Regex("^\\s*\"?${Regex.escape(name)}\"?\\s*=")
        val idx = (depIdx + 1 until next).indexOfFirst { depSpec.matches(lines[it]) }
        if (idx < 0) return false
        lines.removeAt(depIdx + 1 + idx)
        tomlFile.writeText(lines.joinToString("\n") + "\n")
        return true
    }

    private fun sectionHeaderIndex(lines: List<String>, name: String): Int? {
        val header = Regex("^\\s*\\[${Regex.escape(name)}]\\s*$")
        return lines.indices.firstOrNull { header.matches(lines[it]) }
    }

    private fun nextSectionStart(lines: List<String>, from: Int): Int {
        val header = Regex("^\\s*\\[.*")
        return (from + 1 until lines.size).firstOrNull { header.matches(lines[it]) } ?: lines.size
    }
}
