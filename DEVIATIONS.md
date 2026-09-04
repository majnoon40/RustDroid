# Deviations from Termux's `rust/build.sh` + Verified-vs-Theoretical Matrix

## 1. Deviations from `termux-packages/packages/rust/build.sh`

These are deliberate adaptations made for RustDroid. Anything NOT listed here
matches Termux's approach (or, where Termux had no equivalent, was added fresh).

### 1.1 Package identity & prefix path

| Aspect              | Termux                          | RustDroid                                |
|---------------------|----------------------------------|------------------------------------------|
| Package name         | `com.termux`                     | `dev.rustdroid.ide`                       |
| On-device prefix     | `/data/data/com.termux/files/usr` | `/data/data/dev.rustdroid.ide/files/usr` |
| Prefix parameterization | Hardcoded in patches          | Env var `RUSTDROID_PREFIX` read by `openssl-probe` patch |
| Stage (build-host) prefix | Same as on-device             | Separate `RUSTDROID_STAGE_PREFIX` so build-host work files don't get baked into binaries |

**Why**: We must NOT hardcode our package name in patches because (a) the
same dist tarball may be re-targeted to a different package name later
via `patchelf --set-rpath` and (b) rebuilding rustc for every package
name change is prohibitive. The `RUSTDROID_PREFIX` env var pattern lets
the tarball be relocated.

### 1.2 NDK version

| Aspect        | Termux                  | RustDroid                |
|----------------|--------------------------|--------------------------|
| NDK            | Historically r26d (LLVM 17.0.2); current Termux tracks newer | r27c (LLVM 18.1) |
| API level      | 24 (Android 7.0+)        | 24 (same)                |
| Cross-clang symlinks | Created in build.sh | Created in `build.sh symlinks` step |

**Why**: User-locked r27c. r27c is the latest stable as of mid-2025 and
matches the LLVM 18.x generation that rustc 1.85.0 expects.

### 1.3 Rust tag

