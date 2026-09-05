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

The flip side: targetSdk 28 does NOT make shared storage executable.
`/storage/emulated/0` is mounted **noexec** for everyone — folders opened
in place can hold a `target/` directory but `cargo run` dies there with
`Permission denied (os error 13)`. The app therefore redirects build
output for external projects into app data (see "Folders as projects"
below); app data + targetSdk 28 is exec-allowed, shared storage never is.

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
`gesturesEnabled = drawerState.targetValue == Open` — gestures on only
while the drawer is open. The editor is an Android View behind
`AndroidView` interop, so it scrolls and long-press selects inside the
View system and never consumes pointer events in Compose's gesture
pipeline. With gestures on while CLOSED, M3's `anchoredDraggable`
watches the same pointer stream over the content area and (a) steals a
long-press once the finger drifts past touch slop — cancelling the
editor mid-hold, so text selection never fires — and (b) reads
scroll/fling motion as a horizontal drag, popping the drawer open while
scrolling long files (Compose-native scrollables consume their deltas
first, so only interop children are exposed). While OPEN the sheet covers
the editor — nothing interop sits under the gesture area — so a
right-to-left swipe (off the sheet or the scrim) closes the drawer, and
opening stays a deliberate act via the toolbar folder button so
code-scrolling can never summon it. The tree refreshes on every drawer
open and on editor re-entry, so externally-changed folders stay current.

New files: the drawer header has a `+` button that creates an empty file
at a project-relative path (`src/foo.rs`, `notes.md`), making parent
directories as needed. Path safety is `Fs.resolveChild` (no `..`, no
absolute paths), existing files are never overwritten, and the created
file opens in a tab immediately (covered by `FileCreateTest`).

Deleting files: long-press any drawer row and a confirm dialog offers to
delete it — a single file or a whole subtree when the row is a directory
("and everything inside it", spelled out). Guards mirror the create
side: `Fs.resolveChild` blocks traversal/escape, the project root is
refused, and the root `Cargo.toml` is protected with an actionable message
(a folder without a manifest stops being a project — delete the whole
project from Home instead; nested workspace manifests stay deletable as
the deliberate, advanced edit they are). Any open tab pointing into the
deleted path closes without a save prompt — saving would only resurrect
a stale copy — and the tree refreshes at once. Errors keep the dialog
open and show inline, exactly like `NewFileDialog` (covered by
`FileDeleteTest`). The long-press lives on the drawer's Compose-native
rows, so it never touches the sora-editor interop pointer stream (see
the gesture notes above).

Opening .rs files from outside the app: `ACTION_VIEW` intent filters
(`text/x-rust` MIME + `.*\.rs` pathPattern) make RustDroid appear in the
file manager's "Open with" sheet. A dialog then asks where the file goes:
**create a new project** (user-named, scaffolded from a template WITHOUT
the toolchain so editing works before install; Run needs the toolchain)
or **add it to an existing project** (internal or opened-in-place). The
name field defaults to `RsImport.suggestProjectName` (basename minus
`.rs`, whitespace folded to `-`, cargo charset, letter forced up front:
`2048.rs` → `rs2048`). Placed names are sanitized to their basename, the
charset is `[A-Za-z0-9._-]`, clashes get `-1`/`-2` suffixes, and `main.rs`
imports replace the template stub (covered by `RsImportTest`).

