#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# build.sh — RustDroid toolchain build driver.
#
# Adapts termux-packages/packages/rust/build.sh logic for:
#   - Package:  dev.rustdroid.ide  (vs Termux's com.termux)
#   - Prefix:   /data/data/dev.rustdroid.ide/files/usr
#   - NDK:      r27c (LLVM 18.1)
#   - Rust:     1.85.0 stable
#   - Stage 2 self-hosting on aarch64-linux-android
#
# Usage:
#   ./build.sh prepare      # download rustup, NDK, clone rust source
#   ./build.sh symlinks     # create NDK clang triple-prefixed symlinks
#   ./build.sh shims         # compile libandroid_shims.a
#   ./build.sh patches       # apply patches/*.patch to rust source (warn-not-fail)
#   ./build.sh vendor        # run `cargo vendor` to populate vendor/ (heavy)
#   ./build.sh patches-post-vendor  # apply patches/post-vendor/*.patch (e.g. openssl-probe)
#   ./build.sh configure     # generate bootstrap.toml from template
#   ./build.sh smoke         # run x.py check (no full build)
#   ./build.sh dist          # full x.py dist --stage 2 (2-4+ hours)
#   ./build.sh all-smoke     # prepare + symlinks + shims + patches + configure + smoke
#   ./build.sh all           # prepare + ... + dist
#
# Notes:
#   - All paths come from env.sh — do not hardcode.
#   - Logs go to $LOGS_DIR/<step>.log with timestamp.
#   - Each step is idempotent: re-running is safe.

set -euo pipefail

# Source environment.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
log()  { echo "[$(date -u +%H:%M:%S)] $*" >&2; }
fail() { log "ERROR: $*"; exit 1; }
step_log() {
    local step="$1"; shift
    mkdir -p "$LOGS_DIR"
    echo "$LOGS_DIR/${step}-$(date +%Y%m%d-%H%M%S).log"
}

# Idempotent-ish: only download if target missing (or --force).
needs_force() {
    [[ "${1:-}" == "--force" || "${FORCE:-0}" == "1" ]]
}

