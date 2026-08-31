#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# env.sh — RustDroid toolchain build environment (single source of truth).
#
# Sourced by build.sh, verify.sh, and any driver script.
# All pins are here. Do NOT duplicate version numbers elsewhere.
#
# Lock policy: every variable below was pinned by user clarification on
# 2026-08-31. Changing any of them requires re-running the full build
# AND re-running verify.sh. Do not edit in place during iteration —
# bump the value, commit, and rebuild.

set -o pipefail

# ----------------------------------------------------------------------------
# 1. Package identity (baked into RPATH / interpreter strings — DO NOT CHANGE
#    post-build without re-patching via patchelf on every produced binary).
# ----------------------------------------------------------------------------
export RUSTDROID_PACKAGE_NAME="${RUSTDROID_PACKAGE_NAME:-dev.rustdroid.ide}"

# On-device prefix. Everything the toolchain installs lives under this.
# This is the path that gets baked into binaries' RPATH/RUNPATH and that
# openssl-probe / cargo's lib search will fall back to at runtime.
export RUSTDROID_PREFIX="${RUSTDROID_PREFIX:-/data/data/${RUSTDROID_PACKAGE_NAME}/files/usr}"

# Build-host staging prefix (where the toolchain lands during build before
# being tarballed). Distinct from RUSTDROID_PREFIX so the host build dir
# doesn't accidentally get baked in. MUST equal RUSTDROID_PREFIX in the
# final tarball — build.sh enforces this by passing --prefix=$RUSTDROID_PREFIX
# to bootstrap.toml, while using a separate _STAGE dir for working files.
export RUSTDROID_STAGE_PREFIX="${RUSTDROID_STAGE_PREFIX:-/home/z/my-project/scripts/rustdroid-toolchain/stage}"

# ----------------------------------------------------------------------------
# 2. Pinned toolchain versions (user-locked 2026-08-31).
# ----------------------------------------------------------------------------
export RUST_TAG="${RUST_TAG:-1.85.0}"                 # rust-lang/rust stable tag
export RUST_STAGE0_TAG="${RUST_STAGE0_TAG:-1.84.0}"    # stage0.json expected bootstrap rustc (informational)
export NDK_VERSION="${NDK_VERSION:-r27c}"              # Android NDK
export NDK_API_LEVEL="${NDK_API_LEVEL:-24}"            # minimum Android API (Android 7.0+; matches Termux baseline)
export NDK_LLVM_VERSION="${NDK_LLVM_VERSION:-18.1}"    # informational; NDK r27c ships LLVM 18.1

# Bootstrap rustc: rustup stable (rust-lang recommends T-1 stable as stage0).
export RUSTUP_TOOLCHAIN_BOOTSTRAP="${RUSTUP_TOOLCHAIN_BOOTSTRAP:-stable-x86_64-unknown-linux-gnu}"

# ----------------------------------------------------------------------------
# 3. Host / target triples.
# ----------------------------------------------------------------------------
# Critical: aarch64-linux-android is a Tier 2 Rust target *without host tools*.
# We're building it as a self-hosting host (stage 2) — i.e. rustc that runs
# ON Android/arm64 as a process, not just a cross-compiler targeting Android.
export RUSTDROID_BUILD_TRIPLE="${RUSTDROID_BUILD_TRIPLE:-x86_64-unknown-linux-gnu}"
export RUSTDROID_HOST_TRIPLE="${RUSTDROID_HOST_TRIPLE:-aarch64-linux-android}"
export RUSTDROID_TARGET_TRIPLE="${RUSTDROID_TARGET_TRIPLE:-aarch64-linux-android}"  # same as host: self-hosting

# ----------------------------------------------------------------------------
# 4. Network resources (canonical URLs; mirrorable via env override).
# ----------------------------------------------------------------------------
export RUST_GIT_URL="${RUST_GIT_URL:-https://github.com/rust-lang/rust.git}"
export RUST_TARBALL_URL="${RUST_TARBALL_URL:-https://static.rust-lang.org/dist}"
export NDK_DOWNLOAD_URL_BASE="${NDK_DOWNLOAD_URL_BASE:-https://dl.google.com/android/repository}"
export RUSTUP_INIT_URL="${RUSTUP_INIT_URL:-https://sh.rustup.rs}"

