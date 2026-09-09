# Third-party components, licenses, and attribution

RustDroid itself is MIT (repo root `LICENSE`). Everything below is
third-party material that ships with, or is consumed by, the project —
recorded so the licensing story stays auditable (F-Droid included).
The companion plan for the Phase 4 additions is
`docs/phase3-terminal-architecture.md` §3.

## Shipped in the app (APK)

| Component | License | Origin / pin | Where |
|---|---|---|---|
| sora-editor `editor` + `language-textmate` | LGPL-2.1 | Maven `io.github.Rosemoe.sora-editor:0.23.6` (dynamically linked AAR) | code editor |
| VS Code Rust TextMate grammar + themes | MIT | `assets/textmate/rust.tmLanguage.json` etc. (vendored from the vscode-rust grammar) | editor highlighting |
| Kotlin / Compose / AndroidX / kotlinx-coroutines / kotlinx-serialization | Apache-2.0 | Google Maven + Maven Central (see `android/gradle/libs.versions.toml`) | runtime |
| OkHttp | Apache-2.0 | Maven 4.12.0 | networking |
| commons-compress | Apache-2.0 | Maven 1.27.1 | bundle extraction |
| XZ for Java | Public Domain (0BSD) | Maven 1.10 | tar.xz extraction |
| tomlj | MIT | Maven 1.1.1 | Cargo.toml parsing |
| Mozilla CA root store | MPL-2.0 | `assets/ssl/cacert.pem` (from https://curl.se/ca/cacert.pem, periodically refreshed) | cargo TLS trust |

Test-only (not shipped): JUnit 4 (EPL-2.0).

## Shipped in the toolchain bundle (GitHub release → on-device `$PREFIX`)

| Component | License | Origin / pin | Notes |
|---|---|---|---|
| Rust (rustc, cargo, rust-std, rust-src) | MIT OR Apache-2.0 | rust-lang/rust tag `1.85.0` (commit `4d91de4e48198da2e33413efdcd9cd2cc0c46688`), built from source by CI | binaries + vendored crates per `build.sh` |
| Android NDK runtime pieces (`libc++_shared.so`, crt objects, `libunwind.a`, bionic stubs) | Apache-2.0 (LLVM variant for libc++) | NDK r27c | link kit |
| **Phase 4 (planned, not yet shipped):** BusyBox **1.36.1** (latest stable per busybox.net's news listing; v2 re-pin — review P1-6) | **GPL-2.0-only** | busybox.net tarball, SHA-256 `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314` taken from busybox.net's own `busybox-1.36.1.tar.bz2.sha256` (fetched and re-verified), built statically by CI from our config+patches | separate executable (mere aggregation — legal interpretation, see plan §7.4); complete corresponding source published as a release asset (`busybox-1.36.1-src.zip`); see `LICENSES/busybox-GPL-2.0.txt` (added at implementation) |

## Vendored source in this repo

| Component | License | Upstream pin | Divergences |
|---|---|---|---|
| **Phase 4 (planned, not yet vendored):** `terminal-emulator` | Apache-2.0 | termux/termux-app commit `3b66f8799635a4dba4a206563048ff0e6792c487` (origin: jackpal/Android-Terminal-Emulator) | build script replaced; JNI library renamed `libtermux` → `librustdroidpty`; `termux.c` patch set (3 defect fixes + async-signal-safe fork window + `sendSignalToProcessGroup`); enumerated file-by-file here at vendoring time |
| **Phase 4 (planned, not yet vendored):** `terminal-view` | Apache-2.0 (incl. AOSP-derived `support/PopupWindowCompatGingerbread.java`, header preserved) | same commit | build script replaced |

No NOTICE files exist upstream in either Termux module (verified at the
pinned commit `3b66f87` — recursive `find` over both module trees, this
session's post-review re-verification); Apache-2.0 §4(d) therefore
imposes nothing beyond license-text preservation, which
`LICENSES/terminal-{emulator,view}-Apache-2.0.txt` will carry (added at
vendoring time).

## Explicitly NOT used

| Component | License | Why not |
|---|---|---|
| `termux-shared` (Termux) | MIT overall; the **GPLv3-only** scope is `com/termux/shared/termux/*` *unless specifically overridden* — `TermuxConstants.java` and `TermuxPropertyConstants.java` are specifically **MIT** (the v1 wording "the subtree holds TermuxConstants" was imprecise; conclusion unchanged); also `com/termux/shared/file/filesystem/*` is GPLv2+Classpath-exception (ojluni-derived), `StreamGobbler.java` is Apache-2.0 (libsuperuser-derived) | excluded by design: hardcoded `/data/data/com.termux/...` assumptions live in the GPLv3-only subtree. Verified at the pin: neither vendored module references `termux-shared`/`TermuxConstants` (rg, zero matches); not in our Gradle graph. |
| Toybox | 0BSD | not used — shell quality decision (see plan §7.1): AOSP ships Toybox utilities but keeps mksh as its shell; toysh not production-proven |
| Termux prebuilt AAR/JitPack artifacts | mixed | prebuilt 4-ABI `.so` — F-Droid-hostile and unfixable; vendored source instead |

## Build-time only (not distributed)

- Android NDK r27c (Apache-2.0 + LLVM exceptions), Android SDK
- GitHub Actions runners, Gradle 8.10.2 (Apache-2.0)
- ccache (GPL-2.0-or-later, build cache only — never shipped, mere build tooling)
