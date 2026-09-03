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

    /**
     * Last-resort TLS escape hatch (README -> Troubleshooting). Creating an
     * empty file with this name in a project root opts THAT project out of
     * cargo certificate verification; the same file under $HOME (see
     * [INSECURE_TLS_MARKER_HOME]) opts every project out. Read at env build
     * time — no restart needed since envs are rebuilt per invocation.
     */
    const val PROJECT_INSECURE_TLS_MARKER = ".rustdroid-insecure-tls"
    const val INSECURE_TLS_MARKER_HOME = ".config/rustdroid/insecure-tls"

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
     *
     * TLS trust is CAfile-only, on purpose. [SSL_CERT_DIR] used to be
     * exported pointing at Android's hashed CApath (/system/etc/security/
     * cacerts); libcurl turns that env var into CURLOPT_CAPATH, and the
     * statically-linked TLS backend in Android cargo builds rejects the
     * WHOLE verify-location setup whenever a CApath is configured — curl
     * error 77 with "error setting certificate verify locations" even when
     * the CAfile is present, readable, and full of parseable certificates.
     * With CApath out of the picture, the explicit CAfile channels below
     * are authoritative and nothing probes hashed directories.
     *
     * [insecureTlsOk] is the last-resort escape hatch: when true (or when
     * the marker file exists), cargo receives CARGO_HTTP_DANGER_ACCEPT_
     * INVALID_CERTS=true — the env form of `http.danger-accept-invalid-
     * certs` — which skips certificate verification entirely and bypasses
     * the failing verify-location setup. Never enabled by default.
     */
    fun env(
        prefix: File,
        filesDir: File,
        extraPath: String = "/system/bin",
        assetProvider: (() -> ByteArray?)? = null,
        caBundle: File? = CaBundle.ensure(filesDir, prefix, assetProvider = assetProvider),
        insecureTlsOk: Boolean = false,
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

            // ---- TLS trust for cargo's libcurl (CAfile channels only) ----
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
            // deliberately NO SSL_CERT_DIR here — see the doc comment above

            // Diagnostic build: cargo forwards this to libcurl's
            // CURLOPT_VERBOSE, so TLS setup failures print their exact
            // cause (and successes print "certificate verify locations
            // .. ok") into the console. Remove once the error-77 hunt
            // is over.
            put("CARGO_HTTP_DEBUG", "true")

            if (insecureTlsOk ||
                File(homeDir(filesDir), INSECURE_TLS_MARKER_HOME).isFile
            ) {
                put("CARGO_HTTP_DANGER_ACCEPT_INVALID_CERTS", "true")
            }
        }
    }

    fun toolchainCommand(prefix: File, tool: String): String =
        File(binDir(prefix), tool).absolutePath
}
