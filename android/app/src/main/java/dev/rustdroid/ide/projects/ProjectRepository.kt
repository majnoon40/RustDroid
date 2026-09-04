package dev.rustdroid.ide.projects

import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * On-device cargo project CRUD under files/projects. Creation runs
 * `cargo new` through the bundled toolchain (the exact loop the IDE uses);
 * listing/renaming/deletion are plain filesystem ops. Pure JVM.
 */
class ProjectRepository(
    private val projectsRoot: File,
    private val runner: CargoRunner,
    private val envProvider: () -> Map<String, String>,
    /** Absolute path of the bundled cargo binary. Android's JVM does NOT
     *  resolve bare command names against the child env's PATH (verified:
     *  `ProcessBuilder("cargo")` with PATH=files/usr/bin fails with
     *  error=2), so every tool invocation must use the absolute path. */
    private val cargoPath: () -> String,
) {
    fun projectsRootExists(): Boolean = projectsRoot.isDirectory

    fun list(): List<dev.rustdroid.ide.model.ProjectSummary> {
        val dirs = projectsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "Cargo.toml").isFile }
            ?: return emptyList()
        return dirs.map { dir ->
            val deps = try {
                CargoToml.readDependencies(File(dir, "Cargo.toml")).size
            } catch (e: IOException) {
                -1 // parse error marker
            }
            dev.rustdroid.ide.model.ProjectSummary(
                name = dir.name,
                dir = dir,
                lastModifiedMs = latestMtime(dir),
                dependencyCount = deps,
            )
        }.sortedByDescending { it.lastModifiedMs }
    }

    private fun latestMtime(root: File): Long {
        var latest = root.lastModified()
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                latest = maxOf(latest, latestMtime(child))
            } else {
                latest = maxOf(latest, child.lastModified())
            }
        }
        return latest
    }

    /**
     * Creates a project via `cargo new --vcs none --bin/--lib NAME`.
     * `--vcs none` because the bundle ships no git binary — with the
     * default `vcs = "git"` cargo new fails while trying to run `git init`.
     */
    suspend fun create(name: String, isLib: Boolean, onLine: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
                "project name must start with a letter and contain only letters, digits, - and _"
            }
            projectsRoot.mkdirs()
            val dir = File(projectsRoot, name)
            if (dir.exists()) throw IOException("project '$name' already exists")

            // Keep the process output so a failure message can carry the
            // actual stderr instead of a bare exit code.
            val output = StringBuilder()
            val result = runner.run(
                listOf(cargoPath(), "new", "--vcs", "none", if (isLib) "--lib" else "--bin", name),
                cwd = projectsRoot,
                env = envProvider(),
                onLine = { line ->
                    synchronized(output) { output.appendLine(line.text) }
                    onLine(line.text)
                },
            )
            if (!result.success || !File(dir, "Cargo.toml").isFile) {
                Fs.deleteRecursively(dir)
                val tail = synchronized(output) { output.toString().trim() }
                    .lines().takeLast(12).joinToString("\n")
                throw IOException(
                    if (tail.isEmpty()) "cargo new failed (exit ${result.exitCode})"
                    else "cargo new failed (exit ${result.exitCode}):\n$tail"
                )
            }
            dir
        }

    fun rename(old: File, newName: String): File {
        require(newName.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "invalid name" }
        val target = File(old.parentFile, newName)
        if (target.exists()) throw IOException("'$newName' already exists")
        if (!old.renameTo(target)) throw IOException("rename failed")
        return target
    }

    fun delete(dir: File) {
        if (!Fs.deleteRecursively(dir)) throw IOException("could not delete ${dir.name}")
    }

    // ---- file tree for the editor ----

    /** Files under the project, skipping target/ and .git/. */
    fun fileTree(projectDir: File, allFiles: Boolean): List<dev.rustdroid.ide.model.FileNode> {
        val out = mutableListOf<dev.rustdroid.ide.model.FileNode>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            val sorted = children.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            for (child in sorted) {
                val rel = child.relativeTo(projectDir).path
                if (child.isDirectory) {
                    if (child.name == "target" || child.name == ".git" || depth == 0 && child.name == ".cargo") {
                        continue
                    }
                    out += dev.rustdroid.ide.model.FileNode(child, rel, true, depth)
                    walk(child, depth + 1)
                } else {
                    if (!allFiles && !isEditable(child.name)) continue
                    out += dev.rustdroid.ide.model.FileNode(child, rel, false, depth)
                }
            }
        }
        walk(projectDir, 0)
        return out
    }

    private fun isEditable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        return name == "Cargo.toml" || name == "Cargo.lock" ||
            ext in setOf("rs", "toml", "md", "txt", "json", "yml", "yaml", "lock", "cfg", "sh")
    }

    fun readFile(file: File): String {
        if (file.length() > 4L * 1024 * 1024) throw IOException("file too large to edit")
        return file.readText()
    }

    fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        Fs.writeAtomic(file, content)
    }

    /**
     * Creates an empty file at [relativePath] inside [projectDir], making
     * parent directories as needed. Traversal/escape is rejected via
     * [Fs.resolveChild]; an existing file is never overwritten.
     */
    fun createFile(projectDir: File, relativePath: String): File {
        val rel = relativePath.trim()
        if (rel.isEmpty()) throw IOException("file name is empty")
        val file = Fs.resolveChild(projectDir, rel)
        if (file.isDirectory) throw IOException("'$rel' is a directory")
        if (file.exists()) throw IOException("'$rel' already exists")
        file.parentFile?.mkdirs()
        Fs.writeAtomic(file, "")
        return file
    }
}
