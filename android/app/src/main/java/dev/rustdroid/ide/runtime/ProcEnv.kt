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

    /** Android's hashed CApath — usable directly by OpenSSL as a trust dir. */
    val SYSTEM_CA_DIR = "/system/etc/security/cacerts"

    fun ensureDirs(filesDir: File) {
        homeDir(filesDir).mkdirs()
        cargoHome(filesDir).mkdirs()
        tmpDir(filesDir).mkdirs()
        scratchDir(filesDir).mkdirs()
    }

    /**
     * Environment for cargo/rustc invocations. [extraPath] is prepended
     * after the toolchain bin (e.g. system bin dir on device).
     *
     * The CA bundle is generated here (idempotent, cheap after first run,
     * self-healing when the on-disk bundle is missing or invalid) unless
     * an explicit [caBundle] is injected (unit tests). [assetProvider]
     * supplies the APK's pinned Mozilla PEM store for devices whose
     * system CA store is empty or unreadable (Android 14+ / OEM builds);
     * pass `{ CaBundle.readAssetPem(context.assets) }` from UI/repo layers.
     */
    fun env(
        prefix: File,
        filesDir: File,
        extraPath: String = "/system/bin",
        assetProvider: (() -> ByteArray?)? = null,
        caBundle: File? = CaBundle.ensure(filesDir, prefix, assetProvider = assetProvider),
    ): Map<String, String> {
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

            // ---- TLS trust for cargo's libcurl ----
            // The patched openssl-probe in the toolchain probes
            // $RUSTDROID_PREFIX/etc/{tls,ssl,etc} first.
            put("RUSTDROID_PREFIX", prefix.absolutePath)
            if (caBundle != null) {
                // authoritative: cargo's http.cainfo config override ->
                // CURLOPT_CAINFO
                put("CARGO_HTTP_CAINFO", caBundle.absolutePath)
                // OpenSSL default verify paths + curl-sys's openssl-probe
                put("SSL_CERT_FILE", caBundle.absolutePath)
                // libcurl's own env fallback
                put("CURL_CA_BUNDLE", caBundle.absolutePath)
            }
            val systemCaDir = File(SYSTEM_CA_DIR)
            if (systemCaDir.isDirectory && systemCaDir.canRead()) {
                // no-bundle fallback: the system store is a hashed CApath.
                // Only export it when actually readable — pointing OpenSSL
                // at a stub/protected dir fails verify-location setup and
                // surfaces as curl error 77 even with a valid CAfile.
                put("SSL_CERT_DIR", SYSTEM_CA_DIR)
            }
        }
    }

    fun toolchainCommand(prefix: File, tool: String): String =
        File(binDir(prefix), tool).absolutePath
}