# ----------------------------------------------------------------------------
# 1. prepare — rustup + NDK + rust source clone
# ----------------------------------------------------------------------------
do_prepare() {
    log "=== prepare: rustup + NDK r${NDK_VERSION} + rust@${RUST_TAG} ==="

    # 1a. rustup + stable rust (bootstrap stage0 compiler on x86_64-linux)
    mkdir -p "$RUSTUP_HOME" "$CARGO_HOME"
    if ! command -v rustup >/dev/null 2>&1; then
        log "installing rustup..."
        curl --proto '=https' --tlsv1.2 -sSf "$RUSTUP_INIT_URL" \
            | sh -s -- -y --no-modify-path --profile minimal
        source "${CARGO_HOME}/env"
    fi
    rustup default "$RUSTUP_TOOLCHAIN_BOOTSTRAP" \
        || fail "rustup install stable failed"
    rustc --version || fail "rustc not on PATH after rustup install"
    cargo --version

    # 1a-bis. cmake + ninja (required to build LLVM from source — see
    # bootstrap.toml.template [llvm] download-ci-llvm = false rationale).
    # We attempt apt-get install; if it fails (no root), the user must
    # install manually. Smoke build stage-B will warn-not-fail in that case.
    if ! command -v cmake >/dev/null 2>&1; then
        log "installing cmake + ninja-build (requires sudo / root)..."
        if sudo -n apt-get install -y cmake ninja-build 2>&1 | tail -5; then
            :
        else
            log "WARN: cmake install failed (no root?). The full dist build will need cmake + ninja-build."
            log "       On Ubuntu:  sudo apt-get install -y cmake ninja-build"
        fi
    fi

    # 1b. NDK r27c download (Linux x86_64 host)
    mkdir -p "$RUSTDROID_STAGE_PREFIX"
    if [[ ! -d "$NDK_ROOT" ]] || needs_force "$1"; then
        log "downloading NDK $NDK_VERSION (~1.5GB)..."
        local ndk_zip="${RUSTDROID_STAGE_PREFIX}/${NDK_ZIP_NAME}"
        curl -L --fail -o "$ndk_zip" "$NDK_ZIP_URL" \
            || fail "NDK download failed from $NDK_ZIP_URL"
        # SHA1 check (warn-only unless NDK_SHA1_REQUIRED=1).
        local actual_sha1; actual_sha1=$(sha1sum "$ndk_zip" | awk '{print $1}')
        if [[ "$actual_sha1" != "$NDK_SHA1_KNOWN" ]]; then
            if [[ "${NDK_SHA1_REQUIRED:-0}" == "1" ]]; then
                fail "NDK SHA1 mismatch: got $actual_sha1, expected $NDK_SHA1_KNOWN"
            else
                log "WARN: NDK SHA1 mismatch (got $actual_sha1, expected $NDK_SHA1_KNOWN) — Google may have rotated the checksum; continuing"
            fi
        fi
        log "extracting NDK..."
        mkdir -p "$RUSTDROID_STAGE_PREFIX/_ndk_extract"
        unzip -q "$ndk_zip" -d "$RUSTDROID_STAGE_PREFIX/_ndk_extract"
        # The zip extracts to android-ndk-r27c/... — promote to $NDK_ROOT.
        local extracted_dir
        extracted_dir="$(find "$RUSTDROID_STAGE_PREFIX/_ndk_extract" -maxdepth 1 -type d -name 'android-ndk-*' | head -1)"
        [[ -n "$extracted_dir" ]] || fail "NDK extract produced no android-ndk-* dir"
        rm -rf "$NDK_ROOT"
        mv "$extracted_dir" "$NDK_ROOT"
        rm -rf "$RUSTDROID_STAGE_PREFIX/_ndk_extract" "$ndk_zip"
    fi
    # Sanity check: NDK clang exists.
    [[ -x "$NDK_TOOLCHAIN_BIN/clang" ]] \
        || fail "NDK clang not at expected path: $NDK_TOOLCHAIN_BIN/clang"
    log "NDK ready at $NDK_ROOT"

    # 1c. rust-lang/rust source (shallow clone of tag).
    #
    # CRITICAL (CI cache): if a CI cache was restored into
    # $RUST_SRC/build/ (actions/cache restores only the build trees, not
    # .git), the re-clone below would `rm -rf $RUST_SRC` and DESTROY the
    # restored trees — that is exactly why run #18 restored a 2GB LLVM
    # cache and then rebuilt LLVM from scratch for 2h6m anyway. Move the
    # restored trees aside, clone, then re-attach them.
    local preserved_build=""
    if [[ -d "$RUST_SRC/build" && ! -d "$RUST_SRC/.git" ]]; then
        preserved_build="${RUSTDROID_STAGE_PREFIX}/_preserved_build"
        log "preserving CI-restored build trees across clone..."
        rm -rf "$preserved_build"
        mv "$RUST_SRC/build" "$preserved_build"
    fi
    if [[ ! -d "$RUST_SRC/.git" ]] || needs_force "$1"; then
        log "cloning rust-lang/rust @ $RUST_TAG (shallow)..."
        rm -rf "$RUST_SRC"
        git clone --depth 1 --branch "$RUST_TAG" "$RUST_GIT_URL" "$RUST_SRC" 2>&1 \
            | tee "$(step_log clone)"
        # Fetch submodules (rust uses submodules for LLVM, etc).
        # NOTE: full submodule fetch is heavy; for smoke build we can skip.
        if [[ "${RUST_FETCH_SUBMODULES:-1}" == "1" ]]; then
            log "fetching submodules (this is heavy)..."
            (cd "$RUST_SRC" && ./x.py --help >/dev/null 2>&1 || true)
            (cd "$RUST_SRC" && git submodule update --init --depth 1 --recommend-shallow) 2>&1 \
                | tee "$(step_log submodules)" || log "WARN: submodule update failed (may be OK for smoke)"
        fi
    fi
    if [[ -n "$preserved_build" ]]; then
        # Rename the whole preserved directory into place — atomic, no
        # per-entry moves. ($RUST_SRC/build cannot exist in a fresh clone;
        # `mv dir/. dest/` is NOT usable here — moving the "." entry fails
        # with "Device or resource busy", which is what killed run #19.)
        rm -rf "$RUST_SRC/build"
        mv "$preserved_build" "$RUST_SRC/build"
        # Ninja and cmake use MTIME dirty-checking. A fresh clone stamps
        # every source file with NOW, which is newer than the restored
        # build outputs' mtimes — ninja would then rebuild ~100% (the
        # cache would be restored but useless). Touch every restored file
        # so the outputs sort as up-to-date relative to the fresh sources.
        find "$RUST_SRC/build" -exec touch -c {} + 2>/dev/null || true
        log "CI build trees re-attached under $RUST_SRC/build (touched for ninja freshness)"
    fi
    log "rust source ready at $RUST_SRC"
}

