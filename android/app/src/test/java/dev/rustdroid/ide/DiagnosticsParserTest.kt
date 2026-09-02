package dev.rustdroid.ide

import dev.rustdroid.ide.model.Severity
import dev.rustdroid.ide.runtime.DiagnosticsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsParserTest {

    @Test
    fun `parses error with location`() {
        val lines = listOf(
            "error[E0308]: mismatched types",
            " --> src/main.rs:2:24",
            "  |",
            "2 |     let x: i32 = \"hello\";",
            "  |                     ^^^^^^^ expected `i32`, found `&str`",
            "",
            "error: aborting due to 1 previous error; 2 warnings emitted",
        )
        val diags = DiagnosticsParser.parseAll(lines)
        assertEquals(1, diags.size)
        val d = diags[0]
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("E0308", d.code)
        assertEquals("mismatched types", d.message)
        assertEquals("src/main.rs", d.file)
        assertEquals(2, d.line)
        assertEquals(24, d.col)
    }

    @Test
    fun `parses warning and attaches later arrow`() {
        val lines = listOf(
            "warning: unused variable: `y`",
            " --> src/lib.rs:12:9",
            "  |",
            "12 |     let y = 5;",
            "  |         ^ help: if this is intentional, prefix it with `_y`",
            "  |",
            "  = note: `#[warn(unused_variables)]` on by default",
        )
        val diags = DiagnosticsParser.parseAll(lines)
        assertEquals(1, diags.size)
        assertEquals(Severity.WARNING, diags[0].severity)
        assertEquals("src/lib.rs", diags[0].file)
        assertEquals(12, diags[0].line)
    }

    @Test
    fun `summary lines are not diagnostics`() {
        val lines = listOf(
            "error: aborting due to 3 previous errors",
            "warning: `smoke2` (bin \"smoke2\") generated 4 warnings",
            "error: could not compile `smoke2` (bin \"smoke2\") due to 3 previous errors; 4 warnings emitted",
        )
        assertTrue(DiagnosticsParser.parseAll(lines).isEmpty())
    }

    @Test
    fun `strips ansi color codes`() {
        val esc = "\u001b[0m\u001b[1m\u001b[38;5;9m"
        val lines = listOf(
            "${esc}error[E0425]${esc}: cannot find value `x` in this scope",
            " ${esc}-->\u001b[0m src/main.rs:5:13",
        )
        val diags = DiagnosticsParser.parseAll(lines)
        assertEquals(1, diags.size)
        assertEquals("E0425", diags[0].code)
        assertEquals("cannot find value `x` in this scope", diags[0].message)
        assertEquals("src/main.rs", diags[0].file)
        assertEquals(5, diags[0].line)
    }

    @Test
    fun `multiple diagnostics with mixed severity sort errors first`() {
        val lines = listOf(
            "warning: unused import: `std::io`",
            " --> src/main.rs:1:5",
            "",
            "error[E0433]: failed to resolve: use of undeclared type `Map`",
            " --> src/main.rs:9:18",
        )
        val diags = DiagnosticsParser.parseAll(lines)
        assertEquals(2, diags.size)
        assertEquals(Severity.ERROR, diags[0].severity)
        assertEquals(Severity.WARNING, diags[1].severity)
    }

    @Test
    fun `empty and noise lines are ignored`() {
        val lines = listOf(
            "",
            "   Compiling smoke2 v0.1.0 (/data/…/smoke2)",
            "    Finished dev [unoptimized + debuginfo] target(s) in 4.21s",
            "     Running `target/debug/smoke2`",
            "hello from RustDroid on Android",
        )
        assertTrue(DiagnosticsParser.parseAll(lines).isEmpty())
    }

    @Test
    fun `note without location is kept`() {
        val lines = listOf(
            "note: this is a hint",
        )
        val diags = DiagnosticsParser.parseAll(lines)
        assertEquals(1, diags.size)
        assertEquals(Severity.NOTE, diags[0].severity)
        assertNotNull(diags[0].message)
    }
}
