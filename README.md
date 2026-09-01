# RustDroid Toolchain

Build a **self-hosting Rust toolchain for `aarch64-linux-android`** — `rustc` + `cargo` that run ON Android, reparented under an app-controlled prefix (`/data/data/dev.rustdroid.ide/files/usr`), independent of Termux's filesystem assumptions.

This is the first, highest-risk piece of the RustDroid IDE (a Pydroid-style Rust IDE): proving that a Bionic-hosted `rustc` + `cargo` can be built and run standalone on Android.

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

The workflow at `.github/workflows/main.yml` (manually dispatched via `workflow_dispatch`) runs the full pipeline on an `ubuntu-latest` runner:

1. Frees disk, installs `cmake ninja-build patchelf` etc.
2. Runs `./build.sh all` — `prepare → symlinks → shims → patches → vendor → patches-post-vendor → configure → dist`
3. Extracts the resulting tarballs and runs `./verify.sh` (static checks: no Termux strings, no bad RPATH, correct Android PT_INTERP)
4. Uploads the dist directory as the `rustdroid-toolchain-aarch64` artifact (14-day retention)

An LLVM build cache (`stage/rust-src/build/*/llvm`, ~2 GB) is keyed on `hashFiles(bootstrap.toml.template, build.sh)` with an `llvm-` restore-key fallback, so unrelated changes do not force a full LLVM rebuild. First full run ≈ 2.5 h; cached runs ≈ 1.5-2 h.

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

Notes learned the hard way (see commit history / DEVIATIONS.md):

- `x.py dist` writes to `stage/rust-src/build/dist/` — `build.sh do_dist` copies the tarballs into `stage/dist/` so verification and the artifact upload find them (run #16 failed exactly here after a 2h23m build).
- **Tool tarballs (cargo) only appear with `extended = true`** in `[build]`; plain `x.py dist` yields only rustc/rust-std/rustc-dev/docs/src.
- Dist binaries are linked with `-lc++_shared` (Android has no system libc++), so the NDK's `libc++_shared.so` is bundled next to the tarballs.

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
├── .github/workflows/main.yml     # CI build + verify + artifact upload
├── env.sh                         # single source of truth (paths, versions, triples)
├── bootstrap.toml.template        # x.py config.toml template (@VAR@ substitution)
├── build.sh                       # driver: prepare/symlinks/shims/patches/vendor/configure/dist
├── verify.sh                      # static + on-device verification
├── shims/android_shims.c          # Bionic fallbacks (weak syncfs symbol)
├── patches/post-vendor/0001-...   # openssl-probe: RUSTDROID_PREFIX-aware cert/lib probing
└── DEVIATIONS.md                  # verified-vs-theoretical matrix, Termux deltas
```

## License & attribution

MIT. Design references Termux's public `rust/build.sh` but contains no Termux code or branding. Rust is MIT/Apache-2.0 (rust-lang.org); the Android NDK is Apache-2.0 + LLVM-variant (Google).
