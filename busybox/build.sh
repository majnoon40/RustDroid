#!/usr/bin/env bash
# RustDroid BusyBox build (plan §7.2).
#
# Inputs:  busybox-1.36.1.tar.bz2 (SHA-256-pinned, busybox.net),
#          busybox.config (ours), patches/ (Termux-derived, retargeted)
# Output:  busybox — a STATIC aarch64-linux-android binary.
#
# This script + the config + the patches + the pristine tarball are the
# "complete corresponding source" (GPL-2.0 §7.4 / plan §7.4): the release
# ships busybox-1.36.1-src.zip containing exactly these inputs.
#
# Patch application is FATAL, never warn-and-continue — the repo's
# post-vendor lesson (DEVIATIONS.md lineage, same as openssl-probe).
set -euo pipefail

BB_VERSION="1.36.1"
BB_SHA256="b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314"
BB_TARBALL="busybox-${BB_VERSION}.tar.bz2"
BB_URL="https://busybox.net/downloads/${BB_TARBALL}"

NDK_VERSION="27.2.12479018" # NDK r27c — same pin as the toolchain build (env.sh)
API_LEVEL="24"
TARGET="aarch64-linux-android"

RUSTDROID_PREFIX="${RUSTDROID_PREFIX:-/data/data/dev.rustdroid.ide/files/usr}"
OUT="${1:-busybox}"

say() { echo "[busybox-build] $*"; }
die() { echo "[busybox-build] FATAL: $*" >&2; exit 1; }

# ---- inputs ---------------------------------------------------------

if [ ! -f "$BB_TARBALL" ] && [ ! -d "busybox-${BB_VERSION}" ]; then
    say "downloading ${BB_URL}"
    curl -fsSL -o "$BB_TARBALL" "$BB_URL" || die "download failed"
fi

if [ -f "$BB_TARBALL" ]; then
    ACTUAL=$(sha256sum "$BB_TARBALL" | awk '{print $1}')
    if [ "$ACTUAL" != "$BB_SHA256" ]; then
        die "SHA-256 mismatch for ${BB_TARBALL}:
  expected ${BB_SHA256}
  actual   ${ACTUAL}
The pin is the pin — failing LOUD, never silently re-pinning
(the openssl-probe lesson, plan §7.2)."
    fi
    rm -rf "busybox-${BB_VERSION}"
    tar -xjf "$BB_TARBALL"
fi

SRC="busybox-${BB_VERSION}"
[ -d "$SRC" ] || die "source dir $SRC missing"

# ---- patches (FATAL on drift) ---------------------------------------

