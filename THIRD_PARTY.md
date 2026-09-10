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
| Termux `terminal-emulator` + `terminal-view` (vendored source, Apache-2.0 — see the vendored-source section below) | Apache-2.0 | `android/terminal-{emulator,view}` vendored @ `3b66f8799635a4dba4a206563048ff0e6792c487` | terminal emulation + view |
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
| BusyBox **1.36.1** (latest stable per busybox.net's news listing; v2 re-pin — review P1-6; shipped in the manifest-v2 bundle `toolchain-1.85.0-aarch64-bb1.36.1`) | **GPL-2.0-only** | busybox.net tarball, SHA-256 `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314` taken from busybox.net's own `busybox-1.36.1.tar.bz2.sha256` (fetched and re-verified), built statically by `busybox.yml` from `busybox/build.sh` + `busybox/busybox.config` + `busybox/patches/` (Termux-derived, retargeted `@TERMUX_PREFIX@` → `@RUSTDROID_PREFIX@`; patch provenance in `busybox/patches/README.md`) | separate executable (mere aggregation — legal interpretation, see plan §7.4); installed to `$PREFIX/bin/busybox` with manifest-v2 `sh`/`ash` symlinks; complete corresponding source published as a release asset (`busybox-1.36.1-src.zip`) alongside the bundle; in-app source offer at Settings → Licenses; network client/server applets compiled out (see README "Known limits"); `LICENSES/busybox-GPL-2.0.txt` |

## Vendored source in this repo

| Component | License | Upstream pin | Divergences |
|---|---|---|---|
| `terminal-emulator` | Apache-2.0 | termux/termux-app commit `3b66f8799635a4dba4a206563048ff0e6792c487` (origin: jackpal/Android-Terminal-Emulator) | see the file-by-file record below |
| `terminal-view` | Apache-2.0 (incl. AOSP-derived `support/PopupWindowCompatGingerbread.java`, header preserved) | same commit | see the file-by-file record below |

### File-by-file divergence record (vendored 2026-09-10, implementation step 1)

Vendored trees live at `android/terminal-emulator/` and
`android/terminal-view/`; the source trees are **copied verbatim** from
the pin (verified: `diff -r` against the checked-out pin is empty). At
step 1 (this record) the only divergences are build-script-level:

| File | Upstream @ 3b66f87 | Vendored | Divergence |
|---|---|---|---|
| `terminal-emulator/build.gradle` (Groovy) | present | **replaced** by `build.gradle.kts` (ours) | `testOptions.unitTests.isReturnDefaultValues = true`, cFlags, and dependency set carried verbatim (review P1-5); `compileOptions` 1.8→17; `compileSdk` 36→35, `minSdk` 21→24, `ndkVersion` → r27c (27.2.12479018); `abiFilters` stripped to `arm64-v8a` only; maven-publish + `sourceJar` dropped; `targetSdk` 28 kept (upstream's own value) |
| `terminal-emulator/proguard-rules.pro` | comment-only boilerplate | vendored as-is | none |
| `terminal-emulator/src/main/jni/Android.mk` | `LOCAL_MODULE:= libtermux` | `LOCAL_MODULE:= librustdroidpty` (plan §4.2) | library renamed (crash-report/maps disambiguation); JNI symbol names `Java_com_termux_terminal_*` and Java package `com.termux.terminal` unchanged — attribution-preserving |
| `terminal-emulator/src/main/jni/termux.c` | unchanged upstream since `438cd73` | RustDroid patch set (plan §4.3–§4.5), summarized in the file's own header comment | (1) `ReleaseStringUTFChars` pairing fixed (each buffer released against its source jstring); (2) master-fd closed on every failure path (fork/grantpt/unlockpt/ptsname_r/`GetPrimitiveArrayCritical`) via single-exit cleanup; (3) argv/envp early-return leaks fixed (single exit + zeroed arrays); (4) fork→exec window restructured async-signal-safe: fd pre-scan + executable resolution + envp marshalling pre-fork; child path `rd_child_exec` between `RD_CHILD_PATH` markers = sigprocmask(empty) → SIG_DFL reset 1..64 → close(ptm) → setsid → open(pts, no O_NOCTTY) → dup2 → close-list-only fd cleanup (**no close_range — deliberate, seccomp/SIGSYS**) → chdir → execve(abs, argv, envp) → _exit(127); (5) `sendSignalToProcessGroup(pgid, sig)` added (killpg); (6) `RD_FORK`/`RD_GRANTPT`/`RD_MALLOC` test seams (libc defaults in production); upstream's `__APPLE__` ptsname branch dropped (Android/Linux targets only) |
| `terminal-emulator/src/main/java/com/termux/terminal/JNI.java` | `System.loadLibrary("termux")` | `System.loadLibrary("rustdroidpty")` + `sendSignalToProcessGroup` native method + `ptsDevice` out-array parameter | library rename (plan §4.2), the RustDroid addition (plan §4.5), union-discovery device number out-param (plan §5.2) |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java` | — | passes `int[1] ptsDevice` to createSubprocess, stores `mPtsDevice`, exposes `getPtsDevice()`; reader restructured to poll {master, wakeup pipe} (plan §5.3/P1-4): `Os.poll` + `Os.read`, EIO = normal session end, teardown wake via `requestReaderStop()`, bounded `joinReader()`, master close owned by `closeMasterFd()` (idempotent, I/O-owner-after-join) and REMOVED from `cleanupResources`; wakeup pipe closed in cleanup | RustDroid additions (plan §5.2 + §5.3 review P1-4): union-discovery device number; reader lifecycle the controller drives — upstream closed the master from the main thread while the reader could still be watching the fd (fd-reuse use-after-close) and had no wakeup mechanism at all |
| `terminal-emulator/src/main/java/com/termux/terminal/JNIHelper.java` | — (new file, RustDroid addition) | public facade: `signal(pid,sig)` (Os.kill) + `sendSignalToGroup(pgid,sig)` (native killpg); both throw on failure so Kotlin `runCatching` guards them | the vendored `JNI` is package-private by design (kept); the Kotlin session layer needs a bridge (plan §4.5/§5.3) |
| `terminal-emulator/src/**` otherwise (14 Java main, 19 test files) | — | vendored as-is; ONE added test class `Utf8SplitAcrossReadsTest.java` (RustDroid addition, plan §6.3) | the 19 upstream test files untouched; our split-across-reads test joins the suite |
| `terminal-view/build.gradle` (Groovy) | present | **replaced** by `build.gradle.kts` (ours) | deps carried (`androidx.annotation`, `api :terminal-emulator`, JUnit); `unitTests.isReturnDefaultValues` added for symmetry (§4.6); `compileOptions` 1.8→17; `compileSdk` 36→35, `minSdk` 21→24, `ndkVersion` → r27c; `testInstrumentationRunner` (ancient `android.support.test` boilerplate, no instrumented tests exist) and maven-publish blocks dropped |
| `terminal-view/proguard-rules.pro` | comment-only boilerplate | vendored as-is | none |
| `terminal-view/src/**` (Java + res) | — | vendored as-is | none |

License texts: `LICENSES/terminal-{emulator,view}-Apache-2.0.txt`.

No NOTICE files exist upstream in either Termux module (verified at the
pinned commit `3b66f87` — recursive `find` over both module trees, and
re-verified `git ls-tree -r` at vendoring time); Apache-2.0 §4(d)
therefore imposes nothing beyond license-text preservation, which the
`LICENSES/` files carry.

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