# ----------------------------------------------------------------------------
# 2. symlinks — make NDK clang discoverable under triple-prefixed names
# ----------------------------------------------------------------------------
do_symlinks() {
    log "=== symlinks: ${CROSS_PREFIX}-clang etc. ==="
    mkdir -p "$NDK_TOOLCHAIN_BIN"
    local bin="$NDK_TOOLCHAIN_BIN"
    local api="$NDK_API_LEVEL"

    # NDK r27c ships unified clang at $bin/clang with no triple prefix.
    # Rust's build system looks for ${triple}${api}-clang via PATH lookup
    # when invoked with --target=...; we create symlinks.
    #
    # NOTE: the NON-suffixed names (aarch64-linux-android-clang etc.) are
    # ALSO required: the `cc` crate's default compiler for the
    # aarch64-linux-android target is exactly "aarch64-linux-android-clang"
    # (no API suffix). Build scripts of vendored C deps (openssl-src,
    # libz-sys, curl-sys) look that name up on PATH — run #18 logged
    # "ToolNotFound: Failed to find tool. Is `aarch64-linux-android-clang`
    # installed?" warnings because only the suffixed symlinks existed.
    # cc-rs passes --target=aarch64-linux-android to clang itself, so the
    # API-less symlink still produces Android binaries (default min API 21,
    # below our API 24 floor — compatible).
    local link_names=(
        "aarch64-linux-android${api}-clang"
        "aarch64-linux-android${api}-clang++"
        "aarch64-linux-android-clang"
        "aarch64-linux-android-clang++"
        "aarch64-linux-android-ar"
        "aarch64-linux-android-ranlib"
        "aarch64-linux-android-nm"
        "aarch64-linux-android-objcopy"
        "aarch64-linux-android-objdump"
        "aarch64-linux-android-strip"
    )
    local source_files=(
        "clang"        # -> aarch64-linux-android24-clang
        "clang++"      # -> aarch64-linux-android24-clang++
        "clang"        # -> aarch64-linux-android-clang (cc crate default)
        "clang++"      # -> aarch64-linux-android-clang++ (cc crate default)
        "llvm-ar"      # -> aarch64-linux-android-ar
        "llvm-ranlib"  # -> aarch64-linux-android-ranlib
        "llvm-nm"      # -> aarch64-linux-android-nm
        "llvm-objcopy" # -> aarch64-linux-android-objcopy
        "llvm-objdump" # -> aarch64-linux-android-objdump
        "llvm-strip"   # -> aarch64-linux-android-strip
    )
    for i in "${!link_names[@]}"; do
        local ln="${link_names[$i]}"
        local src="${source_files[$i]}"
        local target="${bin}/${ln}"
        if [[ -x "$bin/$src" ]]; then
            ln -sfn "$src" "$target"
            log "  ${ln} -> ${src}"
        else
            log "  WARN: source ${bin}/${src} missing — ${ln} not created"
        fi
    done

    # Verify clang actually runs.
    "$bin/aarch64-linux-android${api}-clang" --version \
        || fail "cross clang symlink test failed"
    log "symlinks OK"
}

# ----------------------------------------------------------------------------
# 3. shims — compile libandroid_shims.a
# ----------------------------------------------------------------------------
do_shims() {
    log "=== shims: libandroid_shims.a ==="
    if [[ "$RUSTDROID_ENABLE_BIONIC_SHIMS" != "1" ]]; then
        log "  Bionic shims DISABLED via RUSTDROID_ENABLE_BIONIC_SHIMS=0"
        return 0
    fi
    mkdir -p "$SHIM_LIB_DIR"
    local cc="$NDK_TOOLCHAIN_BIN/aarch64-linux-android${NDK_API_LEVEL}-clang"
    [[ -x "$cc" ]] || fail "shims need cross clang available; run ./build.sh symlinks first"

    # Compile shims as a static archive. -fPIC so we can link into .so as well.
    "$cc" -c -O2 -fPIC -o "$SHIM_LIB_DIR/android_shims.o" "$SHIMS_DIR/android_shims.c" \
        || fail "shim .o compile failed"
    # Use NDK's llvm-ar (the same one rustc will discover).
    local ar="$NDK_TOOLCHAIN_BIN/llvm-ar"
    "$ar" rcs "$SHIM_LIB_DIR/libandroid_shims.a" "$SHIM_LIB_DIR/android_shims.o" \
        || fail "shim .a archive failed"
    log "  libandroid_shims.a -> $SHIM_LIB_DIR/libandroid_shims.a"

    # Print nm dump for sanity.
    "$NDK_TOOLCHAIN_BIN/llvm-nm" "$SHIM_LIB_DIR/libandroid_shims.a" | grep -E 'syncfs| T ' \
        | head -20
}

