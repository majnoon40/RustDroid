package dev.rustdroid.ide

import dev.rustdroid.ide.model.CheckStatus
import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.RunResult
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.toolchain.ToolchainPaths
import dev.rustdroid.ide.toolchain.ToolchainVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifier-level tests: coroutine cancellation must propagate out of
 * verify() (never masquerade as a failed check), and a broken install
 * fails loud without throwing.
 */
class ToolchainVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Subprocess calls hang forever — cancellation is the only exit. */
    private class HangingRunner : CargoRunner() {
        override suspend fun run(
            command: List<String>,
            cwd: File,
            env: Map<String, String>,
            onLine: (ConsoleLine) -> Unit,
            stdin: StdinPipe?,
        ): RunResult = awaitCancellation()

        override suspend fun probe(
            command: List<String>,
            env: Map<String, String>,
            timeoutSec: Long,
        ): String = awaitCancellation()
    }

    /** Instant, empty subprocess results — for fail-path checks. */
    private class NoopRunner : CargoRunner() {
        override suspend fun run(
            command: List<String>,
            cwd: File,
            env: Map<String, String>,
            onLine: (ConsoleLine) -> Unit,
            stdin: StdinPipe?,
        ): RunResult = RunResult(-1, false, 0)

        override suspend fun probe(
            command: List<String>,
            env: Map<String, String>,
            timeoutSec: Long,
        ): String = ""
    }

    // ------------------------------------------------------------------
    // Layout helpers: a structurally valid prefix so verification reaches
    // the subprocess checks (where the hanging runner parks).
    // ------------------------------------------------------------------

    private fun aarch64Elf(): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x7F; h[1] = 'E'.code.toByte(); h[2] = 'L'.code.toByte(); h[3] = 'F'.code.toByte()
        h[4] = 2   // ELFCLASS64
        h[5] = 1   // little-endian
        h[18] = 0xB7.toByte(); h[19] = 0 // EM_AARCH64
        return h
    }

    /** GNU-style ar member: 60-byte header + payload, even-padded. */
    private fun arMember(name: String, data: ByteArray): ByteArray {
        val h = ByteArray(60)
        val nameB = name.toByteArray(Charsets.US_ASCII).copyOf(16)
        System.arraycopy(nameB, 0, h, 0, 16)
        val sizeStr = data.size.toString().padStart(10)
        System.arraycopy(sizeStr.toByteArray(Charsets.US_ASCII), 0, h, 48, 10)
        h[58] = '`'.code.toByte(); h[59] = '\n'.code.toByte()
        return h + data + if (data.size % 2 == 1) ByteArray(1) else ByteArray(0)
    }

    /** ar archive carrying a "/" symbol-table member with [symbols]. */
    private fun arWithSymbols(vararg symbols: String): ByteArray {
        val names = symbols.joinToString("") { "$it\u0000" }
        val count = symbols.size
        val data = ByteArray(4 + 4 * count)
        data[0] = ((count shr 24) and 0xFF).toByte()
        data[1] = ((count shr 16) and 0xFF).toByte()
        data[2] = ((count shr 8) and 0xFF).toByte()
        data[3] = (count and 0xFF).toByte()
        return "!<arch>\n".toByteArray(Charsets.US_ASCII) +
            arMember("/", data + names.toByteArray(Charsets.US_ASCII))
    }

    /** Builds a prefix that passes every static check up to the probes. */
    private fun fakePrefix(): Pair<ToolchainPaths, File> {
        val filesDir = tmp.newFolder()
        val paths = ToolchainPaths(filesDir)
        val bin = paths.bin.apply { mkdirs() }
        listOf("rustc", "cargo").forEach {
            val f = File(bin, it)
            f.writeText("#!/system/bin/sh\nfake\n")
            f.setExecutable(true)
        }
        for (shim in listOf("cc", "clang", "gcc")) {
            val f = File(bin, shim)
            f.writeText(
                "#!/system/bin/sh\n# fake shim referencing gcc-ld/ld.lld and rust-lld\n" +
                    "#".repeat(200) + "\n"
            )
            f.setExecutable(true)
        }
        val kit = paths.kit.apply { mkdirs() }
        for (n in listOf("crtbegin_dynamic.o", "crtbegin_so.o", "crtend_android.o", "crtend_so.o")) {
            File(kit, n).writeBytes(aarch64Elf())
        }
        val sysroot = File(kit, "sysroot").apply { mkdirs() }
        listOf("libc.so", "libm.so", "libdl.so").forEach { File(sysroot, it).writeText("stub") }
        File(kit, "libunwind.a").writeBytes(arWithSymbols("_Unwind_Resume", "_Unwind_Backtrace"))
        File(kit, "libclang_rt.builtins.a").writeText("stub")
        // rust-lld pair: gcc-ld/ld.lld + sibling rust-lld
        File(paths.rustLld.parentFile, "gcc-ld").mkdirs()
        paths.rustLld.writeText("fake lld")
        paths.rustLld.setExecutable(true)
        File(paths.rustLld.parentFile.parentFile, "rust-lld").apply {
            parentFile.mkdirs()
            writeText("fake generic lld")
            setExecutable(true)
        }
        paths.libcxx.parentFile.mkdirs()
        paths.libcxx.writeText("fake libc++")
        return paths to filesDir
    }

    private val pem = "-----BEGIN CERTIFICATE-----\nTESTROOT\n-----END CERTIFICATE-----\n".toByteArray()

    @Test
    fun `cancellation propagates instead of becoming a failed check`() = runBlocking {
        val (paths, filesDir) = fakePrefix()
        val verifier = ToolchainVerifier(
            paths, filesDir, HangingRunner(), caAssetProvider = { pem },
        )

        val deferred = async { verifier.verify() }
        // let the static checks pass and the first subprocess probe hang
        delay(500)
        deferred.cancel()

        try {
            deferred.await()
            // verify() completing normally would mean the cancellation was
            // swallowed into a FAIL check (the old behavior)
            fail("cancellation was swallowed — verify() completed normally")
        } catch (e: CancellationException) {
            // expected: structured cancellation unwound through verify(),
            // runCheck and the subprocess probe
        }
    }

    @Test
    fun `empty prefix fails checks loud without throwing`() = runBlocking {
        val filesDir = tmp.newFolder()
        val verifier = ToolchainVerifier(ToolchainPaths(filesDir), filesDir, NoopRunner())

        val checks = verifier.verify()

        assertTrue(checks.isNotEmpty())
        val failed = checks.filter { it.status == CheckStatus.FAIL }
        // many checks must FAIL (bins missing, crt missing, ...) — loud,
        // structured, no exception
        assertTrue("expected several failing checks, got ${failed.size}", failed.size >= 5)
    }
}
