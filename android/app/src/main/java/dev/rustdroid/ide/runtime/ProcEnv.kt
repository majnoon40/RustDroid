package dev.rustdroid.ide.runtime

import java.io.File

/**
 * The subprocess environment contract. This is the Phase-1-validated recipe
 * (proven on-device 2026-09-02), plus the two app-sandbox deltas:
 *  - HOME/CARGO_HOME/TMPDIR must live under app-writable storage
 *    (an untrusted_app uid cannot write /data/local/tmp).
 *  - PATH leads with the toolchain bin dir so rustc's default linker `cc`
 *    resolves to the RustDroid shim.
 *
 * Pure JVM — unit-testable.
 */
object ProcEnv {

    fun binDir(prefix: File): File = File(prefix, "bin")
    fun libDir(prefix: File): File = File(prefix, "lib")
    fun homeDir(filesDir: File): File = File(filesDir, "home")
    fun cargoHome(filesDir: File): File = File(homeDir(filesDir), ".cargo")
    fun tmpDir(filesDir: File): File = File(homeDir(filesDir), "tmp")
    fun scratchDir(filesDir: File): File = File(homeDir(filesDir), "scratch")

    fun ensureDirs(filesDir: File) {
        homeDir(filesDir).mkdirs()
        cargoHome(filesDir).mkdirs()
        tmpDir(filesDir).mkdirs()
        scratchDir(filesDir).mkdirs()
    }

    /**
     * Environment for cargo/rustc invocations. [extraPath] is prepended
     * after the toolchain bin (e.g. system bin dir on device).
     */
    fun env(prefix: File, filesDir: File, extraPath: String = "/system/bin"): Map<String, String> {
        ensureDirs(filesDir)
        return buildMap {
            put("HOME", homeDir(filesDir).absolutePath)
            put("CARGO_HOME", cargoHome(filesDir).absolutePath)
            put("PATH", "${binDir(prefix).absolutePath}:$extraPath:/system/xbin")
            put("LD_LIBRARY_PATH", libDir(prefix).absolutePath)
            put("TMPDIR", tmpDir(filesDir).absolutePath)
            // plain output: the parser reads severity markers directly and
            // the console panel colors by stream (stdout/stderr)
            put("CARGO_TERM_COLOR", "never")
            // stable ordering for reproducible diagnostics
            put("LC_ALL", "C")
            put("TERM", "dumb")
        }
    }

    fun toolchainCommand(prefix: File, tool: String): String =
        File(binDir(prefix), tool).absolutePath
}
