package dev.rustdroid.ide.toolchain

import java.io.File

/**
 * Single source of truth for on-disk toolchain layout — mirrors Phase 1's
 * $RUSTDROID_PREFIX conventions exactly (RPATH/RUNPATH in the toolchain
 * binaries expect this path; keep it byte-identical).
 */
class ToolchainPaths(val filesDir: File) {

    /** /data/data/dev.rustdroid.ide/files/usr — the Phase 1 prefix. */
    val prefix: File get() = File(filesDir, "usr")

    val bin: File get() = File(prefix, "bin")
    val lib: File get() = File(prefix, "lib")

    /** rustdroid-link kit, installed at $PREFIX/lib/rustdroid-link. */
    val kit: File get() = File(lib, "rustdroid-link")

    val rustc: File get() = File(bin, "rustc")
    val cargo: File get() = File(bin, "cargo")
    val ccShim: File get() = File(bin, "cc")

    /** $PREFIX/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld */
    val rustLld: File get() = File(
        lib, "rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld"
    )

    val libcxx: File get() = File(lib, "libc++_shared.so")

    /** Download/import staging area for the bundle zip. */
    val cacheDir: File get() = File(filesDir, "home/cache")

    val bundleZip: File get() = File(cacheDir, "rustdroid-app-bundle.zip")

    /** Scratch space for the smoke test. */
    val scratch: File get() = File(filesDir, "home/scratch")

    /** Marker: a fully verified install (survives app restarts). */
    val readyMarker: File get() = File(filesDir, "usr/.rustdroid-verified")

    fun ensureDirs() {
        bin.mkdirs()
        lib.mkdirs()
        cacheDir.mkdirs()
        scratch.mkdirs()
    }

    fun isInstalled(): Boolean = rustc.isFile && rustc.canExecute() &&
        cargo.isFile && cargo.canExecute() &&
        kit.isDirectory && libcxx.isFile
}
