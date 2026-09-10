# RustDroid BusyBox patches

Retargeted from termux-packages `packages/busybox/patches/` (@TERMUX_PREFIX@
→ @RUSTDROID_PREFIX@, the openssl-probe / patches/post-vendor/0001 pattern:
the token is substituted by build.sh AFTER patching).

Carried set (with 1.36.1 retargeting notes):

| Patch | Carried | Why / deviation from Termux |
|---|---|---|
| 0000-use-clang | **dropped** | our build.sh drives the NDK clang via make CC= — no Makefile patch needed |
| 0001-clang-fix | carried | clang compile fixes |
| 0002-hardcoded-paths-fix | carried, **telnetd.c section removed** | the core prefix reparenting; telnetd is disabled in our config (network servers off, §7.2) — its section targeted 1.38.0 context and would fail the FATAL apply for dead code |
| 0003-strchrnul-fix | carried | bionic API-level gating for strchrnul |
| 0004-no-change-identity | carried | Android uid semantics (change_identity) |
| 0005-miscutils-crond | carried | crond fix (applet kept — local, no network) |
| 0006-miscutils-crontab | carried | crontab fix (applet kept) |
| 0007-0009 (ftpd/httpd/tftp) | **dropped** | applets disabled (network servers off) |
| 0010-util-linux-mount-no-addmntent | carried | bionic lacks addmntent (mount kept) |
| 0011-kernel-6.8 (tc.c) | **dropped** | tc disabled (networking off) |
| 0012-fix-segfault | carried | BB_GLOBAL_CONST clang fix |
| 0013/0014-fix-ipv6 | carried | NOT applet features — bionic header compat: newer bionic (NDK r27c) defines `struct in6_ifreq` itself, clashing with busybox's local definition in ifconfig.c/interface.c — applets we KEEP (ifconfig/route are local admin, no DNS) |
| 0015-selinux | **dropped** | CONFIG_SELINUX=n (static build, no libandroid-selinux) |
| 0016-explicit_bzero | **dropped** | entire patch targets yescrypt/*, which only exists in busybox ≥ 1.37 — in 1.36.1 there is no explicit_bzero call site at all |

Patch application is **FATAL, never warn-and-continue** — the repo's
post-vendor lesson (plan §7.2). Context drift on upstream bumps is a
stop-and-fix event by design.
