package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Folders opened in place (external projects): registry persistence,
 * ref-based resolution, in-place scaffolding, SAF document-id translation.
 */
class ExternalProjectsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    // ---- SAF document-id → path (FolderLink's pure core) ----

    @Test
    fun `documentIdToPath maps primary volume folders under shared storage`() {
        assertEquals(
            "/storage/emulated/0/Download/myproj",
            ProjectRepository.documentIdToPath("primary:Download/myproj", "/storage/emulated/0"),
        )
        // the storage root itself (empty rest)
        assertEquals(
            "/storage/emulated/0",
            ProjectRepository.documentIdToPath("primary:", "/storage/emulated/0"),
        )
    }

    @Test
    fun `documentIdToPath maps removable volumes`() {
        assertEquals(
            "/storage/ABCD-1234/proj",
            ProjectRepository.documentIdToPath("ABCD-1234:proj", "/storage/emulated/0"),
        )
    }

    @Test
    fun `documentIdToPath rejects non-filesystem or malformed ids`() {
        // SAF ids for cloud/other providers have UUID-ish volumes with no
        // real path cargo could use
        assertNull(
            ProjectRepository.documentIdToPath("msd:folder", "/storage/emulated/0"),
        )
        assertNull(ProjectRepository.documentIdToPath("no-colon", "/storage/emulated/0"))
        assertNull(ProjectRepository.documentIdToPath("", "/storage/emulated/0"))
        assertNull(ProjectRepository.documentIdToPath(":weird", "/storage/emulated/0"))
    }

    // ---- registry: register / list / unregister ----

    @Test
    fun `registered folders appear on Home marked external`() {
        val repo = repo()
        val proj = tmp.newFolder("cargoproj")
        File(proj, "Cargo.toml").writeText("[package]\nname = \"x\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
        // external folders live OUTSIDE the projects root — that's the
        // whole point (Download, Documents, anywhere on storage)
        val ext = tmp.newFolder("outside", "extfolder")
        File(ext, "Cargo.toml").writeText("[package]\nname = \"y\"\nversion = \"0.1.0\"\n")
        File(ext, "src").mkdirs()
        File(ext, "src/main.rs").writeText("fn main() {}\n")

        assertNotNull(repo.registerExternal(ext))
        val list = repo.list()
        assertEquals(2, list.size)
        val external = list.first { it.external }
        assertEquals(ext.name, external.name)
        assertEquals(ext.absolutePath, external.dir.absolutePath)
        assertFalse(list.first { !it.external }.external)
    }

    @Test
    fun `registerExternal is idempotent and canonicalizes`() {
        val repo = repo()
        val ext = tmp.newFolder("extfolder")
        File(ext, "Cargo.toml").writeText("[package]\nname = \"y\"\nversion = \"0.1.0\"\n")
        assertNotNull(repo.registerExternal(ext))
        // second registration of the SAME folder (any path spelling) is a no-op
        assertNull(repo.registerExternal(File(ext.absolutePath)))
        assertEquals(1, repo.list().count { it.external })
        assertTrue(repo.isRegisteredExternal(ext))
    }

    @Test
    fun `unregisterExternal forgets the folder without touching files`() {
        val repo = repo()
        val ext = tmp.newFolder("extfolder")
        File(ext, "keep.rs").writeText("fn keep() {}")
        repo.registerExternal(ext)
        repo.unregisterExternal(ext)
        assertFalse(repo.isRegisteredExternal(ext))
        assertEquals(0, repo.list().count { it.external })
        // files are untouched — forgetting is never deletion
        assertTrue(File(ext, "keep.rs").isFile)
    }

    @Test
    fun `vanished external folders drop out of the list instead of crashing`() {
        val repo = repo()
        val ext = tmp.newFolder("extfolder")
        repo.registerExternal(ext)
        // the folder is deleted/moved on storage (by the user, not by us)
        ext.deleteRecursively()
        assertEquals(0, repo.list().count { it.external })
    }

    @Test
    fun `empty registry file is not created by list`() {
        assertFalse(File(tmp.root, "external-projects.txt").exists())
        assertEquals(0, repo().list().size)
    }

    // ---- refs (navigation currency) ----

    @Test
    fun `resolve maps bare names under projects root and paths verbatim`() {
        val repo = repo()
        assertEquals(
            File(tmp.root, "internal"),
            repo.resolve("internal"),
        )
        assertEquals(
            File("/storage/emulated/0/Download/proj"),
            repo.resolve("/storage/emulated/0/Download/proj"),
        )
    }

    @Test
    fun `refOf names internal projects and paths external ones`() {
        val repo = repo()
        val internal = tmp.newFolder("internal")
        assertEquals("internal", repo.refOf(internal))
        val ext = tmp.newFolder("outside", "extproj")
        assertEquals(ext.absolutePath, repo.refOf(ext))
    }

    // ---- in-place scaffolding ----

    @Test
    fun `ensureCargoProject writes manifest and entry into the folder`() {
        val repo = repo()
        val ext = tmp.newFolder("plain")
        repo.ensureCargoProject(ext, "plain")
        assertTrue(File(ext, "Cargo.toml").isFile)
        assertTrue(File(ext, "src/main.rs").isFile)
        assertTrue(File(ext, "Cargo.toml").readText().contains("name = \"plain\""))
    }

    @Test
    fun `ensureCargoProject never overwrites existing files`() {
        val repo = repo()
        val ext = tmp.newFolder("existing")
        val toml = File(ext, "Cargo.toml")
        toml.writeText("# user manifest\n[package]\nname = \"mine\"\nversion = \"0.1.0\"\n")
        File(ext, "src").mkdirs()
        val mainRs = File(ext, "src/main.rs")
        mainRs.writeText("fn main() { mine(); }\n")

        repo.ensureCargoProject(ext, "whatever")
        assertEquals("mine", Regex("""name = "(\w+)"""").find(toml.readText())!!.groupValues[1])
        assertEquals("fn main() { mine(); }\n", mainRs.readText())
    }

    @Test
    fun `ensureCargoProject leaves lib projects alone`() {
        val repo = repo()
        val ext = tmp.newFolder("libproj")
        File(ext, "Cargo.toml").writeText("[package]\nname = \"l\"\nversion = \"0.1.0\"\n")
        File(ext, "src").mkdirs()
        File(ext, "src/lib.rs").writeText("pub fn f() {}\n")

        repo.ensureCargoProject(ext, "l")
        // src/main.rs is NOT forced in next to lib.rs
        assertFalse(File(ext, "src/main.rs").exists())
    }

    @Test
    fun `ensureCargoProject rejects illegal package names`() {
        val repo = repo()
        val ext = tmp.newFolder("p")
        try {
            repo.ensureCargoProject(ext, "1bad")
            throw AssertionError("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `plain external folder edits work end to end`() {
        // no Cargo.toml at all — editing still lists, reads and writes files
        val repo = repo()
        val ext = tmp.newFolder("notes")
        File(ext, "solver.rs").writeText("fn a() {}\n")
        repo.registerExternal(ext)

        val summary = repo.list().first { it.external }
        val tree = repo.fileTree(summary.dir, allFiles = false)
        assertEquals(1, tree.size)
        assertEquals("solver.rs", tree[0].relativePath)

        repo.writeFile(tree[0].file, "fn a() { changed(); }\n")
        assertEquals("fn a() { changed(); }\n", File(ext, "solver.rs").readText())
    }

    @Test
    fun `delete refuses registered external folders`() {
        val repo = repo()
        val ext = tmp.newFolder("extfolder")
        File(ext, "keep.rs").writeText("x")
        repo.registerExternal(ext)
        try {
            repo.delete(ext)
            throw AssertionError("expected refusal")
        } catch (e: java.io.IOException) {
            // expected — user files are never deleted through the IDE
        }
        assertTrue(File(ext, "keep.rs").isFile)
    }
}
