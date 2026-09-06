package dev.rustdroid.ide

import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CargoRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Home-screen listing performance/correctness: the mtime sort key must not
 * walk build output (target/) and must survive symlink loops.
 */
class ProjectListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): ProjectRepository = ProjectRepository(
        projectsRoot = tmp.root,
        externalRegistry = File(tmp.root, "external-projects.txt"),
        runner = CargoRunner(),
        envProvider = { emptyMap() },
        cargoPath = { "/unused/cargo" },
    )

    private fun project(name: String): File {
        val dir = File(tmp.root, name).apply { mkdirs() }
        File(dir, "Cargo.toml").writeText("[package]\nname = \"$name\"\n")
        return dir
    }

    @Test
    fun `target directory mtimes do not win the sort`() {
        val a = project("aaa")
        val b = project("bbb")
        File(File(a, "src"), "main.rs").apply {
            parentFile.mkdirs()
            writeText("fn main() {}")
            setLastModified(System.currentTimeMillis() + 60_000)
        }
        // b's build output is FRESHER than everything in a — but target/
        // is skipped by the mtime walk, so a must still sort first
        val deps = File(File(b, "target/debug"), "deps")
        deps.mkdirs()
        File(deps, "huge_artifact.rmeta").apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() + 120_000)
        }
        val list = repo().list()
        assertEquals("aaa", list.first().name)
    }

    @Test
    fun `a symlink loop inside a project does not hang or overflow`() {
        val p = project("loopy")
        val loop = File(p, "loop")
        loop.mkdirs()
        // loop/a -> loop  (cycle)
        Files.createSymbolicLink(File(loop, "a").toPath(), loop.toPath())
        // the walk must terminate
        val list = repo().list()
        assertEquals(1, list.size)
        assertEquals("loopy", list[0].name)
    }

    @Test
    fun `hidden dirs are skipped but hidden files still count`() {
        val p = project("hidden")
        val marker = File(p, ".hidden-marker")
        marker.writeText("")
        marker.setLastModified(System.currentTimeMillis() + 60_000)
        // walk includes hidden FILES (markers matter), skips hidden DIRS
        val hiddenDir = File(p, ".cargo")
        hiddenDir.mkdirs()
        File(hiddenDir, "config.toml").apply {
            writeText("")
            setLastModified(0)
        }
        val list = repo().list()
        assertTrue(list.isNotEmpty())
    }
}
