# RustDroid

A **Pydroid-style Rust IDE for Android** — develop, compile, and run Rust programs entirely on-device, with no PC, no Termux, and no root.

The repo contains two things:

1. **A self-hosting Rust toolchain for `aarch64-linux-android`** (`rustc` + `cargo` that execute on Bionic), built by CI and installed under an app-owned prefix — `rustc` + `cargo` that **execute on Android** (`/system/bin/linker64`), reparented to `/data/data/dev.rustdroid.ide/files/usr`, zero Termux filesystem assumptions, zero Termux code or branding.
2. **The Android app** (`android/`, `dev.rustdroid.ide`) that installs the toolchain in one tap and provides the IDE: code editor with Rust syntax highlighting, project management, crates.io dependency search + download, build/run console with diagnostics.

## Project phases

| Phase | Scope | Status |
|-------|-------|--------|
| **1 — Toolchain** | Self-hosting rustc + cargo for `aarch64-linux-android`, built via GitHub Actions, verified statically + on-device | **VALIDATED ON-DEVICE** (2026-09-02): full `rustc` compile + link + run works on a TECNO-LJ9 via the `rustdroid-link` kit (`hello from RustDroid on Android`). CI: runs #22/#23 green; kit + libunwind fix landed in 0f7eb19/2cc5296, baked into artifacts from run #26 on |
| **2 — App shell** | Android app that downloads/installs the toolchain, gate screen, settings, foreground-service download | **BUILT, on-device first pass done** (see `android/`): install/download/verify loop works on a TECNO-LJ9; first-report bugs fixed in-app; SHA-256-pinned bundle |
| **3 — IDE experience** | Code editor with Rust TextMate syntax, project templates, build output panel with diagnostics, `cargo run` console, crates.io dependency search + fetch | **BUILT**: editor + tabs + file tree, DiagnosticsParser-backed problems panel, selectable/copyable console, deps screen with search/fetch and a self-healing TLS trust store |

## The app