| Aspect        | Termux                  | RustDroid                |
|----------------|--------------------------|---------------------------|
| Source pin      | Tracks latest Termux rust package version | `1.85.0` (commit `4d91de4e48198da2e33413efdcd9cd2cc0c46688`) |
| Bootstrap rustc | Termux cross-bootstraps from x86_64-linux rustc | `rustup stable` (rust-lang's recommended path for non-rust-lang-org builders) |

**Why**: User-locked 1.85.0 (Feb 2025 stable). Stage0 toolchain is the
1.84.0 tarball from `static.rust-lang.org/dist/2025-01-09/` per the
`src/stage0.json` for tag 1.85.0.

### 1.4 Bionic shims

| Aspect        | Termux                  | RustDroid                |
|----------------|--------------------------|---------------------------|
| `syncfs()` stub | Provides via Termux's `libandroid-support` package, linked via `RUSTFLAGS=-landroid-support` | Provides via standalone `libandroid_shims.a` (no Termux dependency) |
| `backtrace_symbols` / libexecinfo | Termux ships `libexecinfo` package and patches std to link it | Modern rust-lang/rust@1.85.0 std uses `libunwind` (NDK r27c ships it) — no patch needed. `patches/0002-std-no-libexecinfo-on-android.patch` was deleted after smoke build confirmed the symbol isn't referenced in `src/build_helper/src/lib.rs`. |
| `openssl-probe` path | Hardcoded `/data/data/com.termux/files/usr/lib` added to probe list | Reads `RUSTDROID_PREFIX` env var first; falls back to system paths if unset. Patch lives in `patches/post-vendor/` because `vendor/openssl-probe/src/lib.rs` only exists after `cargo vendor` runs. |

**Why**: RustDroid can't depend on a Termux package (`libandroid-support`),
so we ship our own minimal static archive. The weak-symbol approach means
the shim is a no-op on Bionic versions that already provide `syncfs()`
(API 30+, Android 11) and a syscall-dispatching fallback on older Bionic.

**Verified empirically**: `libandroid_shims.a` compiles cleanly with
`aarch64-linux-android24-clang`; `llvm-nm` shows `W syncfs` (weak symbol);
a cross-compiled binary linking `-landroid_shims` resolves the symbol and
links without errors.

### 1.5 `bootstrap.toml` (a.k.a. Termux's "config.toml")

| Aspect        | Termux                  | RustDroid                |
|----------------|--------------------------|---------------------------|
| Config file name | `bootstrap.toml` (older name) or `config.toml` | `config.toml` (rust-lang/rust@1.85.0 looks for this exact name) |
| `[install]`     | prefix + sysconfdir + localstatedir | prefix + sysconfdir only — `localstatedir` is NOT a valid 1.85.0 field (rejected by x.py) |
| `[llvm] download-ci-llvm` | true (Termux's CI has access) | false — rust-lang.org deleted 1.85.0's CI LLVM artifacts (HTTP 404 from `ci-artifacts.rust-lang.org`). Build LLVM from `src/llvm-project` submodule instead, requires cmake + ninja-build. |
| `[llvm] experimental-targets` | boolean `false` | string `""` — must be a string in 1.85.0 (boolean rejected) |
| `[rust] optimize-llvm` | bool | REMOVED — not a valid 1.85.0 `[rust]` field |
| `[rust] parallel-compiler` | bool | REMOVED — not a valid 1.85.0 `[rust]` field |
| `[rust] use-libcxx` | (varies) | REMOVED from `[rust]` — not a valid `[rust]` field; would go under `[llvm]` if needed |
| `[rust] lld`, `use-lld` | not set | Both true — explicit, because glibc's `ld` would produce glibc-dynlinked binaries; `lld` from NDK r27c produces correct Android interp |
| `[dist] sign-key` | (varies) | REMOVED — not a valid 1.85.0 `[dist]` field; valid fields are `sign-folder, upload-addr, src-tarball, compression-formats, compression-profile, include-mingw-linker, vendor` |
| `[target.aarch64-linux-android] nm/objcopy/objdump/strip` | set | REMOVED — not valid 1.85.0 `[target.*]` fields. rustc falls back to PATH lookup for `llvm-nm`/etc. |
| `[target.aarch64-linux-android] android-ndk` | set | REMOVED — not a valid 1.85.0 `[target.*]` field. NDK path is conveyed only via the `cc/cxx/linker` paths. |
| `[target.aarch64-linux-android] rustflags` | inline | REMOVED from config — not a valid 1.85.0 `[target.*]` field. Per-target rustflags must be set via the `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS` env var. The `build.sh` `dist` step exports this env var with `-Clink-arg=-L$SHIM_LIB_DIR -Clink-arg=-landroid_shims -Wl,--enable-new-dtags`. |
| `change-id` at top level | not set | Set to `134650` — required by x.py 1.85.0 (warning if absent, future-proofing for schema changes) |

### 1.6 Script structure

| Aspect        | Termux                  | RustDroid                |
|----------------|--------------------------|---------------------------|
| Layout         | Single `build.sh` (~600 lines) | Modular: `env.sh` + `bootstrap.toml.template` + `patches/{,post-vendor}/*.patch` + `shims/android_shims.c` + `build.sh` driver + `verify.sh` + `README.md` + this `DEVIATIONS.md` |
| Idempotency    | (varies)                  | Every step is idempotent: re-running is safe; `patches` step reverses-applies before applying forward; `prepare` skips downloads if cached |
| Warn-not-fail  | n/a                       | `patches` and `patches-post-vendor` warn-not-fail so smoke build can proceed even when patch paths drift from pinned tag |
| Verification   | (none in build.sh itself) | `verify.sh` performs 4 checks: grep com.termux, readelf -d RPATH, readelf -l INTERP, on-device smoke-compile |

## 2. Verified-vs-Theoretical Matrix

Status legend:
- ✅ **VERIFIED** — observed working in the smoke build (2026-08-31 sandbox)
- ⚠️ **THEORETICAL** — designed correctly but not yet empirically validated here
- ❌ **BLOCKED** — known blocking issue, needs separate fix

### 2.1 Build pipeline

| Step                                     | Status | Notes |
|------------------------------------------|--------|-------|
| rustup install stable on x86_64-linux     | ✅     | rustc 1.98.0 installed |
| NDK r27c download (634MB zip)             | ✅     | 6s from dl.google.com |
| NDK r27c extract + symlink resolution     | ✅     | clang-18, llvm-ar, llvm-nm etc. all present |
| NDK clang symlink creation (`aarch64-linux-android24-clang` etc.) | ✅ | All 8 symlinks created; `--version` reports LLVM 18.0.3 targeting Android 24 |
| `libandroid_shims.a` compile + archive    | ✅     | Weak `syncfs` symbol present in archive |
| Cross-compile hello.c with NDK clang + shims | ✅ | Binary: ARM aarch64, PT_INTERP `/system/bin/linker64`, for Android 24 |
| Clone rust-lang/rust @ 1.85.0 (shallow)   | ✅     | 363MB, ~10s |
| Fetch `src/tools/cargo` submodule         | ✅     | commit `d73d2caf9e41a39daf2a8d6ce60ec80bf354d2a7` |
| Auto-fetch `library/backtrace` + `library/stdarch` + `src/llvm-project` submodules | ✅ | x.py did this automatically on first invocation |
| Download stage0 rustc/cargo/rust-std 1.84.0 | ✅ | Extracted to `build/x86_64-unknown-linux-gnu/stage0/` |
| Stage0 rustc runs on x86_64-linux host     | ✅     | `ldd` resolves all libs; `libLLVM.so.19.1-rust-1.84.0-stable` loads |
| x.py parses our `config.toml`              | ✅     | No field errors, no parse errors |
| `x.py check src/bootstrap` (full check)   | ⚠️    | Blocked by missing `cmake` (sandbox has no root). On real Ubuntu host with `apt-get install cmake ninja-build` this should pass. |
| `x.py build --dry-run --stage 0`           | ⚠️    | Same cmake limitation |
| `cargo vendor` (populate `vendor/`)        | ⚠️    | Not run in this smoke (heavy ~5GB). build.sh `vendor` step would do this. |
| openssl-probe patch (0001) applies post-vendor | ⚠️ | Could not run because vendor step was skipped. Patch target path `vendor/openssl-probe/src/lib.rs` is the standard location. |
| `x.py dist --stage 2` (full self-hosting)  | ❌     | Not attempted in this sandbox (2-4+ hours, needs ~16GB RAM, ~30GB disk, cmake, 8+ cores). User must run on a real build host. |

### 2.2 Verification (`verify.sh` checks)

| Check                                    | Status | Notes |
|------------------------------------------|--------|-------|
| Check 1: grep `com.termux` in ELF files  | ✅     | PASS on cross-compiled hello binary. No Termux hardcoded paths. |
| Check 2: `readelf -d` DT_RPATH/DT_RUNPATH | ✅    | PASS. No bad RPATH entries. |
| Check 3: `readelf -l` PT_INTERP          | ✅     | PASS. PT_INTERP = `/system/bin/linker64` (Android 64-bit). |
| Check 4: on-device smoke-compile         | ⚠️    | Not attempted (no Android device in this sandbox). See README.md "On-device validation steps" for the adb commands to run on a real device. |

### 2.3 Runtime behavior (post-dist-tarball, on device)

| Behavior                                  | Status | Notes |
|------------------------------------------|--------|-------|
| Stage2 rustc actually runs on Android arm64 | ⚠️   | Untestable in this sandbox. The recipe produces correct PIE binaries with Android linker64 interp + correct AArch64 arch (verified via stage-A), so the binaries WILL load on Android, but actual rustc execution (codegen, llvm invocation) needs on-device test. |
| `RUSTDROID_PREFIX` env var is read by openssl-probe at runtime | ⚠️ | Patch is correctly designed but not applied/verified. Needs `cargo vendor` → patch application → on-device `cargo build` of an openssl-dep crate. |
| `libandroid_shims.a` weak `syncfs` symbol resolves at runtime on API 24 | ⚠️ | Weak symbol approach is correct in theory. On API 30+ Bionic's syncfs wins; on API 24-29 our syscall-based fallback wins. Needs on-device test to confirm the actual binary's symbol resolution. |
| `lld` from NDK r27c produces correct DT_RUNPATH in stage2 binaries | ⚠️ | Verified for the hello.c binary (cross-compiled). For stage2 rustc binaries, lld is invoked by rustc with the same `-Wl,--enable-new-dtags` flag we set in CARGO_TARGET_..._RUSTFLAGS, so should be fine. Needs on-device readelf of the actual stage2 binaries. |

## 3. Open questions / risk register

### 3.1 Patch path drift

The original 3 patches I drafted targeted paths from memory of older
rust-lang/rust versions. After inspecting the actual 1.85.0 tree:

- **0002-std-no-libexecinfo-on-android.patch** → DELETED. The `execinfo`
  link attribute no longer exists in `src/build_helper/src/lib.rs` or
  anywhere in `src/` per `grep -rln 'execinfo'`. Modern rust std handles
  backtrace via the vendored `backtrace` crate (separate submodule at
  `library/backtrace`), which uses `libunwind` (provided by NDK r27c).
  No patch needed.

- **0003-cargo-etc-prefix-override.patch** → DELETED. Modern cargo
  (`src/tools/cargo/src/cargo/util/context/mod.rs`) does not contain
  a literal `/etc/cargo` string. The global config path lookup is
  abstracted through the `home` crate and computed differently.
  Documenting this as: "use `CARGO_HOME` env var on-device instead of
  expecting a global config under `$PREFIX/etc/cargo`". No patch needed.

- **0001-openssl-probe-parameterize-prefix.patch** → KEPT, but moved
  to `patches/post-vendor/`. The file `vendor/openssl-probe/src/lib.rs`
  does NOT exist until `cargo vendor` runs (which happens during the
  first `x.py` invocation that needs to resolve dependencies, or
  explicitly via `x.py vendor`). The patch's hunk headers (line numbers
  + surrounding context lines) are speculative — they may need
  adjustment against the actual vendored openssl-probe version. The
  build.sh `patches` step is warn-not-fail to accommodate this.

### 3.2 Missing cmake in sandbox

x.py validates LLVM build prerequisites at config load time, even for
`check` operations. The sandbox has no root for `apt-get install cmake`.
The `download-ci-llvm = true` alternative doesn't work either because
rust-lang deleted 1.85.0's CI LLVM artifacts. **On a real Ubuntu build
host with root, this is not an issue.** Documented in `README.md`.

### 3.3 `change-id` value may drift on future rust tags

The `change-id = 134650` value is correct for 1.85.0's bootstrap.toml
schema. When bumping to a newer rust tag, x.py will emit a NOTE telling
you the new value. Update `bootstrap.toml.template` accordingly.

### 3.4 Stage0 rustc version

We installed rustup-stable (rustc 1.98.0). The actual stage0 used by
x.py is the version pinned in `src/stage0.json` (rustc 1.84.0),
downloaded by x.py on first invocation. So our rustup-stable is only
used to build x.py's own `bootstrap` binary, NOT as the stage0 compiler.
This is correct — verified empirically: x.py downloaded 1.84.0
successfully.

### 3.5 Disk space for full build

The full `x.py dist --stage 2` build will need ~30GB disk for:
- 5GB vendor/
- 5GB rustc/build artifacts
- 10GB LLVM build artifacts (or ~150MB if `download-ci-llvm` works)
- 1GB dist tarball
- ~5GB headroom for incremental linkers

The sandbox has 9.3GB free. **Not enough for full build here.**

### 3.6 Memory for full build

Stage 2 rustc LTO link needs ~16GB RAM. The sandbox has 4GB. **Not
enough for full build here.** Will likely OOM at the LTO link step.

### 3.7 Rust tag bump considerations

If you bump to a newer rust tag (e.g. 1.86.0+):
- Re-check `change-id` value (x.py tells you the new value)
- Re-verify all `[target.*]` field names (rust-lang/rust occasionally
  renames them)
- Consider switching to `download-ci-llvm = true` if the new tag's CI
  artifacts are still hosted (would skip the cmake requirement)
- Re-test patches (paths may have drifted)

## 4. Recommendations for the next iteration

1. **Move to a real Ubuntu build host** with root, 16GB+ RAM, 30GB+ disk.
2. Run `sudo apt-get install -y cmake ninja-build`.
3. Run `./build.sh all` end-to-end. Expect 2-4+ hours.
4. If patches drift, regenerate them against the actual 1.85.0 tree
   using `git diff` against a working tree checkout.
5. Run `./verify.sh stage/dist/rust-1.85.0-aarch64-linux-android.tar.xz`.
6. Push the tarball to a real arm64 Android device via adb (see
   README.md "On-device validation steps").
7. Run `./verify.sh $RUSTDROID_PREFIX --device` on the device.
8. Report back what fails at runtime — most likely candidates:
   - `libstd`'s assumption about `/proc/self/exe` (Android path differs)
   - `libgetopts` / other small std deps assuming glibc
   - cargo's `home::cargo_home()` returning the wrong path
   - Filesystem permissions under `/data/data/<pkg>/files/`

---

## 5. CI phase addendum (GitHub Actions, 2026-09-01)

The full build moved from a rootless sandbox to `ubuntu-latest` runners
(workflow_dispatch, `.github/workflows/main.yml`). Facts learned from live
runs (not theory):

- **Run #16 (2h23m, main@34993fd)**: the entire stage-2 self-hosting dist
  SUCCEEDED on a 4-vCPU/16GB runner — LLVM (host + cross-aarch64), stage1,
  stage2 rustc, all dist tarballs. Failed only because `stage/dist/` was
  empty: `x.py dist` writes to `stage/rust-src/build/dist/`, and `do_dist`
  never copied. Fixed in `c2ba52e`/`4db13a2`.
- **No cargo tarball without `extended = true`**: plain `x.py dist` yields
  rustc/rust-std/rustc-dev/docs/src only. The original template comment
  claiming "the standard dist set ... is enough" was wrong. Fixed with
  `extended = true` + `tools = ["cargo"]` (`4db13a2`).
- **`-lc++_shared` is required**: rustc's link pulls NDK libc++ symbols
  (`std::__ndk1::*`); the default `-lstdc++` resolves to NDK's empty legacy
  compat stub. Consequence: every dist binary carries
  `DT_NEEDED=libc++_shared.so`, which does not exist in Android's system
  libs — the NDK copy is bundled into `stage/dist/` and must be installed
  to `$RUSTDROID_PREFIX/lib` with `LD_LIBRARY_PATH` exported on-device.
- **ThinLTO off**: the runner's default C++ compiler is GCC; `-flto=thin`
  is Clang-only (`thin-lto = false` in the template).
- **`x.py vendor --no-merge` is not a flag on 1.85.0** — dropped.
- **LLVM build cache**: both `build/*/llvm` trees (~2.0GB zstd) fit inside
  the 10GB Actions cache limit; exact-key hits skip the ~1h50m LLVM build.
  Cache key = `hashFiles(bootstrap.toml.template, build.sh)` with an
  `llvm-` restore-key fallback so doc-only changes never bust it.
- **The openssl-probe patch must live in `patches/post-vendor/`**: it sat
  at the repo root and was silently never attempted (warn-not-fail).
  Moved in `4db13a2`.
- **Run #27 (1h07m, main@7f7d146) — "applied cleanly" is not "compiled
  in"**: the curl verify-locations instrumentation patch
  (`0002-curl-verify-locations-debug.patch`) reported `2 applied cleanly,
  0 drifted`, checksums were resynced, the build went green — yet the
  published cargo binary contained ZERO `RUSTDROID-DEBUG:` strings
  (confirmed by `strings` on the extracted release binary). Root cause:
  the workspace lockfile pins TWO curl-sys versions, and `x.py vendor`
  hands the *unversioned* dir to one of them:
  `Vendoring curl-sys v0.4.74+curl-8.9.0 ... to vendor/curl-sys-0.4.74+curl-8.9.0`
  `Vendoring curl-sys v0.4.78+curl-8.11.0 ... to vendor/curl-sys`
  The patch targeted `vendor/curl-sys/` (= 0.4.78+curl-8.11.0), but cargo
  1.85.0 compiles `curl-sys v0.4.74+curl-8.9.0` from the *versioned* dir
  (CI log: `Compiling curl-sys v0.4.74+curl-8.9.0`). 0001 got lucky
  because openssl-probe has a single version in the lockfile. Fixes:
  (a) 0002 retargeted to `vendor/curl-sys-0.4.74+curl-8.9.0/curl/lib/vtls/openssl.c`;
  (b) post-vendor patch drift is now FATAL, not warn-and-continue;
  (c) per-patch `target crate:` identity log (reads the touched crate's
  `.cargo-checksum.json` `package` field) so future logs show WHICH crate
  version a patch actually landed in;
  (d) `<patch>.postcheck` gate: GREP_PATTERN|PACKAGE_REGEX — the pattern
  must be present in the file of the vendored crate matching the package
  regex (`RUSTDROID-DEBUG: fopen|^curl-sys 0\.4\.74`), keyed on the
  version cargo's lockfile pins, not on the dir the patch happened to
  touch (locally validated: run-#27 scenario correctly rejected, exit 6);
  (e) verify.sh `check_cargo_instrumentation`: end-to-end `strings` gate
  on the shipped cargo binary, auto-disabled when the diagnostic patch is
  removed from the repo.
- Sandbox blockers (no root → no cmake; 4GB RAM; 9GB disk) are all
  irrelevant on Actions runners.

Still pending on-device validation: rustc/cargo actually executing on
Android, `LD_LIBRARY_PATH` resolution of `libc++_shared.so`,
`RUSTDROID_PREFIX` env var read by the patched openssl-probe, and cargo's
`home::cargo_home()` under `/data/data/dev.rustdroid.ide/files/usr`.
