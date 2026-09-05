package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileCreateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // createFile never touches the runner/env/cargo; dummies are fine.
    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    @Test
    fun `createFile writes empty file in project root`() {
        val project = tmp.newFolder("proj")
        val f = repo().createFile(project, "notes.md")
        assertEquals(File(project, "notes.md"), f)
        assertTrue(f.isFile)
        assertEquals("", f.readText())
    }

    @Test
    fun `createFile builds nested parent directories`() {
        val project = tmp.newFolder("proj")
        val f = repo().createFile(project, "src/bin/tool.rs")
        assertEquals(File(File(project, "src/bin"), "tool.rs").path, f.path)
        assertTrue(f.isFile)
        assertTrue(File(project, "src/bin").isDirectory)
    }

    @Test
    fun `createFile trims surrounding whitespace`() {
        val project = tmp.newFolder("proj")
        val f = repo().createFile(project, "  main.rs  ")
        assertEquals("main.rs", f.name)
    }

    @Test
    fun `createFile rejects empty and blank names`() {
        val project = tmp.newFolder("proj")
        assertCreateFails(project, "")
        assertCreateFails(project, "   ")
    }

    @Test
    fun `createFile rejects traversal and absolute paths`() {
        val project = tmp.newFolder("proj")
        assertCreateFails(project, "../escape.rs")
        assertCreateFails(project, "a/../../escape.rs")
        assertCreateFails(project, "/abs.rs")
    }

    @Test
    fun `createFile refuses to overwrite existing files`() {
        val project = tmp.newFolder("proj")
        File(project, "main.rs").writeText("existing")
        assertCreateFails(project, "main.rs")
        // original content untouched
        assertEquals("existing", File(project, "main.rs").readText())
    }

    @Test
    fun `createFile refuses when a directory of that name exists`() {
        val project = tmp.newFolder("proj")
        File(project, "src").mkdirs()
        assertCreateFails(project, "src")
    }

    @Test
    fun `created files appear in the editable file tree`() {
        val project = tmp.newFolder("proj")
        repo().createFile(project, "src/main.rs")
        val tree = repo().fileTree(project, allFiles = false)
        assertTrue(tree.any { it.relativePath == "src/main.rs" })
    }

    private fun assertCreateFails(project: File, name: String) {
        try {
            repo().createFile(project, name)
            throw AssertionError("expected IOException for '$name'")
        } catch (e: IOException) {
            // expected
        }
    }
}
