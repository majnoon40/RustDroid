package dev.rustdroid.ide.runtime

import java.io.File

/**
 * TLS root-CA trust for the bundled cargo.
 *
 * Why this exists: cargo links libcurl with a *statically built* OpenSSL
 * that has no idea where Android keeps its CA store — cargo's crates.io
 * downloads fail with libcurl error 77 ("Problem with the SSL CA cert")
 * or 60 when the configured CAfile is missing, unreadable, or holds no
 * parseable certificate. The toolchain's vendored openssl-probe was
 * patched to probe $RUSTDROID_PREFIX/etc/{tls,ssl,etc} first, and cargo
 * also honors the CARGO_HTTP_CAINFO env override for http.cainfo.
 *
 * Trust sources, in order:
 *  1. The APK asset [ASSET_PATH] (a pinned Mozilla PEM bundle). This is
 *     the deterministic path: clean PEM with no device-specific quirks.
 *     Android's classic system store is empty or unreadable on several
 *     Android 14+/OEM builds (the roots moved into the Conscrypt APEX),
 *     which is exactly how dangling-cainfo / error-77 states happen.
 *  2. Android's system CA stores ([SYSTEM_SOURCES]), when readable and
 *     non-empty.
 *
 * The winning bundle is exposed through every trust channel the toolchain
 * knows about:
 *  - [ProcEnv] sets CARGO_HTTP_CAINFO / SSL_CERT_FILE / CURL_CA_BUNDLE
 *    to the bundle (cargo -> CURLOPT_CAINFO, the authoritative path),
 *  - a mirror is placed at $prefix/etc/tls/cert.pem so the patched
 *    openssl-probe finds it via RUSTDROID_PREFIX even without env vars,
 *  - SSL_CERT_DIR points at the system CApath as a no-bundle fallback.
 *
 * Every [ensure] call re-validates the on-disk bundle and self-heals
 * (rebuilds from the asset or the system store) when it is missing,
 * unreadable, or holds no certificate — a stale/dangling CAfile is
 * exactly what produces curl error 77 inside cargo.
 *
 * Pure JVM — unit-testable with injected source dirs and asset bytes.
 */
object CaBundle {

    /** APK asset path holding the pinned Mozilla PEM bundle. */
    const val ASSET_PATH = "ssl/cacert.pem"

    /** Android system CA stores, in preference order. */
    val SYSTEM_SOURCES: List<File> = listOf(
        File("/system/etc/security/cacerts"), // classic built-in store (hashed CApath)
        File("/apex/com.android.conscrypt/cacerts"), // Android 14+ Conscrypt APEX store
    )

    private const val BEGIN_MARKER = "-----BEGIN CERTIFICATE-----"
    private const val END_MARKER = "-----END CERTIFICATE-----"

    /** Canonical bundle: $filesDir/home/.ssl/cacert.pem */
    fun bundleFile(filesDir: File): File = File(ProcEnv.homeDir(filesDir), ".ssl/cacert.pem")

    /**
     * Mirror under the toolchain prefix: the patched openssl-probe checks
     * $RUSTDROID_PREFIX/etc/tls first and looks for a file named exactly
     * "cert.pem" (first entry of its candidate list).
     */
    fun probeFile(prefix: File): File = File(prefix, "etc/tls/cert.pem")

    /**
     * Reads [ASSET_PATH] from Android's asset manager. Kept as one tiny
     * helper so every caller shares a single path; returns null on any
     * error (JVM unit tests never invoke this).
     */
    fun readAssetPem(assets: android.content.res.AssetManager): ByteArray? =
        runCatching { assets.open(ASSET_PATH).use { it.readBytes() } }.getOrNull()

    /**
     * A bundle is usable only when it is a readable, non-empty file that
     * actually contains at least one complete PEM certificate block.
     * Guards against truncated writes, restored-backup files with broken
     * permissions, and empty stubs left behind by older app versions.
     */
    fun isUsable(file: File): Boolean {
        if (!file.isFile || file.length() == 0L || !file.canRead()) return false
        return runCatching {
            val text = file.readText()
            text.contains(BEGIN_MARKER) && text.contains(END_MARKER)
        }.getOrDefault(false)
    }

    private fun hasPem(content: ByteArray): Boolean {
        if (content.isEmpty()) return false
        val text = runCatching { String(content, Charsets.UTF_8) }.getOrNull() ?: return false
        return text.contains(BEGIN_MARKER) && text.contains(END_MARKER)
    }

    /**
     * Ensures a usable bundle exists and returns it, or null when neither
     * the APK asset nor any system store yields a certificate (callers
     * then rely on SSL_CERT_DIR alone). Idempotent; cheap stat fast-path
     * when the bundle is already present and valid. Self-heals an
     * existing-but-unusable bundle by rebuilding. Called from
     * [ProcEnv.env] on arbitrary threads, hence synchronized.
     */
    @Synchronized
    fun ensure(
        filesDir: File,
        prefix: File,
        sources: List<File> = SYSTEM_SOURCES,
        assetProvider: (() -> ByteArray?)? = null,
    ): File? {
        val target = bundleFile(filesDir)
        if (isUsable(target)) {
            syncProbeCopy(prefix, target)
            return target
        }
        // 1) pinned asset bundle — deterministic, survives any device quirk
        if (assetProvider != null) {
            val bytes = runCatching { assetProvider() }.getOrNull()
            if (bytes != null && hasPem(bytes) && writeBundle(target, bytes)) {
                syncProbeCopy(prefix, target)
                return target
            }
        }
        // 2) concatenate readable system stores (classic + APEX)
        val certs = StringBuilder()
        var count = 0
        for (src in sources) {
            val files = src.listFiles() ?: continue
            for (f in files.sortedBy { it.name }) {
                val pem = runCatching { f.readText() }.getOrNull() ?: continue
                if (!pem.contains(BEGIN_MARKER)) continue
                certs.append(pem)
                // repair a missing trailing newline so the next BEGIN
                // marker starts on its own line (PEM parsers require it)
                if (!pem.endsWith("\n")) certs.append('\n')
                count++
            }
        }
        if (count > 0 && writeBundle(target, certs.toString().toByteArray())) {
            syncProbeCopy(prefix, target)
            return target
        }
        return null
    }

    /**
     * Atomic, validated write. Returns true only when the file on disk is
     * readable afterwards; any IOException becomes "no bundle" instead of
     * crashing the env build inside ProcEnv's default argument.
     */
    private fun writeBundle(target: File, content: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        dev.rustdroid.ide.util.Fs.writeAtomic(target, String(content, Charsets.UTF_8))
        isUsable(target)
    }.getOrDefault(false)

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
