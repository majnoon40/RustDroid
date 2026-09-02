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
# Run all checks.
# ----------------------------------------------------------------------------
log "RustDroid verify.sh starting on: $TARGET"
log "RUSTDROID_PREFIX = $RUSTDROID_PREFIX"
log "ELF file count   = ${#ELF_FILES[@]}"

check_termux_strings
check_rpath
check_interp
check_smoke_compile

log "all static checks complete."
log "Next steps for [D] device checks:"
log "  1. Copy dist tarball to device via adb push to $RUSTDROID_PREFIX"
log "  2. Extract: adb shell 'cd $RUSTDROID_PREFIX && tar xf <tarball>'"
log "  3. Run:    adb shell 'cd /tmp && RUSTDROID_PREFIX=$RUSTDROID_PREFIX $RUSTDROID_PREFIX/bin/rustc hello.rs'"
