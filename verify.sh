#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# verify.sh — Static + runtime verification of the RustDroid dist tarball.
#
# Verifications (selected per user spec, 2026-08-31):
#   [S]  grep for "com.termux" in all ELF files     (bare minimum)
#   [S]  readelf -d: DT_RPATH / DT_RUNPATH entries (must point at $RUSTDROID_PREFIX)
#   [S]  readelf -l: PT_INTERP dynamic linker        (must be /system/bin/linker64)
#   [D]  smoke-compile: $PREFIX/bin/rustc hello.rs   (requires on-device Android)
#
# [S] = static (runs on any Linux host with readelf)
# [D] = dynamic (requires aarch64-linux-android execution environment)
#
# Usage:
#   ./verify.sh <dist-tarball-or-directory>            # run all [S] checks
#   ./verify.sh <dist-tarball-or-directory> --device    # also attempt [D] checks
#                                                        (will fail if not on Android)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

READELF="${READELF:-readelf}"
OBJDUMP="${OBJDUMP:-objdump}"
PATCHELF="${PATCHELF:-patchelf}"  # optional; only needed for --fix mode

TARGET="${1:-$DIST_DIR}"
RUN_DEVICE_CHECKS=0
[[ "${2:-}" == "--device" ]] && RUN_DEVICE_CHECKS=1

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
log()  { echo "[$(date -u +%H:%M:%S)] $*" >&2; }
pass() { echo "  PASS: $*"; }
warn() { echo "  WARN: $*"; }
fail() { echo "  FAIL: $*"; exit 1; }

# ----------------------------------------------------------------------------
# Locate artifacts to verify: extract tarball if needed.
# ----------------------------------------------------------------------------
WORK_DIR=""
cleanup() {
    # NOTE: must end in success. The EXIT trap's return status overrides the
    # script's exit code in bash — with a directory target WORK_DIR is empty,
    # the [[ ]] fails, and a previously-ALL-GREEN verification would still
    # exit 1 (which would fail the CI step and skip artifact upload).
    [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]] && rm -rf "$WORK_DIR"
    return 0
}
trap cleanup EXIT

if [[ -f "$TARGET" && "$TARGET" == *.tar.xz ]]; then
    WORK_DIR="$(mktemp -d -p "${TMPDIR:-/tmp} rustdroid-verify-XXXX")"
    log "extracting tarball $TARGET -> $WORK_DIR"
    tar -xf "$TARGET" -C "$WORK_DIR"
    TARGET_DIR="$WORK_DIR"
elif [[ -d "$TARGET" ]]; then
    TARGET_DIR="$TARGET"
    log "verifying directory $TARGET_DIR"
else
    fail "$TARGET is neither a .tar.xz nor a directory"
fi

# ----------------------------------------------------------------------------
# Build the list of ELF files to check.
# Skip the obvious junk (debug info, fonts, etc).
# ----------------------------------------------------------------------------
# Fast path: one python3 process reading 20 magic bytes per file. The old
# implementation spawned `file` once per file (the extracted rust-src tree
# alone has tens of thousands of files — CI run #20 spent ~24 minutes in
# this enumeration alone). ELF check: magic \x7fELF + e_machine == 0xB7
# (EM_AARCH64, little-endian) at offsets 18-19. The ELF class check is
# deliberately omitted: EM_AARCH64 only exists in 64-bit ELF.
# Fallback: legacy `file`-per-file loop if python3 is unavailable.
ELF_FILES=()
if command -v python3 >/dev/null 2>&1; then
    while IFS= read -r -d '' f; do
        ELF_FILES+=("$f")
    done < <(python3 - "$TARGET_DIR" <<'PYEOF'
import os, sys
root = sys.argv[1]
for dirpath, dirnames, filenames in os.walk(root):
    for fn in filenames:
        p = os.path.join(dirpath, fn)
        try:
            with open(p, 'rb') as fh:
                h = fh.read(20)
        except OSError:
            continue
        if (len(h) >= 20 and h[:4] == b'\x7fELF'
                and h[18] == 0xb7 and h[19] == 0):
            sys.stdout.write(p + '\0')
PYEOF
)
else
    while IFS= read -r -d '' f; do
        if file "$f" 2>/dev/null | grep -q 'ELF .*aarch64'; then
            ELF_FILES+=("$f")
        fi
    done < <(find "$TARGET_DIR" -type f -print0)
