# RustDroid

A **Pydroid-style Rust IDE for Android** — develop, compile, and run Rust programs entirely on-device, with no PC, no Termux, and no root.

This repository currently contains **Phase 1**: the foundation everything else depends on — a **self-hosting Rust toolchain for `aarch64-linux-android`**, built by CI and installable under an app-owned prefix:

- `rustc` + `cargo` that **execute on Android** (Bionic libc, `/system/bin/linker64`)
- reparented to `/data/data/dev.rustdroid.ide/files/usr` — a prefix the future IDE app fully controls
- zero Termux filesystem assumptions, zero Termux code or branding

## Project phases

| Phase | Scope | Status |
|-------|-------|--------|
| **1 — Toolchain** (this repo) | Self-hosting rustc + cargo for `aarch64-linux-android`, built via GitHub Actions, verified statically + on-device | **CI green** (run #22, 73 min): all 10 tarballs + `libc++_shared.so` built, 182 ELF files pass static checks (no Termux paths, correct PT_INTERP). On-device validation pending |
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

A second cache layer, **ccache** (bootstrap's native `[llvm] ccache` → `CMAKE_{C,CXX}_COMPILER_LAUNCHER`), covers the C/C++ compiles: when the LLVM tree cache misses or cmake regenerates `build.ninja`, a warm ccache serves most of the ~5,200 compile units from its content-addressed store instead of recompiling. The tree cache is the fast path; ccache is rebuild insurance. Rust compiles are NOT ccache'd (that would need sccache, which is risky with the `-Z` flags stage builds use).

**Expected dist output** (component tarballs + runtime lib + link kit):

```
stage/dist/
├── rustc-1.85.0-aarch64-linux-android.tar.xz
├── cargo-1.85.0-aarch64-linux-android.tar.xz      # needs extended = true (see below)
├── rust-std-1.85.0-aarch64-linux-android.tar.xz
├── rustc-dev-1.85.0-aarch64-linux-android.tar.xz
├── rust-src-1.85.0.tar.xz
├── rust-dev-1.85.0-aarch64-linux-android.tar.xz
├── libc++_shared.so                               # runtime dep of -lc++_shared-linked binaries
└── rustdroid-link/                                # on-device LINKING support
    ├── bin/{cc,clang,gcc}                         #   linker-driver shims over rust-lld
    ├── crtbegin_dynamic.o, crtbegin_so.o          #   Bionic startup objects (NDK)
    ├── crtend_android.o, crtend_so.o
    ├── libgcc.a, libunwind.a, libclang_rt.builtins.a
    └── sysroot/                                   #   link-time stubs (libc.so, libm.so, ...)
```

## Notes learned the hard way

Each of these was a real CI failure (see commit history / DEVIATIONS.md):

- **`x.py dist` writes to `stage/rust-src/build/dist/`** — not `stage/dist/`. `build.sh do_dist` copies the tarballs into `stage/dist/` so verification and the artifact upload find them (run #16 failed exactly here after a 2h23m build).
- **Tool tarballs (cargo) only appear with `extended = true`** in `[build]`; plain `x.py dist` yields only rustc/rust-std/rustc-dev/docs/src.
- **Dist binaries are linked with `-lc++_shared`** (Android has no system libc++), so the NDK's `libc++_shared.so` is bundled next to the tarballs.
- **Bootstrap does not vendor git checkouts by default** — `vendor = true` only applies to release tarballs. Without an explicit source-replacement config, tool builds (cargo included) compile from the **unpatched crates.io registry**, silently embedding Termux paths from `openssl-probe` (run #20). Fix: `do_vendor` writes `$RUST_SRC/.cargo/config.toml` with the source replacement, and `bootstrap.toml` sets `vendor = true`.
- **Editing vendored sources breaks cargo's directory-source checksums** — after patching `vendor/openssl-probe`, `.cargo-checksum.json` must be regenerated or `--frozen` builds fail with "the listed checksum has changed" (run #21). Fix: `do_patches_post_vendor` resyncs the checksum file for every crate a patch touched.
- **openssl-sys must come from the vendor set, not the registry**, and needs `ANDROID_NDK_ROOT`/`ANDROID_NDK_HOME` exported for its vendored-openssl cross-build (run #18).
- **On-device validation (2026-09-02, TECNO-LJ9, Android shell in `/data/local/tmp`)**: `rustc --version`, `cargo --version` and `rustc --emit=obj` all work — the Bionic-hosted compiler genuinely compiles Rust on Android. `rustc hello.rs -o hello` failed with `linker 'cc' not found`: stock Android ships no C linker driver, crt objects, or link-time libc stubs. Fix: the **rustdroid-link kit** (crt objects + bionic stubs + `cc`/`clang`/`gcc` shims that drive the toolchain's own `rust-lld`) is bundled into the dist; after installing it, `rustc` links with no extra flags.
- **Windows repacking loses Unix permission bits**: extracting/repacking the tarballs with Windows `tar` produces `rw-rw-rw-` files — run `chmod -R 755 <toolchain-dir>` once on-device after extracting.

## Building locally (alternative)

On a real Ubuntu x86_64 host with root, 16 GB+ RAM, 30 GB+ disk:

```bash
./build.sh env         # show pinned config
./build.sh all         # full pipeline, 2-4 h
./verify.sh stage/dist-extracted/   # after extracting tarballs
```

Optional: `sudo apt install ccache` before building — `build.sh` auto-detects it, enables bootstrap's `[llvm] ccache`, and keeps its store in `stage/ccache` (subsequent local rebuilds of LLVM become near-instant). Without ccache everything still builds, just uncached.

For a fast pipeline check without the full build: `./build.sh all-smoke` (cross-compiles `hello.c` with the NDK + shims, `x.py` config validation).

## On-device validation (via adb)

**Minimum set for a smoke test** (skip rust-dev / rustc-dev / docs / src / the big `rust-` meta bundle):
`rustc`, `rust-std`, `cargo` tarballs + `libc++_shared.so` + `rustdroid-link/`.

> **Windows hosts**: Android's `tar` has no `xz` support, so decompress on the PC (`tar -xf foo.tar.xz`) and push plain `tar`s. Windows repacking also drops Unix permission bits — run the `chmod` step below, always.

```bash
adb shell mkdir -p /data/local/tmp/rd
adb push rustc.tar rust-std.tar cargo.tar /data/local/tmp/rd/   # PC-decompressed, plain tar
adb push libc++_shared.so /data/local/tmp/rd/
adb push rustdroid-link /data/local/tmp/rd/                     # loose directory, recursive push
```

Then, inside one `adb shell` session (the exports must survive):

```sh
cd /data/local/tmp/rd
mkdir -p tc
for t in rustc rust-std cargo; do tar xf $t.tar -C tc --strip-components=2; done
cp libc++_shared.so tc/lib/         # runtime dep of rustc (libc++_shared)
cp -a rustdroid-link tc/lib/        # crt objects + bionic stubs
mkdir -p tc/bin
cp rustdroid-link/bin/* tc/bin/     # cc / clang / gcc linker-driver shims
chmod -R 755 tc                     # (Windows-repack permission fix; harmless on Linux hosts)

export TC=/data/local/tmp/rd/tc
export LD_LIBRARY_PATH=$TC/lib      # libc++_shared.so for rustc and rust-lld
export HOME=/data/local/tmp/rd      # cargo home
export PATH=$TC/bin:$PATH           # rustc, cargo, AND the cc shim (rustc's default linker)

rustc --version && cargo --version

echo 'fn main() { println!("hello from RustDroid on Android"); }' > hello.rs
rustc hello.rs -o hello && ./hello

cargo new smoke && cd smoke && cargo run
```

What works today (verified on a TECNO-LJ9, 2026-09-02): `rustc --version`, `cargo --version`, `rustc --emit=obj` (full compile pipeline), and — with the link kit installed — linking and running `hello`-style binaries via the `cc` shim + `rust-lld`. **Not yet supported**: compiling C code (build scripts using the `cc` crate) — the shim is link-only and reports that clearly; a real on-device `clang` is Phase 2 work. `cargo build` of crates with network dependencies needs the registry reachable from the device.

`LD_LIBRARY_PATH=$TC/lib` is required: dist binaries carry `DT_NEEDED=libc++_shared.so`, which is not part of Android's system libraries.

For the real app-sandbox prefix (once the Phase 2 app exists), the same steps run wrapped in `adb shell run-as dev.rustdroid.ide` with `PREFIX=/data/data/dev.rustdroid.ide/files/usr` — plus:

```bash
adb shell run-as dev.rustdroid.ide sh -c '
  RUSTDROID_PREFIX='$PREFIX' RUSTDROID_PACKAGE_NAME=dev.rustdroid.ide \
  sh /data/local/tmp/verify.sh '$PREFIX' --device'
```

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