**Folders as projects (open in place, never a copy):** the Home toolbar's
folder-open button (also on the empty state) picks any folder on shared
storage — Download, anywhere — via `ACTION_OPEN_DOCUMENT_TREE`. The tree
URI is translated to the real path (`FolderLink` →
`ProjectRepository.documentIdToPath`: `primary:Download/x` →
`/storage/emulated/0/Download/x`, removable volumes too; cloud/USB picks
are rejected with a clear message) and checked readable+writable.
Folders without a `Cargo.toml` can be turned into a cargo project right
there (`ensureCargoProject` writes `Cargo.toml` + `src/main.rs` only when
missing — existing files are never overwritten) or opened for plain
editing. The folder is remembered in `files/external-projects.txt` (one
canonical path per line, pure JVM + `ExternalProjectsTest`), stays where
the user put it, and every edit — new files, saves, deletions — lands straight
in it. "Remove" on an external card forgets it
WITHOUT deleting anything (`ProjectRepository.delete` refuses registered
external folders as a safety net). External projects navigate by
absolute path (`Routes.editor` takes a ref: bare name or `/path`, both
resolve in `ProjectRepository.resolve`); editing-before-install works for
them too. Storage access rides the legacy model kept by targetSdk 28:
one `WRITE_EXTERNAL_STORAGE` runtime grant, requested only when the user
actually opens a folder.

**Running builds from external folders (the noexec problem):** shared
storage is mounted `noexec` — compiling into the folder works, but the
moment `cargo run` tries to spawn `target/debug/<bin>` the kernel answers
EACCES and cargo dies with `"could not execute process … Permission
denied (os error 13)"`. The fix is env-level, not file-level:
`ProcEnv.redirectedTargetDir` points `CARGO_TARGET_DIR` at
`files/build/<16-hex sha of the project's canonical path>` whenever the
project lives outside app data (internal projects keep their in-tree
`target/` untouched). Build output then sits on the same exec-allowed
ground the toolchain itself runs from, `cargo run`/`build`/`clean` work
unchanged, and the hash key means two same-named folders never share
build state. The console says so once per run ("this folder is on shared
storage (noexec) — binaries are built and run from app storage") so the
missing `target/` in the folder is never a surprise. Covered by
`ProcEnvTest` redirect cases.

Screen real estate: the file tab strip is a custom 30dp row (M3 `Tab`
enforces a 48dp minimum that ate a quarter of the screen on small
devices); the active tab gets a 2dp primary-color underline, and the
close button is a 22dp tap target inside the strip. With the keyboard open and the
editor focused, the console/problems panel hides entirely (the IME
already eats half the screen — a 220dp panel on top left a sliver of
code); it returns when the keyboard closes. Typing in the console's
stdin bar keeps the panel with the console shrunk to 88dp. Focus is
tracked from the sora-editor View (`setOnFocusChangeListener` bridged
through `AndroidView.update`), IME visibility via
`WindowInsets.isImeVisible`; `imePadding()` sits on the outer column —
with edge-to-edge, `adjustResize` in the manifest is neutralized
(decorFitsSystemWindows=false), so IME insets must be applied manually.

Settings "Re-verify health" returns to Home when the (minutes-long)
verification passes — driven by `ToolchainManager.verifyPassTick`, a
monotonic counter that survives StateFlow conflation. The naive "did I
see Verifying?" watcher missed the case where the user backgrounds the
app mid-verify: Compose recomposition pauses while stopped, every
intermediate `Verifying` emission is conflated away, and on return the
state jumps straight to `Ready` — so the transition was never observed.
Even the tick counter alone wasn't enough: the run used to live in
SettingsViewModel's `viewModelScope`, and a backgrounded process holding
no foreground service simply gets killed — the verify coroutine died
with it and the tick never incremented. The re-verify now runs inside
`ToolchainInstallService` (`ACTION_REVERIFY`), the same foreground
service installs use: the process (and the tick) survive backgrounding
until the run completes, and the notification mirrors progress. Failures
stay on Settings (and re-route to the Gate); the Settings-route pop is
guarded on the current destination.

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

Buildable from source, no proprietary SDKs. Permissions are the minimum
an on-device IDE needs: INTERNET (toolchain download + crates.io),
foreground-service + POST_NOTIFICATIONS (install/re-verify survive
backgrounding), WRITE_EXTERNAL_STORAGE (folders opened in place — one
legacy-model runtime grant, targetSdk 28). All dependencies are
Apache-2.0/LGPL-2.1/Public-Domain from Maven Central / Google Maven.
sora-editor is LGPL-2.1 (dynamic linking via Maven AAR is fine for
F-Droid; the license is shown in Settings → About).