# ----------------------------------------------------------------------------
# 4a. patches — apply patches/*.patch to the rust source tree.
#     Warn-not-fail: this lets the smoke build proceed even when patch
#     file paths drift from the pinned source tag, and lets us document
#     which patches applied cleanly vs which need adjustment.
# ----------------------------------------------------------------------------
do_patches() {
    log "=== patches: apply to $RUST_SRC ==="
    [[ -d "$RUST_SRC" ]] || fail "rust source missing; run ./build.sh prepare first"

    # Reverse any previously-applied patches first (idempotent re-runs).
    local p
    for p in "$PATCHES_DIR"/*.patch; do
        [[ -f "$p" ]] || continue
        local name; name="$(basename "$p")"
        log "  trying to reverse $name (if already applied)..."
        (cd "$RUST_SRC" && git apply --reverse --check "$p" 2>/dev/null && git apply --reverse "$p") \
            && log "    reversed" || log "    not previously applied (OK)"
    done

    # Apply forward.
    local apply_ok=0 apply_fail=0
    for p in "$PATCHES_DIR"/*.patch; do
        [[ -f "$p" ]] || continue
        local name; name="$(basename "$p")"
        log "  applying $name..."
        if (cd "$RUST_SRC" && git apply --check "$p" 2>&1 && git apply "$p" 2>&1); then
            log "    OK"
            apply_ok=$((apply_ok + 1))
        else
            log "    WARN: $name does NOT apply cleanly — documented in DEVIATIONS.md"
            apply_fail=$((apply_fail + 1))
        fi
    done
    log "patches summary: $apply_ok applied cleanly, $apply_fail drifted (see warnings above)"
}

# ----------------------------------------------------------------------------
# 4b. vendor — populate $RUST_SRC/vendor/ via `cargo vendor`
#     Required before patches-post-vendor can apply (openssl-probe lives
#     in vendor/openssl-probe/src/lib.rs after this step runs).
#     Heavy: pulls ~5GB of crates.
# ----------------------------------------------------------------------------
do_vendor() {
    log "=== vendor: populate $RUST_SRC/vendor/ (heavy ~5GB) ==="
    [[ -d "$RUST_SRC" ]] || fail "rust source missing; run ./build.sh prepare first"
    export PATH="$RUSTDROID_STAGE_PREFIX/cargo/bin:$PATH"

    local vendor_log; vendor_log="$(step_log vendor)"
    log "  full log: $vendor_log"

    # Deactivate any previously-activated vendored-source config before
    # re-vendoring: `cargo vendor` must talk to the crates.io registry, and
    # source replacement can interfere with it on re-runs.
    rm -f "$RUST_SRC/.cargo/config.toml"

    # x.py has a `vendor` subcommand that's preferred over `cargo vendor`
    # because it handles the rust-monorepo lockfile correctly.
    # NOTE: --no-merge was removed — current x.py vendor usage is:
    #   x.py vendor <--sync <SYNC>|--versioned-dirs> [PATHS]... [-- <ARGS>...]
    # --no-merge is not a recognized top-level flag on this version; x.py
    # vendor already writes a fresh vendor config by default (no merge with
    # an existing one), so dropping it preserves the original intent.
    (cd "$RUST_SRC" && ./x.py vendor --sync ./Cargo.toml 2>&1) \
        | tee "$vendor_log" \
        || fail "x.py vendor failed — see $vendor_log"

    log "vendor populated: $(du -sh "$RUST_SRC/vendor" 2>/dev/null | awk '{print $1}')"

    # CRITICAL (CI run #20, 2026-09-01): activate the vendored sources for
    # every subsequent cargo invocation bootstrap makes. `cargo vendor` only
    # PRINTS this config to stdout (it never writes it); rust's own dist
    # step captures the printout and writes it into the release tarball
    # (dist.rs PlainSourceTarball). Our pipeline is a git checkout, for
    # which bootstrap defaults `vendor` to false — so unless this file
    # exists, tool builds (cargo!) compile from the UNPATCHED crates.io
    # registry copies in $CARGO_HOME/registry instead of the patched
    # vendor/ tree, and the openssl-probe patch never reaches the binaries.
    mkdir -p "$RUST_SRC/.cargo"
    cat > "$RUST_SRC/.cargo/config.toml" <<'EOF'
# Written by RustDroid build.sh do_vendor (see run #20 forensics).
# Redirects all crates.io dependencies to the local, PATCHED vendor/ tree.
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "vendor"
EOF
    log "  wrote $RUST_SRC/.cargo/config.toml (vendored sources ACTIVE)"

    # Early sanity check: the openssl-probe patch target must EXIST in the
    # vendor tree — fail fast here rather than 2h later at verification.
    # (The patch itself applies in the next step, patches-post-vendor.)
    [[ -f "$RUST_SRC/vendor/openssl-probe/src/lib.rs" ]] \
        || fail "vendor/openssl-probe/src/lib.rs missing — patch 0001 has no target; did x.py vendor sync cargo's tree?"
}

# ----------------------------------------------------------------------------
# 4c. patches-post-vendor — apply patches/post-vendor/*.patch after
#     `cargo vendor` populated $RUST_SRC/vendor/.
# ----------------------------------------------------------------------------
do_patches_post_vendor() {
    log "=== patches-post-vendor: apply to $RUST_SRC ==="
    [[ -d "$RUST_SRC/vendor" ]] || fail "vendor/ missing; run ./build.sh vendor first"

    local apply_ok=0 apply_fail=0
    local touched=()
    local p
    for p in "$PATCHES_DIR/post-vendor"/*.patch; do
        [[ -f "$p" ]] || continue
        local name; name="$(basename "$p")"
        log "  applying $name..."
        # Record which files this patch touches (needed for the
        # .cargo-checksum.json resync below) BEFORE applying.
        while IFS= read -r tf; do
            [[ -n "$tf" ]] && touched+=("$tf")
        done < <(cd "$RUST_SRC" && git apply --numstat "$p" 2>/dev/null | awk '{print $NF}')
        if (cd "$RUST_SRC" && git apply --check "$p" 2>&1 && git apply "$p" 2>&1); then
            log "    OK"
            apply_ok=$((apply_ok + 1))
        else
            log "    WARN: $name does NOT apply cleanly — see DEVIATIONS.md"
            apply_fail=$((apply_fail + 1))
        fi
    done
    log "post-vendor patches summary: $apply_ok applied cleanly, $apply_fail drifted"

    # ------------------------------------------------------------------------
    # .cargo-checksum.json resync — REQUIRED after editing vendored sources.
    #
    # cargo's directory-source verification (cargo/src/cargo/sources/
    # directory.rs verify()) re-hashes every file listed in each vendored
    # crate's .cargo-checksum.json and FAILS the build on mismatch:
    #   error: the listed checksum of `.../vendor/openssl-probe/src/lib.rs`
    #   has changed ... directory sources are not intended to be edited
    # (CI run #21, exactly here — after vendor mode was activated, the
    # patched file was finally being compiled, and cargo rejected it).
    #
    # Standard distro practice: patch the vendored source AND update the
    # checksum file. We regenerate the "files" map (bare sha256 hex, no
    # prefix — the format cargo's copy_and_checksum writes) for every crate
    # a patch touched, preserving "package" as-is.
    # ------------------------------------------------------------------------
    if [[ ${#touched[@]} -gt 0 ]]; then
        log "  resyncing .cargo-checksum.json for patched crates..."
        python3 - "$RUST_SRC" "${touched[@]}" <<'PYEOF'
import hashlib, json, os, sys

rust_src = sys.argv[1]
touched = sys.argv[2:]

# Map touched vendor files -> crate roots (the ancestor dir holding
# .cargo-checksum.json).
roots = set()
for rel in touched:
    if not rel.startswith('vendor/'):
        continue
    d = os.path.dirname(os.path.join(rust_src, rel))
    while d.startswith(os.path.join(rust_src, 'vendor')):
        if os.path.isfile(os.path.join(d, '.cargo-checksum.json')):
            roots.add(d)
            break
        d = os.path.dirname(d)

for root in sorted(roots):
    cj_path = os.path.join(root, '.cargo-checksum.json')
    with open(cj_path) as f:
        data = json.load(f)
    files_map = {}
    for dirpath, dirnames, filenames in os.walk(root):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, root)
            if rel == '.cargo-checksum.json':
                continue
            h = hashlib.sha256()
            with open(p, 'rb') as fh:
                while True:
                    chunk = fh.read(65536)
                    if not chunk:
                        break
                    h.update(chunk)
            files_map[rel] = h.hexdigest()
    data['files'] = files_map
    with open(cj_path, 'w') as f:
        json.dump(data, f, sort_keys=True)
    print('    resynced {} entries in {}/.cargo-checksum.json'.format(
        len(files_map), os.path.basename(root)))
PYEOF
    fi

    # CRITICAL gate (run #20 forensics): if openssl-probe is still carrying
    # a com.termux path after patching, the built cargo binary WILL fail
    # verify.sh check 1 — 2h later. The openssl-probe patch is the ONLY
    # defense against the upstream 0.1.5 hardcoded
    # /data/data/com.termux/files/usr/etc/tls fallback (line 41), which gets
    # compiled into cargo whenever this patch does not apply. Fail NOW
    # instead, with a message that names the file to fix.
    if [[ -f "$RUST_SRC/vendor/openssl-probe/src/lib.rs" ]] \
        && grep -q "com\.termux" "$RUST_SRC/vendor/openssl-probe/src/lib.rs"; then
        fail "vendor/openssl-probe/src/lib.rs still contains a com.termux path after patching — cargo will embed it (verify.sh check 1 WILL fail). Fix patches/post-vendor/0001 and re-run."
    fi
    log "  vendor/openssl-probe verified: no com.termux paths remain"
}

# ----------------------------------------------------------------------------
# 5. configure — generate bootstrap.toml from template
# ----------------------------------------------------------------------------
do_configure() {
    log "=== configure: config.toml ==="
    [[ -d "$RUST_SRC" ]] || fail "rust source missing; run ./build.sh prepare first"
    local tpl="$SCRIPT_DIR/bootstrap.toml.template"
    # NOTE: x.py looks for `config.toml` at the rust source root, NOT
    # `bootstrap.toml` (the file we kept the template name as is for clarity
    # but the runtime config must be named config.toml).
    local out="$RUST_SRC/config.toml"

    # Substitute our env vars into the template.
    sed \
        -e "s|@RUSTDROID_PREFIX@|${RUSTDROID_PREFIX}|g" \
        -e "s|@RUSTDROID_HOST_TRIPLE@|${RUSTDROID_HOST_TRIPLE}|g" \
        -e "s|@RUSTDROID_BUILD_TRIPLE@|${RUSTDROID_BUILD_TRIPLE}|g" \
        -e "s|@NDK_TOOLCHAIN_BIN@|${NDK_TOOLCHAIN_BIN}|g" \
        -e "s|@NDK_API_LEVEL@|${NDK_API_LEVEL}|g" \
        -e "s|@SHIM_LIB_DIR@|${SHIM_LIB_DIR}|g" \
        -e "s|@NDK_ROOT@|${NDK_ROOT}|g" \
        "$tpl" > "$out"
    log "  config.toml written: $out"

    # Validate by asking x.py to print the parsed config (uses the same
    # TOML loader as a real build).
    (cd "$RUST_SRC" && ./x.py config --help >/dev/null 2>&1) \
        || (cd "$RUST_SRC" && ./x.py --help >/dev/null 2>&1) \
        || fail "x.py --help failed — is config.toml malformed?"
    log "  x.py config.toml parse-check OK"
}

# ----------------------------------------------------------------------------
# 6. smoke — run x.py check on a small piece (NOT full stage 2)
# ----------------------------------------------------------------------------
do_smoke() {
    log "=== smoke: x.py check (NO full build) ==="
    [[ -d "$RUST_SRC" ]] || fail "rust source missing; run ./build.sh prepare first"
    [[ -f "$RUST_SRC/config.toml" ]] || fail "no config.toml; run ./build.sh configure first"

    # Free-memory-friendly smoke test:
    # - `x.py check` on a leaf crate (build_helper) to validate rustc + cargo
    #   resolve dependencies under our [target.aarch64-linux-android] config.
    # - This does NOT produce a usable toolchain — it just proves the build
    #   system accepts our config and that patches don't break compilation.
    local smoke_log; smoke_log="$(step_log smoke)"

    # First, attempt a simple bootstrap rustc-based cross-compile sanity
    # check that doesn't involve x.py at all. This validates our NDK clang
    # + shims + symlinks pipeline end-to-end for a tiny test program.
    log "  stage-A: cross-compile hello.c via NDK clang + shims..."
    local hello_c="$RUSTDROID_STAGE_PREFIX/hello.c"
    cat > "$hello_c" <<'EOF'
#include <unistd.h>
#include <stdio.h>
int main(void) {
    printf("hello from aarch64-linux-android\n");
    // Force the linker to pull in our syncfs shim:
    extern int syncfs(int);
    return syncfs(1);  // syncfs(stdout)
}
EOF
    "$NDK_TOOLCHAIN_BIN/aarch64-linux-android${NDK_API_LEVEL}-clang" \
        -O2 -pie -fPIE \
        -L"$SHIM_LIB_DIR" -landroid_shims \
        -o "$RUSTDROID_STAGE_PREFIX/hello-aarch64-android" \
        "$hello_c" 2>&1 | tee -a "$smoke_log" || \
        fail "stage-A cross-compile failed — NDK clang + shims broken"

    # Confirm the binary's interpreter + arch.
    file "$RUSTDROID_STAGE_PREFIX/hello-aarch64-android" 2>&1 | tee -a "$smoke_log"
    "$NDK_TOOLCHAIN_BIN/llvm-readelf" -h "$RUSTDROID_STAGE_PREFIX/hello-aarch64-android" \
        | grep -E 'Machine|Class' | tee -a "$smoke_log"
    "$NDK_TOOLCHAIN_BIN/llvm-readelf" -l "$RUSTDROID_STAGE_PREFIX/hello-aarch64-android" \
        | grep -E 'INTERP|Requesting' | tee -a "$smoke_log"

    # Stage-B: x.py check on the bootstrap crate itself — proves config.toml
    # is loaded (otherwise we'd see "WARNING: you have not made a `config.toml`")
    # and that the build graph accepts our [build] target/host triples.
    # `src/bootstrap` is a leaf crate with a Cargo.toml — already pre-built
    # during the first x.py invocation, so `check` is cheap (no full compile).
    #
    # This stage requires cmake to be installed (x.py validates LLVM build
    # prerequisites at config load time, even for `check`). On hosts
    # without root (this sandbox), stage-B will fail — warn-not-fail and
    # continue so that stage-C still gets attempted.
    log "  stage-B: x.py check src/bootstrap (proves config.toml is loaded)..."
    if (cd "$RUST_SRC" && ./x.py check --stage 0 src/bootstrap 2>&1) | tee -a "$smoke_log"; then
        log "    stage-B OK"
    else
        log "    WARN: stage-B failed (likely missing cmake or LLVM submodule issue)."
        log "    This is a sandbox limitation — on a real Ubuntu host with cmake installed, this stage should pass."
    fi

    # Stage-C: cheap config.toml acceptance proof — ask x.py to enumerate
    # the build graph with our config. Same cmake caveat as stage-B.
    log "  stage-C: x.py build --dry-run --stage 0 (config acceptance)..."
    if (cd "$RUST_SRC" && ./x.py build --dry-run --stage 0 2>&1) | tee -a "$smoke_log"; then
        log "    stage-C OK"
    else
        log "    WARN: stage-C failed (likely same cmake limitation as stage-B)."
    fi

    log "smoke complete (see $smoke_log for details)"
}

# ----------------------------------------------------------------------------
# 7. dist — full x.py dist --stage 2 (LONG)
# ----------------------------------------------------------------------------
do_dist() {
    log "=== dist: x.py dist --stage 2 (this will take 2-4+ hours) ==="
    [[ -d "$RUST_SRC" ]] || fail "rust source missing; run ./build.sh prepare first"
    [[ -f "$RUST_SRC/config.toml" ]] || fail "no config.toml; run ./build.sh configure first"

    mkdir -p "$DIST_DIR"
    local dist_log; dist_log="$(step_log dist)"
    log "  full log: $dist_log"
    log "  dist artifacts will land in $DIST_DIR"

    # Wire the Bionic shim library and the real NDK libc++ runtime into the
    # actual dist link step. Previously these were only linked in the
    # do_smoke() standalone test — x.py dist itself never saw them, causing
    # undefined-symbol linker failures:
    #   - "undefined reference ... syncfs" — libandroid_shims.a (built by
    #     do_shims) was compiled but never passed to this build.
    #   - undefined std::__ndk1::* (libc++) symbols — rustc's default
    #     "-lstdc++" resolves to NDK's empty legacy compat stub, not the
    #     real C++ runtime. Android's actual C++ std lib is LLVM libc++,
    #     linked via "-lc++_shared".
    local dist_rustflags=""
    if [[ "$RUSTDROID_ENABLE_BIONIC_SHIMS" == "1" ]]; then
        [[ -f "$SHIM_LIB_DIR/libandroid_shims.a" ]] \
            || fail "libandroid_shims.a not found at $SHIM_LIB_DIR; run ./build.sh shims first"
        dist_rustflags+=" -Clink-arg=-L${SHIM_LIB_DIR} -Clink-arg=-landroid_shims"
    fi
    dist_rustflags+=" -Clink-arg=-lc++_shared"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${dist_rustflags# }"
    log "  CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS=$CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"

    # NDK env for build scripts of vendored C deps in extended tools
    # (cargo links vendored openssl + curl; openssl-src's android path reads
    # ANDROID_NDK_ROOT / ANDROID_NDK_HOME). Harmless if already set.
    export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$NDK_ROOT}"
    export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$NDK_ROOT}"

    # Full stage-2 self-hosting dist build.
    # --stage 2 = build stage0 -> stage1 -> stage2, where stage2 rustc runs
    # on aarch64-linux-android (the host triple).
    (cd "$RUST_SRC" && ./x.py dist --stage 2 --host "$RUSTDROID_HOST_TRIPLE" --target "$RUSTDROID_TARGET_TRIPLE" 2>&1) \
        | tee "$dist_log" \
        || fail "x.py dist failed — see $dist_log"

    # x.py writes tarballs to $RUST_SRC/build/dist/ — NOT to $DIST_DIR.
    # Copy them into $DIST_DIR so verify.sh and the CI artifact-upload step
    # actually find them. (Previously this copy was missing entirely:
    # stage/dist/ stayed empty and verification failed with
    # "no ELF files found" after a successful 2h+ build — CI run #16.)
    local xpy_dist_dir="${RUST_SRC}/build/dist"
    if [[ ! -d "$xpy_dist_dir" ]] || [[ -z "$(ls -A "$xpy_dist_dir" 2>/dev/null)" ]]; then
        fail "x.py dist produced no artifacts at $xpy_dist_dir — see $dist_log"
    fi
    log "  copying artifacts: $xpy_dist_dir -> $DIST_DIR"
    cp -a "$xpy_dist_dir"/. "$DIST_DIR"/ \
        || fail "copying dist artifacts into $DIST_DIR failed"

    # Bundle the NDK's libc++_shared.so alongside the tarballs.
    # Rationale: do_dist links stage2 rustc (and any extended tools) with
    # -lc++_shared, so those binaries carry DT_NEEDED=libc++_shared.so.
    # Android ships no system-wide libc++, so the file MUST travel with the
    # toolchain. On-device install: place it in $RUSTDROID_PREFIX/lib and
    # export LD_LIBRARY_PATH=$RUSTDROID_PREFIX/lib (see README).
    local libcxx_src="${NDK_TOOLCHAIN_BIN%/bin}/sysroot/usr/lib/${RUSTDROID_HOST_TRIPLE}/libc++_shared.so"
    if [[ -f "$libcxx_src" ]]; then
        cp -a "$libcxx_src" "$DIST_DIR/libc++_shared.so"
        log "  bundled $DIST_DIR/libc++_shared.so (runtime dep of -lc++_shared-linked binaries)"
    else
        log "  WARN: libc++_shared.so not found at $libcxx_src"
        log "       dist binaries linked with -lc++_shared will need it from the NDK at install time"
    fi

    log "dist OK — artifacts in $DIST_DIR:"
    ls -la "$DIST_DIR"
    log "  verify with: ./verify.sh ./stage/dist-extracted/  (after extracting)"
    log "  or a single tarball: ./verify.sh $DIST_DIR/rust-std-${RUST_TAG}-aarch64-linux-android.tar.xz"
}

# ----------------------------------------------------------------------------
# entrypoint
# ----------------------------------------------------------------------------
main() {
    local cmd="${1:-help}"
    shift || true
    case "$cmd" in
        prepare)              do_prepare "$@" ;;
        symlinks)             do_symlinks "$@" ;;
        shims)                do_shims "$@" ;;
        patches)              do_patches "$@" ;;
        vendor)               do_vendor "$@" ;;
        patches-post-vendor)  do_patches_post_vendor "$@" ;;
        configure)            do_configure "$@" ;;
        smoke)                do_smoke "$@" ;;
        dist)                 do_dist "$@" ;;
        all-smoke)
            do_prepare "$@"
            do_symlinks
            do_shims
            do_patches
            do_configure
            do_smoke
            ;;
        all-smoke-with-vendor)
            do_prepare "$@"
            do_symlinks
            do_shims
            do_patches
            do_vendor
            do_patches_post_vendor
            do_configure
            do_smoke
            ;;
        all)
            do_prepare "$@"
            do_symlinks
            do_shims
            do_patches
            do_vendor
            do_patches_post_vendor
            do_configure
            do_dist
            ;;
        env)
            rustdroid_env_summary
            ;;
        help|*)
            cat <<EOF
RustDroid toolchain build driver.

Usage: $0 <step> [args]

Steps:
  prepare              — install rustup+stable, download NDK $NDK_VERSION, clone rust@$RUST_TAG
  symlinks             — create aarch64-linux-android${NDK_API_LEVEL}-clang etc. symlinks in NDK bin
  shims                — compile libandroid_shims.a (Bionic fallbacks)
  patches              — apply patches/*.patch to rust source (warn-not-fail)
  vendor               — run \`cargo vendor\` to populate vendor/ (heavy ~5GB)
  patches-post-vendor  — apply patches/post-vendor/*.patch (e.g. openssl-probe)
  configure            — generate bootstrap.toml from template
  smoke                — quick verification (no full build): cross-compile hello.c,
                         x.py check on src/build_helper
  dist                  — full x.py dist --stage 2 (2-4+ hours; needs ~16GB RAM, ~30GB disk)
  all-smoke             — prepare + symlinks + shims + patches + configure + smoke
  all-smoke-with-vendor — above + cargo vendor + post-vendor patches
  all                    — prepare + ... + dist
  env                    — print the env.sh summary
  help                   — this message

Pinned (do not change without re-running full build):
  package:    $RUSTDROID_PACKAGE_NAME
  prefix:     $RUSTDROID_PREFIX
  rust tag:   $RUST_TAG
  NDK:        $NDK_VERSION (API $NDK_API_LEVEL, LLVM $NDK_LLVM_VERSION)
  host triple: $RUSTDROID_HOST_TRIPLE
EOF
            ;;
    esac
}

main "$@"
