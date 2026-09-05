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
 *
 * Two kinds of projects share one Home screen:
 *  - **internal** projects live under [projectsRoot] and are referenced by
 *    name (the cargo package name == directory name);
 *  - **external** projects are folders anywhere on storage (Download, …)
 *    the user opened *in place* — RustDroid edits them where they lie, never
 *    copies them, and external edits are picked up on refresh. They are
 *    referenced by absolute path (see [resolve]/[refOf]) and remembered in a
 *    one-path-per-line [externalRegistry] file.
 */
class ProjectRepository(
    private val projectsRoot: File,
    /** Registry of folders opened in place: one canonical absolute path per line. */
    private val externalRegistry: File,
    private val runner: CargoRunner,
    private val envProvider: () -> Map<String, String>,
    /** Absolute path of the bundled cargo binary. Android's JVM does NOT
     *  resolve bare command names against the child env's PATH (verified:
     *  `ProcessBuilder("cargo")` with PATH=files/usr/bin fails with
     *  error=2), so every tool invocation must use the absolute path. */
    private val cargoPath: () -> String,
) {
    fun projectsRootExists(): Boolean = projectsRoot.isDirectory

    // ---- project references (navigation currency) ----

    /**
     * Resolves a project reference to its directory: an absolute path
     * (external, starts with "/") or a bare name under [projectsRoot].
     * Navigation routes carry refs, never File objects.
     */
    fun resolve(ref: String): File =
        if (ref.startsWith("/")) File(ref) else File(projectsRoot, ref)

    /** The ref a project directory is navigated by. */
    fun refOf(dir: File): String =
        if (dir.parentFile == projectsRoot) dir.name else dir.absolutePath

    fun list(): List<dev.rustdroid.ide.model.ProjectSummary> {
        val dirs = projectsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "Cargo.toml").isFile }
            ?: emptyList()
        val internal = dirs.map { dir ->
            summary(dir, external = false)
        }
        // Folders opened in place. Registered-but-vanished folders (deleted
        // or unmounted storage) silently drop out of the list instead of
        // crashing the Home screen.
        val external = readRegistry()
            .map { File(it) }
            .filter { it.isDirectory && it.canRead() }
            .map { dir -> summary(dir, external = true) }
        return (internal + external).sortedByDescending { it.lastModifiedMs }
    }

    private fun summary(dir: File, external: Boolean): dev.rustdroid.ide.model.ProjectSummary {
        val deps = try {
            CargoToml.readDependencies(File(dir, "Cargo.toml")).size
        } catch (e: IOException) {
            -1 // no/!parseable Cargo.toml marker
        }
        return dev.rustdroid.ide.model.ProjectSummary(
            name = dir.name,
            dir = dir,
            lastModifiedMs = latestMtime(dir),
            dependencyCount = deps,
            external = external,
        )
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

    // ---- external folders (open in place, never copy) ----

    private fun readRegistry(): List<String> {
        if (!externalRegistry.isFile) return emptyList()
        return externalRegistry.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun writeRegistry(paths: List<String>) {
        externalRegistry.parentFile?.mkdirs()
        if (paths.isEmpty()) {
            externalRegistry.delete()
        } else {
            Fs.writeAtomic(externalRegistry, paths.joinToString("\n") + "\n")
        }
    }

    /** True when [dir] (canonicalized) is already opened as a project. */
    fun isRegisteredExternal(dir: File): Boolean =
        readRegistry().contains(dir.canonicalPath)

    /**
     * Remembers a folder anywhere on storage as a project. The folder is
     * NOT copied, moved or written to — it keeps living wherever the user
     * put it (Download, Documents, …) and every edit goes straight back to
     * it. Returns the registered dir (canonical), or null when it was
     * already registered (idempotent: no error, nothing changes).
     */
    fun registerExternal(dir: File): File? {
        val canonical = dir.canonicalPath
        val paths = readRegistry()
        if (canonical in paths) return null
        writeRegistry(paths + canonical)
        return File(canonical)
    }

    /** Forgets an external folder. Its files are never touched. */
    fun unregisterExternal(dir: File) {
        val canonical = dir.canonicalPath
        writeRegistry(readRegistry().filter { it != canonical })
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

    /**
     * Scaffold a minimal, fully valid cargo bin project WITHOUT touching
     * the toolchain (`cargo new` needs a working rustc; a user opening a
     * .rs attachment may not have installed one yet). Used by the
     * open-a-.rs flow's "new project" branch — same shape as `cargo new`:
     * Cargo.toml + src/main.rs.
     */
    fun createStandaloneProject(name: String): File {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
            "project name must start with a letter and contain only letters, digits, - and _"
        }
        projectsRoot.mkdirs()
        val dir = File(projectsRoot, name)
        if (dir.exists()) throw IOException("project '$name' already exists")
        dir.resolve("src").mkdirs()
        Fs.writeAtomic(
            dir.resolve("Cargo.toml"),
            "[package]\nname = \"$name\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
        )
        Fs.writeAtomic(dir.resolve("src/main.rs"), TEMPLATE_MAIN_RS)
        return dir
    }

    /**
     * Turns an arbitrary folder into a cargo project IN PLACE: writes
     * Cargo.toml and src/main.rs into [dir] only when missing — existing
     * files are never overwritten (they are the user's). Used by the
     * open-folder-as-project flow when the picked folder has no manifest.
     */
    fun ensureCargoProject(dir: File, packageName: String): File {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) {
            "package name must start with a letter and contain only letters, digits, - and _"
        }
        if (!File(dir, "Cargo.toml").isFile) {
            Fs.writeAtomic(
                dir.resolve("Cargo.toml"),
                "[package]\nname = \"$packageName\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
            )
        }
        if (!File(dir, "src/main.rs").isFile && !File(dir, "src/lib.rs").isFile) {
            dir.resolve("src").mkdirs()
            Fs.writeAtomic(dir.resolve("src/main.rs"), TEMPLATE_MAIN_RS)
        }
        return dir
    }

    fun rename(old: File, newName: String): File {
        require(newName.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]*"))) { "invalid name" }
        val target = File(old.parentFile, newName)
        if (target.exists()) throw IOException("'$newName' already exists")
        if (!old.renameTo(target)) throw IOException("rename failed")
        return target
    }

    fun delete(dir: File) {
        if (isRegisteredExternal(dir)) {
            // external folders belong to the user — forgetting them must
            // never delete the underlying files
            throw IOException("external folder — remove it from the list instead")
        }
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

    /**
     * Deletes a file — or a whole directory tree — at [relativePath] inside
     * [projectDir]. Same safety envelope as [createFile]: traversal/escape
     * is rejected via [Fs.resolveChild], and two more guards keep the
     * project itself intact: the project root ("." or an empty path) and
     * the root Cargo.toml (a folder without a manifest stops being a
     * project — remove the whole project from the Home screen instead)
     * are refused. Deleting the manifest of a nested workspace member is
     * allowed: that is a deliberate, advanced edit.
     */
    fun deleteFile(projectDir: File, relativePath: String) {
        val rel = relativePath.trim().replace('\\', '/')
        if (rel.isEmpty() || rel == ".") throw IOException("nothing to delete")
        val target = Fs.resolveChild(projectDir, rel)
        if (target.canonicalPath == projectDir.canonicalPath) {
            throw IOException("cannot delete the project root")
        }
        if (target.parentFile?.canonicalPath == projectDir.canonicalPath &&
            target.name == "Cargo.toml"
        ) {
            throw IOException(
                "Cargo.toml is what makes this folder a project — " +
                    "delete the whole project from the Home screen instead",
            )
        }
        if (!target.exists()) throw IOException("'$rel' does not exist")
        if (!Fs.deleteRecursively(target)) throw IOException("could not delete '$rel'")
    }

    // ---- .rs intake (ACTION_VIEW from file managers) ----

    /**
     * Places incoming .rs content into a project as `src/<name>`. A file
     * named main.rs replaces the template stub; any other name that already
     * exists gets a `-1`, `-2`… suffix instead of being overwritten.
     * Returns the path relative to the project root (for opening a tab).
     * Works for internal AND external projects — the caller picks the
     * destination; this method only writes into [projectDir].
     */
    fun importRsContent(projectDir: File, fileName: String, content: String): String {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (clean.isEmpty() || !clean.endsWith(".rs")) {
            throw IOException("not a Rust source name: '$fileName'")
        }
        if (!clean.matches(Regex("[A-Za-z0-9._-]+"))) {
            throw IOException("unsupported file name: '$clean'")
        }
        val src = projectDir.resolve("src")
        src.mkdirs() // destination projects may predate src/ (plain folders)
        var target = src.resolve(clean)
        if (clean == "main.rs") {
            // the template stub exists precisely to be replaced
            Fs.writeAtomic(target, content)
            return "src/main.rs"
        }
        var n = 0
        while (target.exists()) {
            n += 1
            target = src.resolve(clean.removeSuffix(".rs") + "-$n.rs")
        }
        Fs.writeAtomic(target, content)
        return target.relativeTo(projectDir).path
    }

    companion object {
        /** src/main.rs stub for toolchain-free project scaffolds. */
        const val TEMPLATE_MAIN_RS =
            "fn main() {\n    println!(\"Hello from RustDroid!\");\n}\n"

        /**
         * Maps a SAF tree document id to an absolute filesystem path:
         * `primary:Download/proj` → `<primaryStorage>/Download/proj`,
         * `ABCD-1234:proj` → `/storage/ABCD-1234/proj` (removable volume).
         * Null for anything else (cloud providers, usb, downloads providers
         * — they have no real path cargo could use). Pure string work: the
         * Android side (Uri → document id) stays a thin, untestable shell
         * around this.
         */
        fun documentIdToPath(documentId: String, primaryStoragePath: String): String? {
            val idx = documentId.indexOf(':')
            if (idx <= 0) return null
            val volume = documentId.substring(0, idx)
            val rest = documentId.substring(idx + 1)
            val base = when {
                volume == "primary" -> primaryStoragePath
                Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(volume) -> "/storage/$volume"
                else -> null
            } ?: return null
            return if (rest.isEmpty()) base else "$base/$rest"
        }
    }
}