fi

log "found ${#ELF_FILES[@]} aarch64 ELF files to verify"
[[ ${#ELF_FILES[@]} -gt 0 ]] || fail "no ELF files found under $TARGET_DIR"

# ----------------------------------------------------------------------------
# Check 1: grep for "com.termux" strings in every ELF
# ----------------------------------------------------------------------------
check_termux_strings() {
    log "=== check 1: grep for com.termux / Termux hardcoded paths ==="
    local found=0
    local offending=()
    local f
    for f in "${ELF_FILES[@]}"; do
        # `strings -a` is portable; -n 8 filters noise.
        if strings -a -n 8 "$f" 2>/dev/null | grep -E 'com\.termux|/data/data/com\.termux' >/dev/null; then
            offending+=("$f")
            found=$((found + 1))
        fi
    done
    if [[ $found -eq 0 ]]; then
        pass "no 'com.termux' or '/data/data/com.termux' strings in any ELF"
    else
        # Print the offending files AND the matching strings BEFORE fail()
        # — fail() calls exit 1, so anything printed after it never appears
        # in CI logs (run #20 lost the file list exactly this way).
        echo "  FAIL: $found ELF files still contain Termux hardcoded paths:"
        local f2
        for f2 in "${offending[@]}"; do
            echo "  --> $f2"
            strings -a -n 8 "$f2" 2>/dev/null | grep -E 'com\.termux|/data/data/com\.termux' | head -5 | sed 's/^/        /'
        done | head -40
        fail "$found ELF files still contain Termux hardcoded paths (see list above)"
    fi

    # Also confirm our OWN prefix IS present where we'd expect (rustc, cargo).
    local rustc_bin="${TARGET_DIR}/bin/rustc"
    if [[ -x "$rustc_bin" ]]; then
        if strings -a -n 8 "$rustc_bin" 2>/dev/null | grep -q "$RUSTDROID_PREFIX"; then
            pass "rustc contains $RUSTDROID_PREFIX (expected — install path is baked)"
        else
            warn "rustc does NOT contain $RUSTDROID_PREFIX; check install prefix config"
        fi
    fi
}

# ----------------------------------------------------------------------------
# Check 2: DT_RPATH / DT_RUNPATH entries (must point at $RUSTDROID_PREFIX only)
# ----------------------------------------------------------------------------
check_rpath() {
    log "=== check 2: DT_RPATH / DT_RUNPATH entries ==="
    local f
    local bad=0
    for f in "${ELF_FILES[@]}"; do
        # Read dynamic section; look for RPATH/RUNPATH entries.
        local entries
        entries=$("$READELF" -d "$f" 2>/dev/null | grep -E 'RPATH|RUNPATH' || true)
        [[ -z "$entries" ]] && continue

        # Each entry looks like: 0x000000000000001d (RUNPATH)            Library runpath: [/foo/bar]
        local entry
        while IFS= read -r entry; do
            # Extract the path between [ and ].
            local path
            path=$(echo "$entry" | sed -n 's/.*\[\(.*\)\].*/\1/p')
            [[ -z "$path" ]] && continue
            # Allow entries that:
            #  - are exactly $RUSTDROID_PREFIX/lib
            #  - are under $RUSTDROID_PREFIX
            #  - are under $ORIGIN (cargo-relative, OK for self-relocating)
            #  - are absolute system paths under /system (Android linker search path)
            if [[ "$path" == "$RUSTDROID_PREFIX/lib" || \
                  "$path" == "$RUSTDROID_PREFIX/lib/" || \
                  "$path" == "$RUSTDROID_PREFIX/" || \
                  "$path" == "$RUSTDROID_PREFIX" || \
                  "$path" == \$ORIGIN || "$path" == \$ORIGIN/../lib || \
                  "$path" == \$ORIGIN/../lib/ || \
                  "$path" == /system/lib64 || \
                  "$path" == /system/lib ]]; then
                : # acceptable
            elif [[ "$path" == /data/data/com.termux/* || "$path" == /data/data/com.termux ]]; then
                echo "  FAIL: $f has RUNPATH pointing at Termux: $path"
                bad=$((bad + 1))
            elif [[ "$path" == "$RUSTDROID_PREFIX"/* || "$path" == "$RUSTDROID_PREFIX"* ]]; then
                : # acceptable (anything under our prefix)
            else
                warn "$f has unexpected RUNPATH: $path"
            fi
        done <<< "$entries"
    done
    if [[ $bad -eq 0 ]]; then
        pass "no DT_RPATH/DT_RUNPATH entries point at Termux"
    else
        fail "$bad bad RPATH/RUNPATH entries (see above)"
    fi
}

# ----------------------------------------------------------------------------
# Check 3: PT_INTERP dynamic linker (must be /system/bin/linker64, not glibc's)
# ----------------------------------------------------------------------------
check_interp() {
    log "=== check 3: PT_INTERP dynamic linker ==="
    local f
    local bad=0
    local seen=0
    for f in "${ELF_FILES[@]}"; do
        # Executable ELF files (DYN with PT_INTERP) only; shared libs don't have INTERP.
        # readelf -l puts the interpreter line 1-2 lines after `INTERP`,
        # formatted as: `      [Requesting program interpreter: /system/bin/linker64]`
        # Use awk to robustly extract the path between [ and ].
        local path
        path=$("$READELF" -l "$f" 2>/dev/null \
               | awk '/Requesting program interpreter:/ { gsub(/[\[\]]/, ""); print $NF }' \
               || true)
        [[ -z "$path" ]] && continue
        seen=$((seen + 1))

        case "$path" in
            /system/bin/linker64)
                pass "$f: PT_INTERP = $path (Android 64-bit)"
                ;;
            /system/bin/linker)
                pass "$f: PT_INTERP = $path (Android 32-bit)"
                ;;
            /lib/ld-linux-aarch64.so*|/lib64/ld-linux*|/lib/ld-linux*)
                echo "  FAIL: $f: PT_INTERP = $path (GLIBC — wrong for Android)"
                bad=$((bad + 1))
                ;;
            *)
                warn "$f: PT_INTERP = $path (unknown — investigate)"
                ;;
        esac
    done
    if [[ $seen -eq 0 ]]; then
        warn "no PT_INTERP entries found (all libs are shared objects, not PIEs — OK for lib check)"
    fi
    if [[ $bad -gt 0 ]]; then
        fail "$bad binaries have a glibc dynamic linker (won't run on Android)"
    fi
}

# ----------------------------------------------------------------------------
# Check 4: smoke-compile (requires on-device Android or qemu-aarch64-static)
# ----------------------------------------------------------------------------
check_smoke_compile() {
    log "=== check 4: smoke-compile $RUSTDROID_PREFIX/bin/rustc hello.rs ==="
    if [[ $RUN_DEVICE_CHECKS -ne 1 ]]; then
        warn "skipping smoke-compile (run with --device flag on an Android host)"
        warn "this requires the extracted toolchain to be in place at $RUSTDROID_PREFIX"
        warn "and PATH to include $RUSTDROID_PREFIX/bin — typically via adb shell"
        return 0
    fi

    local rustc_bin="${TARGET_DIR}/bin/rustc"
    [[ -x "$rustc_bin" ]] || rustc_bin="$RUSTDROID_PREFIX/bin/rustc"
    [[ -x "$rustc_bin" ]] || fail "no rustc binary found to smoke-test"

    local workdir; workdir="$(mktemp -d -p "${TMPDIR:-/tmp}" rustdroid-smoke-XXXX)"
    cat > "$workdir/hello.rs" <<'EOF'
fn main() {
    let msg = "hello from RustDroid rustc on aarch64-linux-android";
    println!("{msg}");
}
EOF

    log "  running: $rustc_bin $workdir/hello.rs -o $workdir/hello"
    if "$rustc_bin" "$workdir/hello.rs" -o "$workdir/hello" 2>&1; then
        if [[ -x "$workdir/hello" ]]; then
            log "  compiled OK; attempting run"
            if "$workdir/hello" 2>&1 | grep -q "hello from RustDroid"; then
                pass "smoke-compile OK — output matches expected string"
            else
                warn "smoke binary ran but output did not match expected string"
            fi
        else
            warn "rustc reported success but no output binary produced"
        fi
    else
        fail "smoke-compile failed — rustc did not produce a binary"
    fi

    rm -rf "$workdir"
}

# ----------------------------------------------------------------------------
# Check 5: RustDroid link kit (on-device linking support)
#
# Verified on-device (2026-09-02): rustc/cargo run and --emit=obj compiles,
# but linking fails with "linker 'cc' not found" — stock Android has no C
# linker driver, crt objects or link-time libc stubs. The dist must carry
# the link kit (crt objects + bionic stubs + cc/clang/gcc shims) so
# `rustc hello.rs -o hello` and `cargo run` work on-device.
# ----------------------------------------------------------------------------
check_link_kit() {
    log "=== check 5: RustDroid link kit (on-device linking) ==="
    local kit=""
    local cand
    for cand in "$TARGET_DIR/rustdroid-link" "$(dirname "$TARGET_DIR")/dist/rustdroid-link"; do
        if [[ -d "$cand" ]]; then
            kit="$cand"
            break
        fi
    done
    if [[ -z "$kit" ]]; then
        fail "rustdroid-link kit not found (expected loose in the dist dir next to the tarballs)"
    fi
    log "  kit found at $kit"

    local f
    for f in crtbegin_dynamic.o crtbegin_so.o crtend_android.o crtend_so.o; do
        if [[ ! -f "$kit/$f" ]]; then
            fail "link kit missing $f"
        fi
    done
    pass "crt objects present (crtbegin_dynamic/so, crtend_android/so)"

    if [[ ! -f "$kit/sysroot/libc.so" ]]; then
        fail "link kit missing sysroot/libc.so (bionic link stub)"
    fi
    for f in libm.so libdl.so; do
        if [[ ! -f "$kit/sysroot/$f" ]]; then
            warn "link kit missing sysroot/$f"
        fi
    done
    pass "bionic link stubs present ($(ls "$kit/sysroot" 2>/dev/null | wc -l) .so files)"

    for f in cc clang gcc; do
        if [[ ! -f "$kit/bin/$f" ]]; then
            fail "link kit missing bin/$f (linker driver shim)"
        fi
        if ! grep -q 'rust-lld' "$kit/bin/$f"; then
            fail "bin/$f does not reference rust-lld (corrupt shim?)"
        fi
        if ! grep -q 'gcc-ld/ld.lld' "$kit/bin/$f"; then
            fail "bin/$f does not use gcc-ld/ld.lld flavor dispatch (generic rust-lld refuses to link)"
        fi
        if ! grep -q 'rustdroid-link' "$kit/bin/$f"; then
            fail "bin/$f does not reference the link kit (corrupt shim?)"
        fi
        # -L kit paths must precede the -l resolution point (rustc links with
        # -nodefaultlibs; the driver must supply the search paths)
        if ! grep -q 'CMD="\$CMD -L \$KIT' "$kit/bin/$f"; then
            fail "bin/$f does not add kit -L paths before -l flags"
        fi
    done
    pass "cc/clang/gcc linker-driver shims present and sane"

    # libunwind.a is REQUIRED: rustc's android link line passes -lunwind and
    # nothing else provides those symbols (libc++_shared.so exports none).
    # Its absence caused the 2026-09-02 on-device failure "unable to find
    # library -lunwind" — the kit shipped without it because build.sh then
    # only WARNed when the file was not in the NDK sysroot (it actually
    # lives in the toolchain's clang runtime dir).
    if [[ ! -f "$kit/libunwind.a" ]]; then
        fail "link kit missing libunwind.a — on-device -lunwind would fail"
    fi
    if [[ -z "$(nm "$kit/libunwind.a" 2>/dev/null | grep '_Unwind_Resume')" ]]; then
        fail "kit libunwind.a contains no _Unwind symbols (wrong archive?)"
    fi
    pass "libunwind.a present with _Unwind symbols"

    if [[ ! -f "$kit/libclang_rt.builtins.a" ]]; then
        fail "link kit missing libclang_rt.builtins.a"
    fi
    pass "libclang_rt.builtins.a present"

    # crt objects must be AArch64 (guards against bundling host artifacts)
    local mach
    mach="$("$READELF" -h "$kit/crtbegin_dynamic.o" 2>/dev/null | awk '/Machine:/ {print $NF}')"
    if [[ "$mach" != "AArch64" ]]; then
        fail "crtbegin_dynamic.o Machine != AArch64 (got: ${mach:-none})"
    fi
    pass "crtbegin_dynamic.o is AArch64"

    # the shims exec ld.lld from the rustc component — make sure it exists
    # (gcc-ld/ld.lld preferred: generic rust-lld refuses to link)
    local lld="$TARGET_DIR/rustc-${RUST_TAG}-aarch64-linux-android/rustc/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld"
    if [[ ! -e "$lld" ]]; then
        lld="$(find "$TARGET_DIR" -path '*rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld' -print -quit 2>/dev/null)"
    fi
    if [[ ! -e "$lld" ]]; then
        lld="$(find "$TARGET_DIR" -path '*rustlib/aarch64-linux-android/bin/rust-lld' -print -quit 2>/dev/null)"
    fi
    if [[ -n "$lld" ]]; then
        if [[ -L "$lld" ]]; then
            warn "rust-lld at $lld is a symlink (may not survive Windows repacking)"
        fi
        pass "rust-lld present at ${lld#$TARGET_DIR/}"
    else
        warn "rust-lld not found in extracted tree — shim exec target untested"
    fi
}

# ----------------------------------------------------------------------------
# Check 6: on-host LINK smoke test — reproduce the on-device rustc link.
#
# Runs the DIST kit's cc shim + a rust 1.85.0 cross-compile on THIS host:
#   rustc --target aarch64-linux-android hello.rs (cc shim in PATH)
# The shim's lld exec target is swapped for the host-runnable ld.lld from
# the rustup toolchain (lld's library resolution is arch-independent; only
# the exec'ed binary differs). This is the exact failure mode chain caught
# on-device 2026-09-02 (generic-driver refusal, then -lunwind resolution)
# — either would have failed THIS check before shipping.
# Requires: rustup + network (installs 1.85.0 + aarch64 target, ~40 MB).
# Warn-skips when rustup/network unavailable (non-CI hosts).
# ----------------------------------------------------------------------------
check_link_smoke() {
    log "=== check 6: on-host rustc link smoke test (kit + shim) ==="
    if ! command -v rustup >/dev/null 2>&1; then
        warn "rustup not found — skipping on-host link smoke test"
        return 0
    fi
    local kit=""
    local cand
    for cand in "$TARGET_DIR/rustdroid-link" "$(dirname "$TARGET_DIR")/dist/rustdroid-link"; do
        [[ -d "$cand" ]] && { kit="$cand"; break; }
    done
    [[ -n "$kit" ]] || { warn "no kit dir — skipping link smoke test"; return 0; }

    local tc_bin=""
    tc_bin="$(rustup which --toolchain "$RUST_TAG" rustc 2>/dev/null)" || true
    if [[ -z "$tc_bin" || ! -x "$tc_bin" ]]; then
        log "  installing rustup toolchain $RUST_TAG (minimal) + aarch64 target..."
        if ! rustup toolchain install "$RUST_TAG" --profile minimal >/dev/null 2>&1; then
            warn "could not install rustup toolchain $RUST_TAG — skipping link smoke test"
            return 0
        fi
        tc_bin="$(rustup which --toolchain "$RUST_TAG" rustc 2>/dev/null)"
    fi
    if ! rustup target list --toolchain "$RUST_TAG" --installed 2>/dev/null | grep -q aarch64-linux-android; then
        rustup target add aarch64-linux-android --toolchain "$RUST_TAG" >/dev/null 2>&1 \
            || { warn "aarch64-linux-android target unavailable — skipping link smoke test"; return 0; }
    fi
    local sysroot
    sysroot="$("$tc_bin" --print sysroot)" || { warn "bad rustc — skipping"; return 0; }
    local host_bin="$sysroot/lib/rustlib/x86_64-unknown-linux-gnu/bin"
    # gcc-ld/ld.lld in the toolchain is a WRAPPER that re-execs sibling
    # ../rust-lld by relative path — both must be present in the fake prefix.
    [[ -f "$host_bin/gcc-ld/ld.lld" && -x "$host_bin/rust-lld" ]] \
        || { warn "no host ld.lld/rust-lld pair in rustup toolchain — skipping link smoke test"; return 0; }

    local fake bin_dir workdir
    workdir="$(mktemp -d -p "${TMPDIR:-/tmp}" rustdroid-linksmoke-XXXX)"
    fake="$workdir/prefix"; bin_dir="$fake/bin"
    mkdir -p "$bin_dir" "$fake/lib/rustlib/aarch64-linux-android/bin/gcc-ld"

    # kit copy + shim with a HOST-runnable shebang (device uses /system/bin/sh;
    # here we only validate translation + library resolution)
    cp -a "$kit" "$fake/lib/rustdroid-link"
    for f in cc clang gcc; do
        [[ -f "$fake/lib/rustdroid-link/bin/$f" ]] || continue
        sed '1s|.*|#!/bin/sh|' "$fake/lib/rustdroid-link/bin/$f" > "$bin_dir/$f"
        chmod 755 "$bin_dir/$f"
    done
    # host-runnable lld pair at the path the shim expects. The dist's own
    # aarch64 lld cannot run on an x86_64 host, and the host toolchain's
    # rust-lld resolves libLLVM.so via $ORIGIN-relative RUNPATH — so instead
    # of COPYING the binary (which breaks that resolution), place a stub
    # script that exec's the real one in its original location. Chain:
    # shim -> gcc-ld/ld.lld wrapper (copied, position-independent) ->
    # stub (fake sibling) -> real host rust-lld (libLLVM resolves in place).
    cp "$host_bin/gcc-ld/ld.lld" "$fake/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld"
    chmod 755 "$fake/lib/rustlib/aarch64-linux-android/bin/gcc-ld/ld.lld"
    cat > "$fake/lib/rustlib/aarch64-linux-android/bin/rust-lld" <<STUBEOF
#!/bin/sh
exec "$host_bin/rust-lld" "\$@"
STUBEOF
    chmod 755 "$fake/lib/rustlib/aarch64-linux-android/bin/rust-lld"
    # libc++_shared.so into prefix/lib (shim -L path; not needed to link hello)
    for cand in "$DIST_DIR/libc++_shared.so" "$(dirname "$TARGET_DIR")/dist/libc++_shared.so"; do
        if [[ -f "$cand" ]]; then cp "$cand" "$fake/lib/libc++_shared.so"; break; fi
    done

    cat > "$workdir/hello.rs" <<'EOF2'
fn main() {
    println!("hello from RustDroid");
}
EOF2
    log "  linking with $tc_bin --target aarch64-linux-android (shim in PATH)"
    if PATH="$bin_dir:$PATH" LD_LIBRARY_PATH="$fake/lib" \
       "$tc_bin" --target aarch64-linux-android "$workdir/hello.rs" -o "$workdir/hello" 2>"$workdir/err.log"; then
        local mach interp
        mach="$("$READELF" -h "$workdir/hello" 2>/dev/null | awk '/Machine:/ {print $NF}')"
        interp="$("$READELF" -lW "$workdir/hello" 2>/dev/null | grep -o 'Requesting program interpreter:.*' | awk '{print $NF}')"
        interp="${interp%\]}"   # readelf prints [Requesting program interpreter: /system/bin/linker64]
        [[ "$mach" == "AArch64" ]] || { fail "smoke link produced non-AArch64 output ($mach)"; }
        [[ "$interp" == "/system/bin/linker64" ]] || { fail "smoke link PT_INTERP is '$interp' (expected /system/bin/linker64)"; }
        pass "rustc cross-link via kit shim OK (AArch64 PIE, /system/bin/linker64)"
    else
        log "--- linker error output (tail) ---"
        tail -8 "$workdir/err.log" >&2 || true
        fail "on-host link smoke test FAILED — the kit would fail on-device the same way"
    fi
    rm -rf "$workdir"
}

# ----------------------------------------------------------------------------
# Run all checks.
# ----------------------------------------------------------------------------
log "RustDroid verify.sh starting on: $TARGET"
log "RUSTDROID_PREFIX = $RUSTDROID_PREFIX"
log "ELF file count   = ${#ELF_FILES[@]}"

check_termux_strings
check_rpath
check_interp
check_link_kit
check_link_smoke
check_smoke_compile

log "all static checks complete."
log "Next steps for [D] device checks:"
log "  1. Copy dist tarball to device via adb push to $RUSTDROID_PREFIX"
log "  2. Extract: adb shell 'cd $RUSTDROID_PREFIX && tar xf <tarball>'"
log "  3. Run:    adb shell 'cd /tmp && RUSTDROID_PREFIX=$RUSTDROID_PREFIX $RUSTDROID_PREFIX/bin/rustc hello.rs'"
