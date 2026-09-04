# RustDroid — Android app (Phase 2)

`dev.rustdroid.ide` — the IDE shell that bundles the Phase-1 toolchain and
exposes it through a usable UI: project list, code editor, console, problems
navigation, dependency management — all on-device, no PC, no root, no Termux.

## Build & install

Requirements: JDK 17+, Android SDK (platform 35, build-tools 34.0.0).

```bash
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

No local SDK? The **Android app** GitHub Actions workflow builds the debug
APK on every push touching `android/` (and on manual dispatch): grab
`rustdroid-debug-apk` from the run's artifacts page.

First launch downloads the toolchain bundle (~117 MB) from the project's
GitHub release and verifies it (12 checks, incl. compiling and running a test
program). Offline alternative: download the bundle on a PC, push it to the
phone, and use **Import zip** in the Gate screen.

## Why targetSdk 28

Executing binaries from app data (`/data/data/<pkg>/files/usr`) is blocked by
SELinux for `targetSdk >= 29`. Pinning 28 is the same strategy Termux uses;
the toolchain runs on Android 5–15. This pins the distribution channel to
F-Droid (Play requires target 34+) — which was the plan anyway. Only
permission: `INTERNET` (+ foreground service for the download).

## Architecture

Four layers, no DI framework (manual `AppContainer`):

| Layer | Package | Owns |
|---|---|---|
| UI | `ui/` | Compose screens + VMs: Gate, Home, Editor, Deps, Settings |
| Runtime | `runtime/` | subprocess contract: env assembly, streaming exec, diagnostics parsing, stdin, cancellation |
| Toolchain | `toolchain/` | install state machine: download (SHA-256 pinned), zip→tar.xz extraction with unix modes, 12-check verifier, foreground service |
| Projects | `projects/` | cargo project CRUD, Cargo.toml surgery, crates.io search |

Pure-JVM layers (`runtime`, `toolchain` minus the service, `projects`) carry
the unit tests: 30 JVM tests cover the diagnostics parser, TOML surgery,
ar-archive symbol reading, and a full synthetic-bundle extraction.

### The subprocess contract (runtime/ProcEnv)

The Phase-1-validated recipe, adjusted for the app sandbox:

```
HOME      = files/home            (.cargo registry/cache live here)
CARGO_HOME= files/home/.cargo     (+ config.toml: new.vcs = "none", http.cainfo)
PATH      = files/usr/bin:/system/bin
LD_LIBRARY_PATH = files/usr/lib   (libc++_shared.so)
TMPDIR    = files/home/tmp         (untrusted_app can't write /data/local/tmp)
CARGO_TERM_COLOR = never
```

**TLS trust (`runtime/CaBundle`).** cargo's libcurl is linked against a
statically built OpenSSL that knows nothing about Android's CA stores, so
crate downloads fail with libcurl error 77/60 without explicit trust. The
app ships a pinned Mozilla PEM bundle as an APK asset
(`src/main/assets/ssl/cacert.pem`) and materializes it (validated,
self-healing, asset-first — system stores are only a fallback) into
`files/home/.ssl/cacert.pem`, exposing it through the explicit CAfile
channels: `CARGO_HTTP_CAINFO` (cargo's `http.cainfo` override →
`CURLOPT_CAINFO`, also merged into `$CARGO_HOME/config.toml`), and
`SSL_CERT_FILE` + `CURL_CA_BUNDLE` (OpenSSL/libcurl defaults).
`SSL_CERT_DIR` is deliberately NOT exported: libcurl maps it to a hashed
`CApath`, and the real error-77 root cause turned out to be elsewhere
entirely — `openssl-src` compiles OpenSSL with `no-stdio` on Android, stubbing
`BIO_new_file()`, so no CA *file* could be loaded at all (fixed in the
toolchain by post-vendor patch 0003 + a curl-side fallback loader; see the
root README's Troubleshooting for the full history). A mirror lands at
`files/usr/etc/tls/cert.pem` so the toolchain's patched `openssl-probe`
finds it via `RUSTDROID_PREFIX` even without env vars. The app's own
crates.io search (OkHttp) uses the platform trust store and is unaffected.


**Every tool invocation uses the absolute binary path**
(`files/usr/bin/cargo`, never bare `cargo`): Android's JVM does not resolve
bare command names against the child env's `PATH` — `ProcessBuilder("cargo")`
with `PATH=files/usr/bin:…` fails with `error=2` (empirically verified).
Bare names only search the *JVM process's* PATH, which is `/system/bin` — no
cargo there. The bundled tools' own children (cargo → rustc → cc shim) resolve
through the child env's PATH normally (POSIX execvp), so only the Java exec
hop needed the fix.

rustc's default linker is `cc` — resolved to the kit's shim at
`files/usr/bin/cc`, which execs `files/usr/lib/rustdroid-link/…` chain:
`gcc-ld/ld.lld` → `rust-lld -flavor gnu` + crt objects + bionic stubs +
`libunwind.a` (see the repo root README, Phase 1).

### Toolchain verification (CI mirror)

`ToolchainVerifier` reproduces the CI `verify.sh` checks on-device: binaries
executable, crt objects present + AArch64 ELF, 18 bionic stubs, shims sane
(shebang + ld.lld reference), `libunwind.a` with `_Unwind_*` symbols (parsed
by a minimal ar-reader, no binutils), rust-lld pair present, libc++
present, `rustc --version`, `cargo --version`, and finally **compile+link+run
`hello.rs`** through the exact chain the IDE will use. The Gate screen shows
each check live; any failure blocks entry with an actionable message.

## Distro pinning

`toolchain/ToolchainDistro.kt` pins the release URL + SHA-256 of the app
bundle. Bump together with the release tag when a new toolchain ships.
`publish-release.yml` (repo root `.github/`) builds the bundle from a
completed CI run and records checksums in the release.

## Editor

sora-editor (`io.github.Rosemoe.sora-editor:editor` + `:language-textmate`,
LGPL-2.1) with the VS Code Rust TextMate grammar (MIT) and bundled
dark/light themes (`assets/textmate/`). Programmatic `setText` events are
filtered so tab loads don't mark files dirty; diagnostics tap-through jumps
via `setSelection(line, col, makeVisible)`.

The file drawer is a Material3 `ModalNavigationDrawer` with
`gesturesEnabled = false` — deliberate, not an oversight. The editor is an
Android View behind `AndroidView` interop, so it scrolls and long-press
selects inside the View system and never consumes pointer events in
Compose's gesture pipeline. With drawer gestures on, M3's
`anchoredDraggable` watches the same pointer stream over the content area
and (a) steals a long-press once the finger drifts past touch slop —
cancelling the editor mid-hold, so text selection never fires — and (b)
reads scroll/fling motion as a horizontal drag, popping the drawer open
while scrolling long files. Compose-native scrollables consume their
deltas first, so only interop children are exposed. The drawer opens via
the toolbar folder button; the scrim tap still closes it.

New files: the drawer header has a `+` button that creates an empty file
at a project-relative path (`src/foo.rs`, `notes.md`), making parent
directories as needed. Path safety is `Fs.resolveChild` (no `..`, no
absolute paths), existing files are never overwritten, and the created
file opens in a tab immediately (covered by `FileCreateTest`).

## Known v1 limits

- No PTY: stdin is a line-send field; programs needing raw tty interaction
  (vim-style) won't be usable.
- No C compilation: the cc shim is link-only (build scripts using the `cc`
  crate fail with a clear message) — real clang is later-phase work.
- No git in the bundle: `cargo new` runs with `--vcs none` (+ the same default
  is written to `$CARGO_HOME/config.toml`); anything expecting a git checkout
  (cargo install from a local path with git deps) is out of scope for v1.
- cargo builds run in-process (user-watched); backgrounding mid-build can
  be killed by the OS — incremental cache softens restarts.
- One build per project at a time (by design; `cargo` locks target/ anyway).

## F-Droid notes

Buildable from source, no proprietary SDKs, single INTERNET permission,
all dependencies Apache-2.0/LGPL-2.1/Public-Domain from Maven Central /
Google Maven. sora-editor is LGPL-2.1 (dynamic linking via Maven AAR is
fine for F-Droid; the license is shown in Settings → About).
