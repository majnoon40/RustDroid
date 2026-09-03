package dev.rustdroid.ide.runtime

import java.io.File

/**
 * TLS root-CA trust for the bundled cargo.
 *
 * Why this exists: cargo links libcurl with a *statically built* OpenSSL
 * that has no idea where Android keeps its CA store — cargo's crates.io
 * downloads fail with libcurl error 60 ("SSL peer certificate or SSH
 * remote key was not OK"). The toolchain's vendored openssl-probe was
 * patched to probe $RUSTDROID_PREFIX/etc/{tls,ssl,etc} first, and cargo
 * also honors the CARGO_HTTP_CAINFO env override for http.cainfo.
 *
 * This object builds one concatenated PEM bundle from Android's system
 * CA stores and exposes it through every trust channel the toolchain
 * knows about:
 *  - [ProcEnv] sets CARGO_HTTP_CAINFO / SSL_CERT_FILE / CURL_CA_BUNDLE
 *    to the bundle (cargo -> CURLOPT_CAINFO, the authoritative path),
 *  - a mirror is placed at $prefix/etc/tls/cert.pem so the patched
 *    openssl-probe finds it via RUSTDROID_PREFIX even without env vars,
 *  - SSL_CERT_DIR points at the system CApath as a no-bundle fallback.
 *
 * Pure JVM — unit-testable with injected source dirs.
 */
object CaBundle {

    /** Android system CA stores, in preference order. */
    val SYSTEM_SOURCES: List<File> = listOf(
        File("/system/etc/security/cacerts"), // classic built-in store (hashed CApath)
        File("/apex/com.android.conscrypt/cacerts"), // Android 14+ Conscrypt APEX store
    )

    private const val BEGIN_MARKER = "-----BEGIN CERTIFICATE-----"

    /** Canonical bundle: $filesDir/home/.ssl/cacert.pem */
    fun bundleFile(filesDir: File): File = File(ProcEnv.homeDir(filesDir), ".ssl/cacert.pem")

    /**
     * Mirror under the toolchain prefix: the patched openssl-probe checks
     * $RUSTDROID_PREFIX/etc/tls first and looks for a file named exactly
     * "cert.pem" (first entry of its candidate list).
     */
    fun probeFile(prefix: File): File = File(prefix, "etc/tls/cert.pem")

    /**
     * Ensures the bundle exists. Returns it, or null when no system store
     * is readable (callers then rely on SSL_CERT_DIR alone). Idempotent;
     * cheap stat fast-path when the bundle is already present. Called from
     * [ProcEnv.env] on arbitrary threads, hence synchronized.
     */
    @Synchronized
    fun ensure(
        filesDir: File,
        prefix: File,
        sources: List<File> = SYSTEM_SOURCES,
    ): File? {
        val target = bundleFile(filesDir)
        if (target.isFile && target.length() > 0L) {
            syncProbeCopy(prefix, target)
            return target
        }
        val certs = StringBuilder()
        var count = 0
        for (src in sources) {
            val files = src.listFiles() ?: continue
            for (f in files.sortedBy { it.name }) {
                val pem = runCatching { f.readText() }.getOrNull() ?: continue
                if (!pem.contains(BEGIN_MARKER)) continue
                if (!pem.endsWith("\n")) certs.append('\n')
                certs.append(pem)
                count++
            }
        }
        if (count == 0) return null
        target.parentFile?.mkdirs()
        dev.rustdroid.ide.util.Fs.writeAtomic(target, certs.toString())
        syncProbeCopy(prefix, target)
        return target
    }

    /** Best-effort mirror into the prefix for the openssl-probe path. */
    private fun syncProbeCopy(prefix: File, target: File) {
        runCatching {
            val probe = probeFile(prefix)
            if (probe.isFile && probe.length() == target.length()) return
            probe.parentFile?.mkdirs()
            probe.writeText(target.readText())
        }
    }
}
