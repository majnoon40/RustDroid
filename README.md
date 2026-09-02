# RustDroid

A **Pydroid-style Rust IDE for Android** — develop, compile, and run Rust programs entirely on-device, with no PC, no Termux, and no root.

This repository currently contains **Phase 1**: the foundation everything else depends on — a **self-hosting Rust toolchain for `aarch64-linux-android`**, built by CI and installable under an app-owned prefix:

- `rustc` + `cargo` that **execute on Android** (Bionic libc, `/system/bin/linker64`)
- reparented to `/data/data/dev.rustdroid.ide/files/usr` — a prefix the future IDE app fully controls
- zero Termux filesystem assumptions, zero Termux code or branding

## Project phases

| Phase | Scope | Status |
|-------|-------|--------|
| **1 — Toolchain** (this repo) | Self-hosting rustc + cargo for `aarch64-linux-android`, built via GitHub Actions, verified statically + on-device | **In progress** — full pipeline works; CI fix chain under test (run #22+) |
| 2 — App shell | Android app (`dev.rustdroid.ide`) that downloads/bundles the toolchain, provides terminal + editor | Planned |
| 3 — IDE experience | Code editor with Rust syntax/racer, project templates, build output panel, `cargo run` in PTY | Planned |

Phase 1 is deliberately the highest-risk piece: if a Bionic-hosted `rustc`+`cargo` cannot run standalone in an app sandbox, nothing downstream matters. Everything else is "just" Android app engineering.

## Pinned versions (DO NOT CHANGE without re-running the full build)

| Thing           | Value                                                    |
|-----------------|----------------------------------------------------------|
| Package name    | `dev.rustdroid.ide`                                       |
| On-device prefix| `/data/data/dev.rustdroid.ide/files/usr`                  |
| Rust tag        | `1.85.0` (commit `4d91de4e48198da2e33413efdcd9cd2cc0c46688`) |
| Stage0          | `1.84.0` (auto-downloaded by x.py)                         |
| NDK version     | `r27c` (LLVM 18.1)                                        |
| API level       | `24` (Android 7.0+)                                       |
| Bootstrap rustc | `rustup stable`                                            |
| Build triple    | `x86_64-unknown-linux-gnu` (GitHub Actions ubuntu-latest)  |
| Host triple     | `aarch64-linux-android` (self-hosting)                     |
| Target triple   | `aarch64-linux-android`                                    |

## Building via GitHub Actions (primary path)

`main` is the **only branch**. The workflow at `.github/workflows/main.yml` is manually dispatched (`workflow_dispatch`) and runs the full pipeline on an `ubuntu-latest` runner:

1. Frees disk, installs `cmake ninja-build patchelf` etc.
2. Runs `./build.sh all` — `prepare → symlinks → shims → patches → vendor → patches-post-vendor → configure → dist`
3. Extracts the resulting tarballs and runs `./verify.sh` (static checks: no Termux strings, no bad RPATH, correct Android PT_INTERP — with the offending file list printed on failure)
4. Uploads the dist directory as the `rustdroid-toolchain-aarch64` artifact (14-day retention)

An LLVM build cache (`stage/rust-src/build/*/llvm`, ~2 GB) is keyed on `hashFiles(bootstrap.toml.template, build.sh)` with an `llvm-` restore-key fallback, so unrelated changes do not force a full LLVM rebuild. First full run ≈ 2.5 h; cached runs ≈ 1.5 h.

**Expected dist output** (component tarballs + one runtime lib):

```
stage/dist/
├── rustc-1.85.0-aarch64-linux-android.tar.xz
├── cargo-1.85.0-aarch64-linux-android.tar.xz      # needs extended = true (see below)
├── rust-std-1.85.0-aarch64-linux-android.tar.xz
├── rustc-dev-1.85.0-aarch64-linux-android.tar.xz
├── rust-src-1.85.0.tar.xz
├── rust-dev-1.85.0-aarch64-linux-android.tar.xz
└── libc++_shared.so                               # runtime dep of -lc++_shared-linked binaries
```

## Notes learned the hard way

Each of these was a real CI failure (see commit history / DEVIATIONS.md):

- **`x.py dist` writes to `stage/rust-src/build/dist/`** — not `stage/dist/`. `build.sh do_dist` copies the tarballs into `stage/dist/` so verification and the artifact upload find them (run #16 failed exactly here after a 2h23m build).
- **Tool tarballs (cargo) only appear with `extended = true`** in `[build]`; plain `x.py dist` yields only rustc/rust-std/rustc-dev/docs/src.
- **Dist binaries are linked with `-lc++_shared`** (Android has no system libc++), so the NDK's `libc++_shared.so` is bundled next to the tarballs.
- **Bootstrap does not vendor git checkouts by default** — `vendor = true` only applies to release tarballs. Without an explicit source-replacement config, tool builds (cargo included) compile from the **unpatched crates.io registry**, silently embedding Termux paths from `openssl-probe` (run #20). Fix: `do_vendor` writes `$RUST_SRC/.cargo/config.toml` with the source replacement, and `bootstrap.toml` sets `vendor = true`.
- **Editing vendored sources breaks cargo's directory-source checksums** — after patching `vendor/openssl-probe`, `.cargo-checksum.json` must be regenerated or `--frozen` builds fail with "the listed checksum has changed" (run #21). Fix: `do_patches_post_vendor` resyncs the checksum file for every crate a patch touched.
- **openssl-sys must come from the vendor set, not the registry**, and needs `ANDROID_NDK_ROOT`/`ANDROID_NDK_HOME` exported for its vendored-openssl cross-build (run #18).

## Building locally (alternative)

On a real Ubuntu x86_64 host with root, 16 GB+ RAM, 30 GB+ disk:

```bash
./build.sh env         # show pinned config
./build.sh all         # full pipeline, 2-4 h
./verify.sh stage/dist-extracted/   # after extracting tarballs
```

For a fast pipeline check without the full build: `./build.sh all-smoke` (cross-compiles `hello.c` with the NDK + shims, `x.py` config validation).

## On-device validation (via adb, app sandbox)

```bash
PREFIX=/data/data/dev.rustdroid.ide/files/usr

# 1. Push the dist directory (tarballs + libc++_shared.so).
adb push stage/dist /data/local/tmp/rustdroid-dist

# 2. Extract under the app's sandbox (must be writable by the app user).
adb shell run-as dev.rustdroid.ide sh -c '
  mkdir -p '$PREFIX'/lib &&
  cd '$PREFIX' &&
  for f in /data/local/tmp/rustdroid-dist/*.tar.xz; do tar xf "$f"; done &&
  cp /data/local/tmp/rustdroid-dist/libc++_shared.so '$PREFIX'/lib/'

# 3. Smoke-compile: rustc + cargo must both run.
adb shell run-as dev.rustdroid.ide sh -c '
  export RUSTDROID_PREFIX='$PREFIX' &&
  export LD_LIBRARY_PATH='$PREFIX'/lib &&
  export PATH='$PREFIX'/bin:$PATH &&
  echo "fn main() { println!(\"hello from RustDroid\"); }" > hello.rs &&
  rustc hello.rs -o hello && ./hello &&
  cargo new smoke && cd smoke && cargo run'

# 4. Full on-device verification (static checks + smoke-compile).
adb shell run-as dev.rustdroid.ide sh -c '
  RUSTDROID_PREFIX='$PREFIX' RUSTDROID_PACKAGE_NAME=dev.rustdroid.ide \
  sh /data/local/tmp/verify.sh '$PREFIX' --device'
```

`LD_LIBRARY_PATH=$PREFIX/lib` is required: dist binaries carry `DT_NEEDED=libc++_shared.so`, which is not part of Android's system libraries.

## Repo layout

```
├── .github/workflows/main.yml     # CI build + verify + artifact upload (workflow_dispatch)
├── env.sh                         # single source of truth (paths, versions, triples)
├── bootstrap.toml.template        # x.py config.toml template (@VAR@ substitution)
├── build.sh                       # driver: prepare/symlinks/shims/patches/vendor/configure/dist
├── verify.sh                      # static + on-device verification
├── shims/android_shims.c          # Bionic fallbacks (weak syncfs symbol)
├── patches/post-vendor/0001-...   # openssl-probe: RUSTDROID_PREFIX-aware cert/lib probing
└── DEVIATIONS.md                  # verified-vs-theoretical matrix, Termux deltas
```

`main` is the only branch — fixes land directly on it, and each CI run dispatches from `main`.

## License & attribution

MIT. Design references Termux's public `rust/build.sh` but contains no Termux code or branding. Rust is MIT/Apache-2.0 (rust-lang.org); the Android NDK is Apache-2.0 + LLVM-variant (Google).
