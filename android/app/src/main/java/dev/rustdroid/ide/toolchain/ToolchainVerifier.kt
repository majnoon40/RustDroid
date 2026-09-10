package dev.rustdroid.ide.toolchain

import dev.rustdroid.ide.model.CheckStatus
import dev.rustdroid.ide.model.VerifyCheck
import dev.rustdroid.ide.runtime.CaBundle
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import java.io.File
import java.io.IOException

/**
 * Health verification, mirroring the CI verify.sh checks 5/6 — plus the
 * ultimate gate: a real on-device compile+link+run of hello.rs through the
 * exact chain the IDE will use. A corrupted or partial install fails LOUD
 * with a per-check table instead of confusing silent errors later.
 *
 * [verify] is a SUSPEND function: subprocess probes/runs go directly
 * through the injected [runner] (itself suspend, IO-dispatched), so the
 * caller's cancellation propagates into the running subprocess — the
 * nested runBlocking bridges are gone. Cancellation is NEVER mistaken
 * for a failed check: [runCheck] rethrows CancellationException before
 * the generic catch.
 */
class ToolchainVerifier(
    private val paths: ToolchainPaths,
    private val filesDir: File,
    private val runner: CargoRunner,
    private val caAssetProvider: (() -> ByteArray?)? = null,
) {
    /** Runs all checks; [onCheck] fires after each one for live UI. */
    suspend fun verify(onCheck: (VerifyCheck) -> Unit = {}): List<VerifyCheck> {
        val results = mutableListOf<VerifyCheck>()
        suspend fun runCheck(id: String, title: String, body: suspend () -> String?) {
            val started = VerifyCheck(id, title, CheckStatus.RUNNING)
            onCheck(started)
            val result = try {
                val detail = body()
                VerifyCheck(id, title, if (detail == null) CheckStatus.PASS else CheckStatus.FAIL, detail)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // a cancelled verification run is not a failed check —
                // propagate so the service/VM scope unwinds correctly
                throw e
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

        // TLS trust warms + validates the CA bundle cargo will use for
        // crates.io — failing here means every dependency download dies
        // with libcurl 77/60, so make it loud BEFORE the user hits it.
        runCheck("tls", "CA bundle usable by cargo (TLS trust)") {
            val bundle = CaBundle.ensure(
                filesDir, paths.prefix, assetProvider = caAssetProvider,
            )
            when {
                bundle == null ->
                    "no CA bundle could be built (APK asset + system stores failed) — " +
                        "crates.io downloads will fail with curl 77/60"
                !CaBundle.isUsable(bundle) ->
                    "CA bundle unusable: ${CaBundle.bundleStatus(filesDir)}"
                else -> null
            }
        }

        // 9. version probes (subprocess)
        val env = ProcEnv.env(paths.prefix, filesDir)
        var rustcVersion = ""
        runCheck("rustc-version", "rustc --version runs") {
            val out = runner.probe(listOf(paths.rustc.absolutePath, "--version"), env)
            if (!out.startsWith("rustc ")) "unexpected output: '${out.take(80)}'" else {
                rustcVersion = out.lineSequence().first()
                null
            }
        }
        var cargoVersion = ""
        runCheck("cargo-version", "cargo --version runs") {
            val out = runner.probe(listOf(paths.cargo.absolutePath, "--version"), env)
            if (!out.startsWith("cargo ")) "unexpected output: '${out.take(80)}'" else {
                cargoVersion = out.lineSequence().first()
                null
            }
        }

        // ---- Phase 4 (plan §6.5): BusyBox install-time gate ------------
        // The terminal's shell: present, executable, STATIC AArch64 ELF
        // (no PT_INTERP — the inverse of the toolchain's PT_INTERP check),
        // actually EXECUTES through the exact env the terminal will use,
        // and the sh/ash symlinks resolve. A pty round-trip smoke belongs
        // to the on-device validation checklist (§6.6: interactive bring-
        // up) — the live terminal path is exercised by real use, and the
        // verifier stays a pure install-time gate (recorded in DEVIATIONS).
        val busybox = File(paths.prefix, "bin/busybox")
        runCheck("busybox", "busybox present, executable, static AArch64 ELF") {
            when {
                !busybox.isFile -> "missing ${busybox.path} — the bundle is pre-busybox?"
                !busybox.canExecute() -> "${busybox.path} not executable"
                // elfStaticAarch64's contract: null = valid static AArch64
                // with no PT_INTERP (check PASSES); a non-null String IS
                // the failure detail. The earlier wrapper inverted this —
                // it returned "not an ELF file (magic mismatch)" for every
                // VALID busybox, so no install could ever pass this gate.
                else -> elfStaticAarch64(busybox)
            }
        }
        runCheck("busybox-sh", "busybox sh -c 'echo ok' runs in the terminal env") {
            // the EXACT env the terminal session will use (TerminalEnv —
            // TERM=xterm-256color and the whole ProcEnv channel set)
            val termEnv = dev.rustdroid.ide.runtime.terminal.TerminalEnv.env(
                paths.prefix, filesDir, assetProvider = caAssetProvider,
            )
            val out = runner.probe(
                listOf(busybox.absolutePath, "sh", "-c", "echo ok"), termEnv,
            )
            if (out.trim() != "ok") "unexpected output: '${out.take(80)}'" else null
        }
        runCheck("shell-symlinks", "sh and ash symlinks resolve to busybox") {
            val sh = File(paths.prefix, "bin/sh")
            val ash = File(paths.prefix, "bin/ash")
            when {
                !java.nio.file.Files.isSymbolicLink(sh.toPath()) ->
                    "${sh.path} is not a symlink (manifest v2 symlinks pass failed?)"
                !java.nio.file.Files.isSymbolicLink(ash.toPath()) ->
                    "${ash.path} is not a symlink"
                else -> {
                    val shReal = runCatching { sh.canonicalFile }.getOrNull()
                    val ashReal = runCatching { ash.canonicalFile }.getOrNull()
                    when {
                        shReal != busybox.canonicalFile ->
                            "bin/sh resolves to ${shReal?.path ?: "(unresolvable)"}, not busybox"
                        ashReal != busybox.canonicalFile ->
                            "bin/ash resolves to ${ashReal?.path ?: "(unresolvable)"}, not busybox"
                        else -> null
                    }
                }
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

            val linkResult = runner.run(
                listOf(paths.rustc.absolutePath, "hello.rs", "-o", "hello"),
                cwd = scratch, env = env,
            )
            if (!linkResult.success) {
                return@runCheck "rustc failed (exit ${linkResult.exitCode}) — see console"
            }
            if (!helloBin.isFile || !helloBin.canExecute()) {
                return@runCheck "no executable produced"
            }
            val runOut = StringBuilder()
            val runResult = runner.run(
                listOf("./hello"), cwd = scratch, env = env,
                onLine = { line ->
                    if (runOut.isNotEmpty()) runOut.append('\n')
                    runOut.append(line.text)
                },
            )
            when {
                !runResult.success -> "hello exited ${runResult.exitCode}"
                !runOut.contains("hello from RustDroid") ->
                    "unexpected output: '${runOut.toString().take(60)}'"
                else -> null
            }
        }

        return results
    }

    /**
     * Pure-Kotlin ELF check (plan §6.5): null = static AArch64 with no
     * PT_INTERP; a String = the specific problem. Only the 64-byte ELF64
     * header + program header table is read — no library dependency.
     */
    private fun elfStaticAarch64(f: File): String? {
        val header = f.inputStream().use { it.readNBytes(64) }
        if (header.size < 64 || header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) return "not an ELF file"
        val machine = ((header[19].toInt() and 0xff) shl 8) or (header[18].toInt() and 0xff)
        if (machine != 0xb7) return "not AArch64 (e_machine=0x${machine.toString(16)})"
        fun u16(o: Int) = ((header[o + 1].toInt() and 0xff) shl 8) or (header[o].toInt() and 0xff)
        fun u64(o: Int): Long {
            var v = 0L
            for (b in 0 until 8) v = (v shl 8) or (header[o + (7 - b)].toLong() and 0xff)
            return v
        }
        val off = u64(32)
        val phentsize = u16(54)
        val phnum = u16(56)
        if (phentsize < 56 || phnum <= 0 || phnum > 64) return "implausible program header table"
        f.inputStream().use { input ->
            var skipped = 0L
            while (skipped < off) {
                val n = input.skip(off - skipped)
                if (n <= 0) return "cannot seek to program headers"
                skipped += n
            }
            repeat(phnum) {
                val ph = input.readNBytes(phentsize)
                if (ph.size < phentsize) return "truncated program header table"
                val type = ((ph[3].toInt() and 0xff) shl 24) or ((ph[2].toInt() and 0xff) shl 16) or
                    ((ph[1].toInt() and 0xff) shl 8) or (ph[0].toInt() and 0xff)
                if (type == 3) return "PT_INTERP present — busybox is not static"
            }
        }
        return null
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