cd "$SRC"
for p in ../patches/*.patch; do
    [ -f "$p" ] || continue
    say "applying $(basename "$p")"
    patch -p1 --batch --forward < "$p" >/dev/null 2>&1 || {
        echo "---- patch failed: $(basename "$p") ----" >&2
        patch -p1 --batch --forward < "$p" || true # show the context error
        die "patch $(basename "$p") failed to apply — 1.36.1 context drift is a
stop-and-fix event, never warn-and-continue (plan §7.2)."
    }
done

# The patches carry @RUSTDROID_PREFIX@ tokens (the openssl-probe pattern,
# patches/post-vendor/0001 lineage): substitute AFTER patching.
grep -rl '@RUSTDROID_PREFIX@' . --include='*.c' --include='*.h' --include='Makefile' 2>/dev/null \
    | while read -r f; do
        sed -i "s|@RUSTDROID_PREFIX@|${RUSTDROID_PREFIX}|g" "$f"
      done
say "prefix baked: ${RUSTDROID_PREFIX}"

# ---- configure ------------------------------------------------------

CONFIG_IN="$(mktemp)"
sed "s|@RUSTDROID_PREFIX@|${RUSTDROID_PREFIX}|g" ../busybox.config > "$CONFIG_IN"
cp "$CONFIG_IN" .config
# Termux's template is 1.38.0-flavored; oldconfig resolves symbol drift
# with defaults for 1.36.1 (new-in-1.38 symbols drop out). busybox 1.36's
# kconfig has no olddefconfig, so we feed defaults via `yes ''; the
# subshell locally disables pipefail because `yes` legitimately dies of
# SIGPIPE when oldconfig finishes.
( set +o pipefail; yes '' | make oldconfig >/dev/null ) \
    || die "make oldconfig failed"

# The shell + static pins are LOAD-BEARING (plan §7.2): assert them post-
# oldconfig so silent kconfig drift can never ship a broken shell.
check_on() {
    grep -q "^$1=y" .config || die "config drift: $1 is not set (plan §7.2 pin)"
}
check_on CONFIG_STATIC
check_on CONFIG_SH_IS_ASH
check_on CONFIG_ASH_JOB_CONTROL
check_on CONFIG_ASH_INTERNAL_GLOB
check_on CONFIG_FEATURE_EDITING
check_on CONFIG_FEATURE_TAB_COMPLETION
check_on CONFIG_VI
grep -q '^CONFIG_FEATURE_EDITING_HISTORY=' .config \
    || die "config drift: FEATURE_EDITING_HISTORY missing"
grep -q '^CONFIG_PREFIX="'"$RUSTDROID_PREFIX"'"' .config \
    || die "config drift: PREFIX wrong"

# Condition 9 / plan §7.2: network CLIENT and SERVER applets stay OFF —
# in BOTH directions. (Static bionic loses the dynamic NSS/DNS path, so
# a shipped network client is broken-by-design: "wget is broken" reports
# are worse than no wget. Dropped: wget, telnet/telnetd, ftpget/ftpput/
# ftpd, tftp/tftpd, httpd, nslookup, ping/ping6, traceroute, nc/netcat,
# ntpd, rdate, arping, udhcp*, inetd, dnsd. KEPT — local admin applets
# that open no sockets and resolve no names: ifconfig, route, ip,
# netstat, arp. The negative assertion survives symbol renames: it fails
# on the `=y` line itself, whether kconfig kept the symbol or dropped it
# as unknown.)
check_off() {
    ! grep -q "^$1=y" .config || die "config drift: $1 is ENABLED (plan §7.2 network drop)"
}
for netapp in \
    CONFIG_WGET CONFIG_TELNET CONFIG_TELNETD \
    CONFIG_FTPGET CONFIG_FTPPUT CONFIG_FTPD CONFIG_HTTPD \
    CONFIG_TFTP CONFIG_TFTPD CONFIG_FEATURE_TFTP_GET CONFIG_FEATURE_TFTP_PUT \
    CONFIG_NSLOOKUP CONFIG_PING CONFIG_PING6 \
    CONFIG_TRACEROUTE CONFIG_TRACEROUTE6 \
    CONFIG_NC CONFIG_NETCAT CONFIG_NC_SERVER \
    CONFIG_NTPD CONFIG_FEATURE_NTPD_SERVER CONFIG_RDATE \
    CONFIG_ARPING CONFIG_UDHCPC CONFIG_UDHCPD \
    CONFIG_INETD CONFIG_DNSD CONFIG_TCPSVD CONFIG_UDPSVD \
    CONFIG_WHOIS CONFIG_SENDMAIL CONFIG_POPMAILDIR \
    CONFIG_FEATURE_CROND_CALL_SENDMAIL; do
    check_off "$netapp"
done

# ---- build (NDK r27c clang) -----------------------------------------

NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/${NDK_VERSION}}"
TOOLCHAIN="${NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "${TOOLCHAIN}/clang" ] || die "NDK r27c not found at ${NDK_HOME} (expected ${TOOLCHAIN}/clang)"

CC="${TOOLCHAIN}/${TARGET}${API_LEVEL}-clang"
[ -x "$CC" ] || die "target wrapper $CC missing in NDK"
CFLAGS="-Os -fno-strict-aliasing -Wno-ignored-optimization-argument -Wno-unused-command-line-argument"

say "building with $CC"
BUILD_LOG="../busybox-build.log"
if ! make -j"$(nproc)" \
    ARCH=64 \
    CC="$CC" \
    AR="${TOOLCHAIN}/llvm-ar" \
    RANLIB="${TOOLCHAIN}/llvm-ranlib" \
    STRIP="${TOOLCHAIN}/llvm-strip" \
    EXTRA_CFLAGS="$CFLAGS" \
    >"$BUILD_LOG" 2>&1; then
    tail -60 "$BUILD_LOG" >&2
    die "make failed (log: $BUILD_LOG)"
fi

tail -5 "$BUILD_LOG" >&2 || true

BUILT="busybox_unstripped"
[ -f "$BUILT" ] || die "build produced no busybox_unstripped"

# ---- verify (fail loud, plan §6.5 shape; CI repeats this) -----------

READELF="${TOOLCHAIN}/llvm-readelf"
ELF_ARCH=$("$READELF" -h "$BUILT" | awk '/Machine:/{print $2}')
[ "$ELF_ARCH" = "AArch64" ] || die "not an AArch64 ELF (Machine: $ELF_ARCH)"
if "$READELF" -l "$BUILT" | grep -q INTERP; then
    die "PT_INTERP present — not a static binary (CONFIG_STATIC failed)"
fi
if "$READELF" -d "$BUILT" 2>/dev/null | grep -q NEEDED; then
    die "DT_NEEDED present — static binary must depend on nothing"
fi

# applet smoke (host cannot run an aarch64 binary): the applet names the
# terminal needs must be present in the binary's strings. NOTE: default
# strings minimum length is 4 — "sh"/"ls"/"vi" would never print — so -n 2.
for applet in sh ash vi ls env; do
    grep -qx "$applet" <("${TOOLCHAIN}/llvm-strings" -n 2 "$BUILT") \
        || die "applet '$applet' missing from the binary"
done

# ---- ship ------------------------------------------------------------

"${TOOLCHAIN}/llvm-strip" -s "$BUILT" -o "../${OUT}"
cd ..
say "built ${OUT}: $(stat -c%s "${OUT}") bytes, static AArch64, no PT_INTERP"
sha256sum "${OUT}"
