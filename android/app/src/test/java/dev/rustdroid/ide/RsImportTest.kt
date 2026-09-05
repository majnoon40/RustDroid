package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.projects.RsImport
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RsImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    // ---- createStandaloneProject (the dialog's "new project" branch) ----

    @Test
    fun `createStandaloneProject scaffolds a valid cargo bin project without toolchain`() {
        val dir = repo().createStandaloneProject("fromfile")
        assertTrue(File(dir, "Cargo.toml").isFile)
        assertTrue(File(dir, "src/main.rs").isFile)
        val toml = File(dir, "Cargo.toml").readText()
        assertTrue(toml.contains("name = \"fromfile\""))
        assertTrue(toml.contains("edition = \"2021\""))
        // lives under the projects root, so HomeScreen shows it like any project
        assertEquals(File(tmp.root, "fromfile"), dir)
    }

    @Test
    fun `createStandaloneProject refuses clashing and illegal names`() {
        repo().createStandaloneProject("taken")
        assertCreateFails("taken") // no clobbering
        assertCreateFails("")      // no default surprises
        assertCreateFails("1abc")  // cargo names start with a letter
        assertCreateFails("a b")   // space
        assertCreateFails("../escape")
    }

    // ---- suggestProjectName (dialog default) ----

    @Test
    fun `suggestProjectName derives a valid cargo name from the file name`() {
        assertEquals("solver", RsImport.suggestProjectName("solver.rs"))
        assertEquals("solver", RsImport.suggestProjectName("/storage/emulated/0/Download/solver.rs"))
        assertEquals("my-tool", RsImport.suggestProjectName("my tool.rs"))
        assertEquals("rs2048", RsImport.suggestProjectName("2048.rs")) // letters first
        assertEquals("project", RsImport.suggestProjectName("???.rs"))
        assertEquals("project", RsImport.suggestProjectName(""))
    }

    // ---- importRsContent (the shared placement primitive) ----

    @Test
    fun `importRsContent places file in src and returns relative path`() {
        val dir = repo().createStandaloneProject("demo")
        val rel = repo().importRsContent(dir, "solver.rs", "fn solve() {}")
        assertEquals("src/solver.rs", rel)
        assertEquals("fn solve() {}", File(dir, "src/solver.rs").readText())
    }

    @Test
    fun `importRsContent main dot rs replaces the template stub`() {
        val dir = repo().createStandaloneProject("demo")
        val rel = repo().importRsContent(dir, "main.rs", "fn main() { real(); }")
        assertEquals("src/main.rs", rel)
        assertEquals("fn main() { real(); }", File(dir, "src/main.rs").readText())
    }

    @Test
    fun `importRsContent suffixes on name clash instead of overwriting`() {
        val dir = repo().createStandaloneProject("demo")
        repo().importRsContent(dir, "solver.rs", "v1")
        val rel2 = repo().importRsContent(dir, "solver.rs", "v2")
        assertEquals("src/solver-1.rs", rel2)
        assertEquals("v1", File(dir, "src/solver.rs").readText())
        assertEquals("v2", File(dir, "src/solver-1.rs").readText())
        val rel3 = repo().importRsContent(dir, "solver.rs", "v3")
        assertEquals("src/solver-2.rs", rel3)
    }

    @Test
    fun `importRsContent strips path prefixes from the name`() {
        val dir = repo().createStandaloneProject("demo")
        // file managers hand over display names with directories in them
        val rel = repo().importRsContent(dir, "/storage/emulated/0/Download/tool.rs", "x")
        assertEquals("src/tool.rs", rel)
        // backslash form too
        val rel2 = repo().importRsContent(dir, "C:\\Users\\me\\thing.rs", "y")
        assertEquals("src/thing.rs", rel2)
    }

    @Test
    fun `traversal-looking names are sanitized to their basename`() {
        val dir = repo().createStandaloneProject("demo")
        // the path is stripped to the basename, so ../escape.rs cannot
        // leave the project — it lands as src/escape.rs
        val rel = repo().importRsContent(dir, "../escape.rs", "x")
        assertEquals("src/escape.rs", rel)
        assertTrue(File(dir, "src/escape.rs").isFile)
        assertFalse(File(dir.parentFile, "escape.rs").exists())
        // nothing was written outside the project root
        assertEquals(1, tmp.root.list()!!.count { it == "demo" })
    }

    @Test
    fun `importRsContent rejects non-rust and hostile names`() {
        val dir = repo().createStandaloneProject("demo")
        assertImportFails(dir, "notes.md")   // not .rs
        assertImportFails(dir, "script.txt") // not .rs
        assertImportFails(dir, "a b.rs")     // space — not in the safe charset
        assertImportFails(dir, "a:b.rs")     // colon
        assertImportFails(dir, "a;b.rs")     // semicolon
        assertImportFails(dir, "")           // empty
    }

    @Test
    fun `importRsContent works into a project with no src dir yet`() {
        // external/plain folders opened in place may have no src/ scaffold
        val dir = tmp.newFolder("plainfolder")
        val rel = repo().importRsContent(dir, "solver.rs", "fn solve() {}")
        assertEquals("src/solver.rs", rel)
        assertTrue(File(dir, "src/solver.rs").isFile)
    }

    @Test
    fun `imported file is visible in the editable file tree`() {
        val dir = repo().createStandaloneProject("demo")
        repo().importRsContent(dir, "solver.rs", "fn solve() {}")
        val tree = repo().fileTree(dir, allFiles = false)
        assertTrue(tree.any { it.relativePath == "src/solver.rs" })
    }

    private fun assertImportFails(project: File, name: String) {
        try {
            repo().importRsContent(project, name, "x")
            throw AssertionError("expected IOException for '$name'")
        } catch (e: IOException) {
            // expected
        }
    }

    private fun assertCreateFails(name: String) {
        try {
            repo().createStandaloneProject(name)
            throw AssertionError("expected failure for '$name'")
        } catch (e: Exception) {
            // IllegalArgumentException (regex) or IOException (clash)
        }
    }
}
