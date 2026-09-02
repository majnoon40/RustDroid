package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.model.CheckStatus
import dev.rustdroid.ide.model.VerifyCheck
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import java.io.File
import java.io.IOException

/**
 * Health verification, mirroring the CI verify.sh checks 5/6 — plus the
 * ultimate gate: a real on-device compile+link+run of hello.rs through the
 * exact chain the IDE will use. A corrupted or partial install fails LOUD
 * with a per-check table instead of confusing silent errors later.
 */
class ToolchainVerifier(
    private val paths: ToolchainPaths,
    private val filesDir: File,
    private val runner: CargoRunner,
) {
    /** Runs all checks; [onCheck] fires after each one for live UI. */
    fun verify(onCheck: (VerifyCheck) -> Unit = {}): List<VerifyCheck> {
        val results = mutableListOf<VerifyCheck>()
        fun runCheck(id: String, title: String, body: () -> String?) {
            val started = VerifyCheck(id, title, CheckStatus.RUNNING)
            onCheck(started)
            val result = try {
                val detail = body()
                VerifyCheck(id, title, if (detail == null) CheckStatus.PASS else CheckStatus.FAIL, detail)
            } catch (e: Exception) {
                VerifyCheck(id, title, CheckStatus.FAIL, e.message ?: e.javaClass.simpleName)
            }
            results += result
            onCheck(result)
        }

        runCheck("bins", "rustc + cargo binaries present and executable") {
            val rustc = paths.rustc
            val cargo = paths.cargo
            when {
                !rustc.isFile -> "missing ${rustc.path}"
                !rustc.canExecute() -> "${rustc.path} not executable (extraction failed?)"
                !cargo.isFile -> "missing ${cargo.path}"
                !cargo.canExecute() -> "${cargo.path} not executable"
                else -> null
            }
        }

        runCheck("crt", "crt objects present (crtbegin_dynamic/so, crtend_android/so)") {
            val missing = listOf(
                "crtbegin_dynamic.o", "crtbegin_so.o", "crtend_android.o", "crtend_so.o"
            ).filter { !File(paths.kit, it).isFile }
            if (missing.isNotEmpty()) "missing: ${missing.joinToString()}" else null
        }

        runCheck("crt-arch", "crtbegin_dynamic.o is AArch64 ELF") {
            val f = File(paths.kit, "crtbegin_dynamic.o")
            val machine = ElfInfo.machine(f)
            when {
                machine == null -> "not a valid ELF file"
                machine != ElfInfo.EM_AARCH64 -> "wrong machine type 0x${machine.toString(16)} (want AArch64)"
                else -> null
            }
        }

        runCheck("stubs", "bionic link stubs present (libc.so et al.)") {
            val sysroot = File(paths.kit, "sysroot")
            val sos = sysroot.listFiles()?.filter { it.name.endsWith(".so") } ?: emptyList()
            when {
                !sysroot.isDirectory -> "missing ${sysroot.path}"
                sos.size < 3 -> "only ${sos.size} stub libs (want >= 3 incl. libc.so)"
                File(sysroot, "libc.so").isFile.not() -> "libc.so stub missing — links impossible"
                else -> null
            }
        }

        runCheck("shims", "cc/clang/gcc linker-driver shims present and sane") {
            for (shim in listOf("cc", "clang", "gcc")) {
                val f = File(paths.bin, shim)
                when {
                    !f.isFile -> return@runCheck "missing bin/$shim"
                    !f.canExecute() -> return@runCheck "bin/$shim not executable"
                    f.length() < 200L -> return@runCheck "bin/$shim suspiciously small (${f.length()} B)"
                }
                val text = f.readText()
                if (!text.startsWith("#!/system/bin/sh")) {
                    return@runCheck "bin/$shim bad shebang (repacked on Windows?)"
                }
                if (!text.contains("gcc-ld/ld.lld")) {
                    return@runCheck "bin/$shim does not reference ld.lld (corrupt?)"
                }
            }
            null
        }

        runCheck("unwind", "libunwind.a present with _Unwind symbols") {
            val f = File(paths.kit, "libunwind.a")
            if (!f.isFile) return@runCheck "missing ${f.path} (rustc links -lunwind on Android)"
            val info = f.inputStream().use { ArArchive.read(it) }
            if (!info.symbols.any { it.startsWith("_Unwind") }) {
                "archive defines no _Unwind_* symbols (wrong archive?)"
            } else null
        }

        runCheck("builtins", "libclang_rt.builtins.a present") {
            val f = File(paths.kit, "libclang_rt.builtins.a")
            if (!f.isFile) "missing ${f.name}" else null
        }

        runCheck("lld", "rust-lld present (gcc-ld/ld.lld + rust-lld)") {
            val lld = paths.rustLld
            val generic = File(lld.parentFile?.parentFile, "rust-lld")
            when {
                !lld.isFile -> "missing ${lld.path}"
                !generic.isFile -> "missing sibling ${generic.path} (wrapper execs it)"
                else -> null
            }
        }

        runCheck("libcxx", "libc++_shared.so present in prefix lib") {
            if (!paths.libcxx.isFile) "missing ${paths.libcxx.path} (DT_NEEDED of rustc/cargo)" else null
        }

        // 9. version probes (subprocess)
        val env = ProcEnv.env(paths.prefix, filesDir)
        var rustcVersion = ""
        runCheck("rustc-version", "rustc --version runs") {
            val out = kotlinx.coroutines.runBlocking {
                runner.probe(listOf(paths.rustc.absolutePath, "--version"), env)
            }
            if (!out.startsWith("rustc ")) "unexpected output: '${out.take(80)}'" else {
                rustcVersion = out.lineSequence().first()
                null
            }
        }
        var cargoVersion = ""
        runCheck("cargo-version", "cargo --version runs") {
            val out = kotlinx.coroutines.runBlocking {
                runner.probe(listOf(paths.cargo.absolutePath, "--version"), env)
            }
            if (!out.startsWith("cargo ")) "unexpected output: '${out.take(80)}'" else {
                cargoVersion = out.lineSequence().first()
                null
            }
        }

        // 10. THE gate: compile+link+run hello.rs through the real chain
        runCheck("smoke", "smoke test: rustc hello.rs && ./hello") {
            val scratch = paths.scratch.apply { mkdirs() }
            val helloRs = File(scratch, "hello.rs")
            val helloBin = File(scratch, "hello")
            helloRs.writeText(
                "fn main() { println!(\"hello from RustDroid on Android\"); }\n"
            )
            helloBin.delete()

            val linkResult = kotlinx.coroutines.runBlocking {
                runner.run(
                    listOf(paths.rustc.absolutePath, "hello.rs", "-o", "hello"),
                    cwd = scratch, env = env,
                )
            }
            if (!linkResult.success) {
                return@runCheck "rustc failed (exit ${linkResult.exitCode}) — see console"
            }
            if (!helloBin.isFile || !helloBin.canExecute()) {
                return@runCheck "no executable produced"
            }
            val runOut = StringBuilder()
            val runResult = kotlinx.coroutines.runBlocking {
                runner.run(
                    listOf("./hello"), cwd = scratch, env = env,
                ) { line -> if (runOut.isNotEmpty()) runOut.append('\n'); runOut.append(line.text) }
            }
            when {
                !runResult.success -> "hello exited ${runResult.exitCode}"
                !runOut.contains("hello from RustDroid") ->
                    "unexpected output: '${runOut.toString().take(60)}'"
                else -> null
            }
        }

        return results
    }

    /** ELF header peek: class + machine. */
    object ElfInfo {
        const val EM_AARCH64 = 0xB7

        fun machine(file: File): Int? = try {
            file.inputStream().use { s ->
                val h = ByteArray(20)
                var off = 0
                while (off < h.size) {
                    val n = s.read(h, off, h.size - off)
                    if (n < 0) return null
                    off += n
                }
                if (h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte() ||
                    h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()
                ) return null
                val le = h[5] == 1.toByte() // EI_DATA little-endian
                val m = if (le) {
                    (h[19].toInt() and 0xFF) shl 8 or (h[18].toInt() and 0xFF)
                } else {
                    (h[18].toInt() and 0xFF) shl 8 or (h[19].toInt() and 0xFF)
                }
                m
            }
        } catch (e: IOException) {
            null
        }
    }
}