Install flow: on first launch the gate screen offers **Download** (117 MB bundle from the GitHub release, in a foreground service so screen-off doesn't kill it) or **Import zip file…** (download on a PC, copy to the phone). The bundle is SHA-256-pinned in-app; a corrupted download fails loudly instead of producing a broken install. After extraction, a health check runs the real compile+link+run smoke test before the toolchain is marked Ready.

Dependency downloads are fully on-device: the deps screen searches crates.io (their API policy respected: UA + debounced queries) and runs `cargo fetch` in the project. **TLS trust is self-contained**: cargo links a statically-built TLS backend that knows nothing about Android's CA store, so the app ships a pinned **Mozilla CA bundle** (`android/app/src/main/assets/ssl/cacert.pem`, MPL-2.0, 121 roots) and exposes it to cargo through the explicit CAfile channels — `CARGO_HTTP_CAINFO`/`SSL_CERT_FILE`/`CURL_CA_BUNDLE` → `$HOME/.ssl/cacert.pem`, `http.cainfo` in `$CARGO_HOME/config.toml`, and a mirror at `$RUSTDROID_PREFIX/etc/tls/cert.pem` for the patched `openssl-probe`. The bundle is validated and self-healed on every cargo invocation, so a stale, truncated, or missing file rebuilds itself from the APK asset automatically. `SSL_CERT_DIR` is deliberately **not** exported: libcurl turns it into a hashed `CApath`, which some statically-linked backends reject outright (see Troubleshooting).

Toolchain downloads retry (4 attempts, 1s/3s/7s backoff) and **resume via HTTP Range** — a .part file is re-hashed and the download continues from where the network dropped; there is no whole-request timeout wall on the 117 MB bundle.

### Troubleshooting

- **`[77] Problem with the SSL CA cert` / `error setting certificate verify locations`** — history: the first cause was a dangling/missing `CAfile` (fixed by the bundled-CA design, commit `77d6385`). The second cause is the **CApath channel**: the env used to export `SSL_CERT_DIR=/system/etc/security/cacerts`, which libcurl turns into `CURLOPT_CAPATH` — and the statically-linked TLS backend inside Android cargo builds fails the *whole* verify-location setup whenever a `CApath` is configured, **even with a perfectly valid `CAfile`**. That matches error 77 firing instantly (before any network I/O) while the `diag:` line shows the bundle is OK. Since app `0.1.1` the env is CAfile-only (`SSL_CERT_DIR=unset` in the diag line) and cargo runs with `CARGO_HTTP_DEBUG=1`, so a persistent failure prints the full curl verbose trace in the console — paste it when reporting.
- **Last-resort TLS escape hatch** — if error 77 still blocks downloads on a `0.1.1+` build, you can opt a project out of certificate verification: create an empty file named **`.rustdroid-insecure-tls`** in the project root (via the editor's file tree) or `~/.config/rustdroid/insecure-tls` under the app home. cargo then receives `http.danger-accept-invalid-certs=true`, which bypasses the failing verify-location setup entirely. This trades MITM protection for connectivity on your own device and risk profile — use it only to unblock yourself, and delete the file once the underlying issue is fixed.
- **Toolchain download slow / times out** — fixed by the resume+retry downloader (no more 5-minute whole-call budget). If your connection to GitHub's release CDN is throttled, download `rustdroid-app-bundle-aarch64.zip` on a PC and use **Import zip file…** instead.
- **`cargo fetch` fails with `failed to get <crate>`** after a successful install — check the fetch output panel; the TLS hint (above) distinguishes trust failures from plain network problems.
- **Compiling C dependencies** (build scripts using the `cc` crate) is not supported yet — the `cc` shim is link-only; a real on-device clang is future work.

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
| CA bundle asset | `ssl/cacert.pem` — refresh from https://curl.se/ca/cacert.pem periodically |

## Building via GitHub Actions (primary path)

`main` is the **only branch**. The workflow at `.github/workflows/main.yml` is manually dispatched (`workflow_dispatch`) and runs the full pipeline on an `ubuntu-latest` runner:

1. Frees disk, installs `cmake ninja-build patchelf` etc.
2. Runs `./build.sh all` — `prepare → symlinks → shims → patches → vendor → patches-post-vendor → configure → dist`
3. Extracts the resulting tarballs and runs `./verify.sh` (static checks: no Termux strings, no bad RPATH, correct Android PT_INTERP — with the offending file list printed on failure)
4. Uploads the dist directory as the `rustdroid-toolchain-aarch64` artifact (14-day retention)

An LLVM build cache (`stage/rust-src/build/*/llvm`, ~2 GB) is keyed on `hashFiles(bootstrap.toml.template, build.sh)` with an `llvm-` restore-key fallback, so unrelated changes do not force a full LLVM rebuild. First full run ≈ 2.5 h; cached runs ≈ 1.5 h.

A second cache layer, **ccache** (bootstrap's native `[llvm] ccache` → `CMAKE_{C,CXX}_COMPILER_LAUNCHER`), covers the C/C++ compiles: when the LLVM tree cache misses or cmake regenerates `build.ninja`, a warm ccache serves most of the ~5,200 compile units from its content-addressed store instead of recompiling. The tree cache is the fast path; ccache is rebuild insurance. Rust compiles are NOT ccache'd (that would need sccache, which is risky with the `-Z` flags stage builds use).

A separate **Android app** workflow (`.github/workflows/android.yml`) builds the debug APK on every push.

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
- **Cargo TLS on-device**: cargo's libcurl links a static OpenSSL with no Android CA knowledge. Trust must be provided end-to-end by the app (bundled Mozilla PEM + env + cargo config + openssl-probe mirror) — see "The app" above.

## Building locally (alternative)

On a real Ubuntu x86_64 host with root, 16 GB+ RAM, 30 GB+ disk:

```bash
./build.sh env         # show pinned config
./build.sh all         # full pipeline, 2-4 h
./verify.sh stage/dist-extracted/   # after extracting tarballs
```

Optional: `sudo apt install ccache` before building — `build.sh` auto-detects it, enables bootstrap's `[llvm] ccache`, and keeps its store in `stage/ccache` (subsequent local rebuilds of LLVM become near-instant). Without ccache everything still builds, just uncached.

For a fast pipeline check without the full build: `./build.sh all-smoke` (cross-compiles `hello.c` with the NDK + shims, `x.py` config validation).

The Android app builds like any Compose project: `cd android && ./gradlew assembleDebug` (or use the `android.yml` workflow), unit tests with `./gradlew test`.

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

What works today (verified on a TECNO-LJ9, 2026-09-02): `rustc --version`, `cargo --version`, `rustc hello.rs -o hello && ./hello` — **full compile, link, and execution, entirely on-device** (the toolchain is self-hosting end-to-end). Linking goes through the `cc` shim → `gcc-ld/ld.lld` → `rust-lld -flavor gnu` chain with the `rustdroid-link` kit providing Bionic crt objects, NDK runtime libs, and `libunwind.a` (clang runtime dir — see run #26+ artifacts; earlier artifacts need the [libunwind.a patch](https://github.com/majnoon40/RustDroid/releases/download/device-kit-patch-20260902/libunwind.a)). **Not yet supported**: compiling C code (build scripts using the `cc` crate) — the shim is link-only and reports that clearly; a real on-device `clang` is future work. `cargo build` of crates with network dependencies needs the registry reachable from the device — the app provides TLS trust automatically (see "The app").

`LD_LIBRARY_PATH=$TC/lib` is required: dist binaries carry `DT_NEEDED=libc++_shared.so`, which is not part of Android's system libraries.

For the app-sandbox prefix, the same steps run wrapped in `adb shell run-as dev.rustdroid.ide` with `PREFIX=/data/data/dev.rustdroid.ide/files/usr` — plus:

```bash
adb shell run-as dev.rustdroid.ide sh -c '
  RUSTDROID_PREFIX='$PREFIX' RUSTDROID_PACKAGE_NAME=dev.rustdroid.ide \
  sh /data/local/tmp/verify.sh '$PREFIX' --device'
```

## Repo layout

```
├── .github/workflows/main.yml     # CI build + verify + artifact upload (workflow_dispatch)
├── .github/workflows/android.yml  # Android debug APK build on push
├── android/                       # the IDE app (Compose, dev.rustdroid.ide)
│   └── app/src/main/assets/ssl/cacert.pem   # pinned Mozilla CA bundle (MPL-2.0)
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

MIT. Design references Termux's public `rust/build.sh` but contains no Termux code or branding. Rust is MIT/Apache-2.0 (rust-lang.org); the Android NDK is Apache-2.0 + LLVM-variant (Google); the bundled CA store is Mozilla's (`cacert.pem`, MPL-2.0, via curl.se).
