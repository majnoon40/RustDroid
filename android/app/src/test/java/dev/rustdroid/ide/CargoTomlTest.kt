package dev.rustdroid.ide

import dev.rustdroid.ide.projects.CargoToml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CargoTomlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun toml(content: String) = tmp.newFile("Cargo.toml").apply { writeText(content) }

    @Test
    fun `reads simple dependencies`() {
        val f = toml(
            """
            [package]
            name = "smoke"
            version = "0.1.0"

            [dependencies]
            serde = "1.0"
            anyhow = "1"
            """.trimIndent()
        )
        val deps = CargoToml.readDependencies(f)
        assertEquals(listOf("anyhow", "serde"), deps.map { it.name })
        assertEquals("\"1\"", deps.first { it.name == "anyhow" }.spec)
    }

    @Test
    fun `no dependencies section yields empty`() {
        val f = toml(
            """
            [package]
            name = "smoke"
            version = "0.1.0"
            """.trimIndent()
        )
        assertTrue(CargoToml.readDependencies(f).isEmpty())
    }

    @Test
    fun `add to existing section preserves formatting and comments`() {
        val f = toml(
            """
            [package]
            name = "smoke"
            version = "0.1.0"
            edition = "2021"

            [dependencies]
            # core deps
            serde = "1.0"

            [profile.release]
            lto = true
            """.trimIndent()
        )
        CargoToml.addDependency(f, "rand", "0.8")

        val text = f.readText()
        assertTrue(text.contains("# core deps"))
        assertTrue(text.contains("serde = \"1.0\""))
        assertTrue(Regex("""rand = "0\.8"\s*\n\s*\[profile\.release]""").containsMatchIn(text))
        // parses cleanly after edit
        assertEquals(listOf("rand", "serde"), CargoToml.readDependencies(f).map { it.name })
    }

    @Test
    fun `add creates section when absent`() {
        val f = toml(
            """
            [package]
            name = "smoke"
            version = "0.1.0"
            """.trimIndent()
        )
        CargoToml.addDependency(f, "rand", "0.8")
        val text = f.readText()
        assertTrue(text.contains("[dependencies]"))
        assertTrue(text.contains("rand = \"0.8\""))
        assertEquals(1, CargoToml.readDependencies(f).size)
    }

    @Test
    fun `add replaces existing version`() {
        val f = toml(
            """
            [dependencies]
            serde = "1.0"
            """.trimIndent()
        )
        CargoToml.addDependency(f, "serde", "1.0.200")
        assertEquals(1, CargoToml.readDependencies(f).size)
        assertEquals("\"1.0.200\"", CargoToml.readDependencies(f)[0].spec)
    }

    @Test
    fun `remove deletes only the matching line`() {
        val f = toml(
            """
            [dependencies]
            serde = "1.0"
            rand = "0.8"
            """.trimIndent()
        )
        assertTrue(CargoToml.removeDependency(f, "serde"))
        assertFalse(f.readText().contains("serde"))
        assertTrue(f.readText().contains("rand = \"0.8\""))
        assertEquals(listOf("rand"), CargoToml.readDependencies(f).map { it.name })
    }

    @Test
    fun `remove returns false when absent`() {
        val f = toml("[dependencies]\nrand = \"0.8\"\n")
        assertFalse(CargoToml.removeDependency(f, "serde"))
    }

    @Test
    fun `malformed toml fails loud`() {
        val f = toml("[package\nname = broken")
        try {
            CargoToml.readDependencies(f)
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("parse"))
        }
    }
}
