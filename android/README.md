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

First launch downloads the toolchain bundle (~117 MB) from the project's
GitHub release and verifies it (10 checks, incl. compiling and running a test
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
| Toolchain | `toolchain/` | install state machine: download (SHA-256 pinned), zip→tar.xz extraction with unix modes, 10-check verifier, foreground service |
| Projects | `projects/` | cargo project CRUD, Cargo.toml surgery, crates.io search |

Pure-JVM layers (`runtime`, `toolchain` minus the service, `projects`) carry
the unit tests: 30 JVM tests cover the diagnostics parser, TOML surgery,
ar-archive symbol reading, and a full synthetic-bundle extraction.

### The subprocess contract (runtime/ProcEnv)

The Phase-1-validated recipe, adjusted for the app sandbox:

```
HOME      = files/home            (.cargo registry/cache live here)
CARGO_HOME= files/home/.cargo
PATH      = files/usr/bin:/system/bin
LD_LIBRARY_PATH = files/usr/lib   (libc++_shared.so)
TMPDIR    = files/home/tmp         (untrusted_app can't write /data/local/tmp)
CARGO_TERM_COLOR = never
```

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

## Known v1 limits

- No PTY: stdin is a line-send field; programs needing raw tty interaction
  (vim-style) won't be usable.
- No C compilation: the cc shim is link-only (build scripts using the `cc`
  crate fail with a clear message) — real clang is later-phase work.
- cargo builds run in-process (user-watched); backgrounding mid-build can
  be killed by the OS — incremental cache softens restarts.
- One build per project at a time (by design; `cargo` locks target/ anyway).

## F-Droid notes

Buildable from source, no proprietary SDKs, single INTERNET permission,
all dependencies Apache-2.0/LGPL-2.1/Public-Domain from Maven Central /
Google Maven. sora-editor is LGPL-2.1 (dynamic linking via Maven AAR is
fine for F-Droid; the license is shown in Settings → About).
