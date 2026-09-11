package dev.rustdroid.ide

import dev.rustdroid.ide.model.parseLicenseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * In-app license index integrity (review P3).
 *
 * The Licenses screen (Settings → Licenses, plan §7.5) reads
 * assets/licenses/LICENSE_INDEX.json at runtime and resolves each entry to
 * a text file in the same directory. Nothing verified that the files the
 * index names actually EXIST, or that a component's row pointed at ITS OWN
 * license text rather than another component's — and both defects were live
 * in the tree:
 *
 *  - the Rust row's `file` pointed at `textmate-MIT.txt` (the VS Code Rust
 *    grammar's MIT, a different component), so the screen rendered the
 *    wrong attribution under the Rust name and — because
 *    LicensesScreen.readLicenseText() returns the FIRST file that opens —
 *    never read the row's `file2` at all;
 *  - the terminal-view row pointed at `terminal-emulator-Apache-2.0.txt`,
 *    the other vendored module's file (same Apache body, but that file's
 *    provenance header is about terminal-emulator);
 *  - `terminal-view-Apache-2.0.txt` was not shipped in assets at all.
 *
 * These assertions fail against that tree and pass after the fix. The test
 * walks the REAL asset file, so it cannot drift from what the app ships.
 *
 * Assets are read from the module's source tree (Gradle unit tests run with
 * the module dir as the working directory). If the path is not found the
 * test FAILS with an explanatory message rather than skipping — a silently
 * skipped integrity test is the failure mode this file exists to prevent.
 */
class LicenseIndexTest {

    private val assetsDir = File("src/main/assets/licenses")
    private val indexPath = File(assetsDir, "LICENSE_INDEX.json")

    private fun entries() = parseLicenseIndex(indexPath.readText())

    @Test
    fun `the license index and its referenced files all exist`() {
        assertTrue(
            "asset path not found from ${File(".").absolutePath} — expected " +
                "${indexPath.path}; the assertions below would be vacuous, so this fails loudly",
            indexPath.isFile,
        )

        val all = entries()
        assertTrue("the license index listed no entries", all.isNotEmpty())

        for (e in all) {
            assertTrue("entry '${e.component}' has a blank file", e.file.isNotBlank())
            assertTrue(
                "entry '${e.component}' names '${e.file}', which is not in assets/licenses/",
                File(assetsDir, e.file).isFile,
            )
            // A row must never point its two slots at the same file: the
            // screen shows the first, so the second could never be reached.
            e.file2?.let { f2 ->
                assertTrue("entry '${e.component}' has a blank file2", f2.isNotBlank())
                assertFalse(
                    "entry '${e.component}' names the same file twice (${e.file}); " +
                        "the screen only ever reads the first",
                    f2 == e.file,
                )
                assertTrue(
                    "entry '${e.component}' names file2 '$f2', which is not in assets/licenses/",
                    File(assetsDir, f2).isFile,
                )
            }
        }
    }

    @Test
    fun `each row resolves to its own component's licence text`() {
        val all = entries()

        // Rust: must be the Rust Project's MIT text, and the row's PRIMARY
        // file must say so — this is the assertion the pre-fix index failed
        // (it pointed at the TextMate grammar's MIT).
        val rust = all.firstOrNull { it.component.startsWith("Rust ") }
        assertNotNull("no Rust row in the license index", rust)
        assertEquals("rust-MIT.txt", rust!!.file)
        val rustText = File(assetsDir, rust.file).readText()
        assertTrue(
            "the Rust row's text is not the Rust Project's MIT license " +
                "(missing the 'Rust Project Developers' copyright holder)",
            rustText.contains("Rust Project Developers"),
        )
        assertTrue(
            "the Rust row's text has no MIT grant clause",
            rustText.contains("Permission is hereby granted"),
        )

        // terminal-view: its OWN asset, with its OWN attribution line — not
        // the terminal-emulator file it used to borrow.
        val view = all.firstOrNull { it.component.contains("terminal-view") }
        assertNotNull("no terminal-view row in the license index", view)
        assertEquals("terminal-view-Apache-2.0.txt", view!!.file)
        val viewText = File(assetsDir, view.file).readText()
        assertTrue(
            "the terminal-view license text does not identify terminal-view " +
                "(borrowed another component's provenance header?)",
            viewText.contains("terminal-view"),
        )
        assertTrue(
            "the terminal-view license text is missing the Apache-2.0 body",
            viewText.contains("Apache License"),
        )

        // terminal-emulator keeps its own file (guard against a swap).
        val emulator = all.firstOrNull { it.component.contains("terminal-emulator") }
        assertNotNull("no terminal-emulator row in the license index", emulator)
        assertEquals("terminal-emulator-Apache-2.0.txt", emulator!!.file)

        // Every row's text must actually open and be non-trivial.
        for (e in all) {
            val text = File(assetsDir, e.file).readText()
            assertTrue(
                "license text for '${e.component}' (${e.file}) is suspiciously short " +
                    "(${text.length} chars)",
                text.length > 200,
            )
        }
    }

    @Test
    fun `the GPL corresponding-source offer stays on the busybox row`() {
        // Review condition 11 / plan §7.5: the BusyBox source-asset URL is
        // REQUIRED content — the bundle reaches users through the app's own
        // downloader, not the release page, so the GPL-2.0 corresponding
        // source has to be offered HERE. Regression-guard it so a future
        // index edit cannot quietly drop the offer.
        val busybox = entries().firstOrNull { it.component.contains("BusyBox") }
        assertNotNull("no BusyBox row in the license index", busybox)
        val url = busybox!!.sourceAssetUrl
        assertNotNull("the BusyBox row lost its corresponding-source URL", url)
        assertTrue(
            "the BusyBox source offer must point at a source archive, got '$url'",
            url!!.contains("src.zip") || url.contains("source"),
        )
    }
}