# NDK r27c URL (Linux x86_64 host). Filename pattern:
# android-ndk-<version>-linux.zip
export NDK_ZIP_NAME="${NDK_ZIP_NAME:-android-ndk-${NDK_VERSION}-linux.zip}"
export NDK_ZIP_URL="${NDK_ZIP_URL:-${NDK_DOWNLOAD_URL_BASE}/${NDK_ZIP_NAME}}"
export NDK_SHA1_KNOWN="${NDK_SHA1_KNOWN:-f7c084ae91c80e57fbf9070d26c9c3fddc4c0b90}"
# ^ SHA1 is informational only — r27c's published checksum. verify.sh will
#   recompute and warn (not fail) if mismatch, since Google rotates these.
#   To pin hard, set NDK_SHA1_REQUIRED=1 and re-run.

# ----------------------------------------------------------------------------
# 5. Toolchain paths (computed).
# ----------------------------------------------------------------------------
export SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATCHES_DIR="${SCRIPT_DIR}/patches"
export SHIMS_DIR="${SCRIPT_DIR}/shims"
export LOGS_DIR="${SCRIPT_DIR}/logs"

# Build dirs (under stage prefix so a single rm -rf cleans everything).
export BUILD_ROOT="${BUILD_ROOT:-${RUSTDROID_STAGE_PREFIX}/build}"
export NDK_ROOT="${NDK_ROOT:-${RUSTDROID_STAGE_PREFIX}/ndk}"
export RUST_SRC="${RUST_SRC:-${RUSTDROID_STAGE_PREFIX}/rust-src}"
export RUSTUP_HOME="${RUSTUP_HOME:-${RUSTDROID_STAGE_PREFIX}/rustup}"
export CARGO_HOME="${CARGO_HOME:-${RUSTDROID_STAGE_PREFIX}/cargo}"
export DIST_DIR="${DIST_DIR:-${RUSTDROID_STAGE_PREFIX}/dist}"   # x.py dist output
export SHIM_LIB_DIR="${SHIM_LIB_DIR:-${RUSTDROID_STAGE_PREFIX}/shim-lib}"

# NDK toolchain bin path (this is where aarch64-linux-android24-clang lives
# once we create symlinks).
export NDK_TOOLCHAIN_BIN="${NDK_TOOLCHAIN_BIN:-${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin}"

# ----------------------------------------------------------------------------
# 6. Bionic shim toggle (syncfs stub, libexecinfo avoidance).
# ----------------------------------------------------------------------------
# Whether to build & link the Bionic shims static library.
# Default ON — the shims are a no-op on modern Bionic (API 21+) but
# harmless, and provide a safety net for older Android versions that
# RustDroid may eventually need to support.
export RUSTDROID_ENABLE_BIONIC_SHIMS="${RUSTDROID_ENABLE_BIONIC_SHIMS:-1}"

# ----------------------------------------------------------------------------
# 7. Cross-compile toolchain prefix used by Rust's build system.
# ----------------------------------------------------------------------------
# rustc looks for: ${target_triple}${api_level}-clang, -clang++, -ar, -ranlib
# We symlink these from NDK's unified clang at $NDK_TOOLCHAIN_BIN/clang.
export CROSS_PREFIX="aarch64-linux-android${NDK_API_LEVEL}"

# Convenience echo for debugging.
rustdroid_env_summary() {
    cat <<EOF
=== RustDroid toolchain env ===
Package:        $RUSTDROID_PACKAGE_NAME
Prefix:         $RUSTDROID_PREFIX
Stage prefix:  $RUSTDROID_STAGE_PREFIX
Rust tag:       $RUST_TAG (stage0 expected: $RUST_STAGE0_TAG)
NDK:            $NDK_VERSION (API $NDK_API_LEVEL, LLVM $NDK_LLVM_VERSION)
Build triple:   $RUSTDROID_BUILD_TRIPLE
Host triple:    $RUSTDROID_HOST_TRIPLE  (self-hosting)
Target triple:  $RUSTDROID_TARGET_TRIPLE
NDK bin:        $NDK_TOOLCHAIN_BIN
Cross prefix:   $CROSS_PREFIX  (-> ${CROSS_PREFIX}-clang etc.)
Bionic shims:   $([ "$RUSTDROID_ENABLE_BIONIC_SHIMS" = "1" ] && echo ENABLED || echo DISABLED)
EOF
}

# Allow `./env.sh` direct invocation for inspection.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    rustdroid_env_summary
fi
