package dev.rustdroid.ide.toolchain

/**
 * The pinned toolchain distribution. Updated per toolchain release:
 * bump the tag, the SHA-256, and the expected version together.
 */
object ToolchainDistro {

    const val RUST_VERSION = "1.85.0"

    // Release tag and asset on github.com/majnoon40/RustDroid.
    // The app bundle is the ~117 MB subset the device actually needs
    // (rustc + cargo + rust-std + link kit + libc++_shared.so); the full
    // 1.2 GB dist zip is a separate asset on the same release.
    const val RELEASE_TAG = "toolchain-1.85.0-aarch64"
    const val ASSET_NAME = "rustdroid-app-bundle-aarch64.zip"

    val url: String =
        "https://github.com/majnoon40/RustDroid/releases/download/$RELEASE_TAG/$ASSET_NAME"

    /**
     * SHA-256 of the app bundle zip, recorded by the publish workflow
     * (also in the release's SHA256SUMS.txt + body table).
     * Empty string disables pinning (dev builds only).
     */
    const val SHA256 = "d7e50fe2a86dab08c7d8a5f55eeceaf28423d6f2536ad7852648299a93f46be9"

    val expectedSizeBytes: Long = 116_974_122L // exact; display only

    /** Zip entry names expected inside the bundle (layout contract). */
    val expectedEntries = listOf(
        "rustc-1.85.0-aarch64-linux-android.tar.xz",
        "cargo-1.85.0-aarch64-linux-android.tar.xz",
        "rust-std-1.85.0-aarch64-linux-android.tar.xz",
        "libc++_shared.so",
        "rustdroid-app-bundle.json",
    )

    const val MANIFEST_ENTRY = "rustdroid-app-bundle.json"

    val isPinned: Boolean get() = SHA256.length == 64
}
