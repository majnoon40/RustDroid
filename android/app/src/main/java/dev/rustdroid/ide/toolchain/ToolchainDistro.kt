package dev.rustdroid.ide.toolchain

/**
 * The pinned toolchain distribution. Updated per toolchain release:
 * bump the tag, the SHA-256, and the expected version together.
 */
object ToolchainDistro {

    const val RUST_VERSION = "1.85.0"

    // Release tag and asset on github.com/majnoon40/RustDroid.
    // The app bundle is the ~118 MB subset the device actually needs
    // (rustc + cargo + rust-std + link kit + libc++_shared.so + busybox);
    // the full dist zip is a separate asset on the same release.
    //
    // Phase 4 (plan §7.3): the tag gained the self-describing
    // "-bb1.36.1" suffix — the bundle now carries BusyBox 1.36.1
    // (manifest v2: busybox entry + guarded symlink list).
    const val RELEASE_TAG = "toolchain-1.85.0-aarch64-bb1.36.1"
    const val ASSET_NAME = "rustdroid-app-bundle-aarch64.zip"

    val url: String =
        "https://github.com/majnoon40/RustDroid/releases/download/$RELEASE_TAG/$ASSET_NAME"

    /**
     * SHA-256 of the app bundle zip, recorded by the publish workflow
     * (also in the release's SHA256SUMS.txt + body table).
     * Empty string disables pinning (dev builds only).
     *
     * NOTE (Phase 4 step 5): the busybox-carrying bundle is published by
     * the extended publish workflow; until that release exists this pin
     * intentionally blocks downloads of the OLD bundle (whose layout
     * this app version can no longer accept — busybox is required).
     * Re-pin here when publish-release.yml runs with the busybox
     * artifact. (Dev builds: set to "" to skip pinning.)
     */
    const val SHA256 = ""

    val expectedSizeBytes: Long = 118_500_000L // approximate; display only

    /** Manifest v2 (Phase 4): this app requires a busybox-carrying bundle. */
    const val BUSYBOX_BUNDLED = true

    /** The BusyBox pin the bundle must carry (plan §7.2). */
    const val BUSYBOX_VERSION = "1.36.1"

    /**
     * Free-space preflight: the extracted prefix (rustc + rust-std + cargo
     * + link kit) plus the zip still on disk during install, with headroom.
     * Estimate, not a hard number — the error message says "~", and a
     * false refusal is impossible in practice (the real expansion is well
     * under this; low-storage devices get a clear failure instead of a
     * mid-extraction ENOSPC that used to strand a partial prefix).
     */
    const val EXPECTED_INSTALLED_BYTES: Long = 1_500_000_000L

    /** Zip entry names expected inside the bundle (layout contract). */
    val expectedEntries = listOf(
        "rustc-1.85.0-aarch64-linux-android.tar.xz",
        "cargo-1.85.0-aarch64-linux-android.tar.xz",
        "rust-std-1.85.0-aarch64-linux-android.tar.xz",
        "libc++_shared.so",
        "busybox",
        "rustdroid-app-bundle.json",
    )

    const val MANIFEST_ENTRY = "rustdroid-app-bundle.json"

    val isPinned: Boolean get() = SHA256.length == 64
}
