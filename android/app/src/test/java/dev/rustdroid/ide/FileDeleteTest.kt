package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileDeleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // deleteFile never touches the runner/env/cargo; dummies are fine.
    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    @Test
    fun `deleteFile removes a file in the project root`() {
        val project = tmp.newFolder("proj")
        File(project, "notes.md").writeText("bye")
        repo().deleteFile(project, "notes.md")
        assertFalse(File(project, "notes.md").exists())
    }

    @Test
    fun `deleteFile removes nested files`() {
        val project = tmp.newFolder("proj")
        File(File(project, "src/bin"), "tool.rs").apply {
            parentFile!!.mkdirs()
            writeText("fn main() {}")
        }
        repo().deleteFile(project, "src/bin/tool.rs")
        assertFalse(File(project, "src/bin/tool.rs").exists())
        // parent directories are left behind — only the entry itself dies
        assertTrue(File(project, "src/bin").isDirectory)
    }

    @Test
    fun `deleteFile removes an empty directory`() {
        val project = tmp.newFolder("proj")
        File(project, "examples").mkdirs()
        repo().deleteFile(project, "examples")
        assertFalse(File(project, "examples").exists())
    }

    @Test
    fun `deleteFile removes a non-empty directory recursively`() {
        val project = tmp.newFolder("proj")
        File(File(project, "src/deep"), "a.rs").apply {
            parentFile!!.mkdirs()
            writeText("a")
        }
        File(File(project, "src/deep"), "b.rs").writeText("b")
        File(File(project, "src/deep/nested"), "c.rs").apply {
            parentFile!!.mkdirs()
            writeText("c")
        }
        repo().deleteFile(project, "src/deep")
        assertFalse(File(project, "src/deep").exists())
        assertTrue(File(project, "src").isDirectory)
    }

    @Test
    fun `deleteFile rejects missing paths`() {
        val project = tmp.newFolder("proj")
        assertDeleteFails(project, "ghost.rs")
    }

    @Test
    fun `deleteFile rejects traversal and absolute paths`() {
        val project = tmp.newFolder("proj")
        assertDeleteFails(project, "../escape.rs")
        assertDeleteFails(project, "a/../../escape.rs")
        assertDeleteFails(project, "/abs.rs")
    }

    @Test
    fun `deleteFile refuses the project root`() {
        val project = tmp.newFolder("proj")
        File(project, "src").mkdirs()
        assertDeleteFails(project, ".")
        assertDeleteFails(project, "src/..") // resolves to the root
    }

    @Test
    fun `deleteFile refuses the root manifest but allows nested ones`() {
        val project = tmp.newFolder("proj")
        File(project, "Cargo.toml").writeText("[package]\nname = \"p\"\n")
        File(File(project, "member"), "Cargo.toml").apply {
            parentFile!!.mkdirs()
            writeText("[package]\nname = \"m\"\n")
        }
        assertDeleteFails(project, "Cargo.toml")
        assertTrue(File(project, "Cargo.toml").isFile) // untouched

        repo().deleteFile(project, "member/Cargo.toml")
        assertFalse(File(project, "member/Cargo.toml").exists())
    }

    @Test
    fun `deleteFile rejects empty and blank paths`() {
        val project = tmp.newFolder("proj")
        assertDeleteFails(project, "")
        assertDeleteFails(project, "   ")
    }

    @Test
    fun `deleted entries disappear from the file tree`() {
        val project = tmp.newFolder("proj")
        File(File(project, "src"), "main.rs").apply {
            parentFile!!.mkdirs()
            writeText("fn main() {}")
        }
        val before = repo().fileTree(project, allFiles = false)
        assertTrue(before.any { it.relativePath == "src/main.rs" })

        repo().deleteFile(project, "src/main.rs")

        val after = repo().fileTree(project, allFiles = false)
        assertFalse(after.any { it.relativePath == "src/main.rs" })
    }

    private fun assertDeleteFails(project: File, path: String) {
        try {
            repo().deleteFile(project, path)
            throw AssertionError("expected IOException for '$path'")
        } catch (e: IOException) {
            // expected
        }
    }
}
