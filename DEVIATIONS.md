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
| `cargo run` of a user project stored on shared storage (`/storage/emulated/0/…`) | ✅→fixed | **On-device confirmed (2026-09-05):** shared storage is mounted `noexec`, so cargo builds fine but dies spawning `target/debug/<bin>` with `Permission denied (os error 13)`. App-side fix (no toolchain change): `ProcEnv.redirectedTargetDir` exports `CARGO_TARGET_DIR=files/build/<sha16(projectPath)>` for projects outside app data — build output joins the toolchain on exec-allowed app-data ground (targetSdk 28), internal projects keep their in-tree `target/`. See android/README.md → "Running builds from external folders". |

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
- **Run #28 (main@6c84d99) — the postcheck gate itself crashed (self-inflicted,
  cheap failure ~8min in, LLVM cache preserved)**: the retargeted 0002 patch
  DID land correctly this time (`post-vendor patches summary: 2 applied
  cleanly, 0 drifted` + `resynced 664 entries in
  curl-sys-0.4.74+curl-8.9.0/.cargo-checksum.json`), and the build died only
  at the new postcheck with `TypeError: expected string or bytes-like object,
  got 'NoneType'`. Root cause: postcheck v1 scanned ALL vendored
  `.cargo-checksum.json` `"package"` fields assuming `"name version
  (registry+…)"` strings. In x.py-produced vendor trees that field is NOT
  reliable: at least one vendored crate carries `"package": null` (→
  `re.search(None)` crash), and the crates we care about carry a BARE sha256
  hex instead — run #28's `target crate:` probe printed `8af10b98…` for
  curl-sys-0.4.74+curl-8.9.0 and `ff011a30…` for openssl-probe — so the
  `^curl-sys 0\.4\.74` regex could never match even without the crash.
  Additionally the local dry-run twin (`postcheck_dryrun.py`) validated
  dir-NAME matching while build.sh shipped package-FIELD matching — the
  twin never exercised the code that actually ran. Fixes (defense in depth,
  v2):
  (a) postcheck v2 is DIR-ANCHORED: spec line is `GREP_PATTERN|CRATE_DIR`
  (`RUSTDROID-DEBUG: fopen|curl-sys-0.4.74+curl-8.9.0`); the patch's first
  `+++ b/` target must live under `vendor/<CRATE_DIR>/` with the pattern
  present in the touched file — no JSON field scanning at all, nothing left
  to crash on "package": null;
  (b) the per-patch identity probe now prints
  `dir=<crate-dir> package=<value-or-uninformative>` — the directory name is
  the authoritative identity;
  (c) the dry-run twin and the build.sh heredoc are the same algorithm again;
  (d) NEW end-to-end gate inside `do_dist`: while
  `patches/post-vendor/0002-*.patch` exists, ANY `*stage2/bin/cargo` x.py
  produced must contain `RUSTDROID-DEBUG:` strings (grep -c, not grep -q —
  under `set -o pipefail` a `-q` early-match SIGPIPE (141) would misread a
  hit as a miss) or the build fails BEFORE artifacts reach publish. This
  closes the gap that let run #27 ship: verify.sh's equivalent check runs on
  extracted artifacts and was never wired into CI.
- **Run #29 (33860610050, main@9e8cb5d) — postcheck v2 shipped with its
  spec file missing the trailing newline; `read` + `set -e` killed the
  build SILENTLY (self-inflicted, cheap failure ~8min in, LLVM cache
  preserved)**. Forensics: the run got past everything that killed run #28
  — both patches applied cleanly to the right crate dirs
  (`dir=curl-sys-0.4.74+curl-8.9.0 package=8af10b98…`,
  `dir=openssl-probe package=ff011a30…`), 664+7 checksum entries resynced —
  and then died 0.01s later with `exit code 1` and ZERO further output: no
  postcheck verdict, no fail() message, no traceback. Root cause: the v2
  rewrite of `0002-….patch.postcheck` (9e8cb5d) dropped the file's final
  newline (`…+curl-8.9.0` with no `\n`; 6c84d99's v1 file had it). Bash's
  `read` returns 1 on EOF-without-newline even though it still populates
  the variables, and the unguarded
  `IFS='|' read -r pc_pattern pc_dir < <(head -n 1 "$pc")` under
  `set -euo pipefail` therefore aborted the whole script BEFORE the
  postcheck body could run or report anything. Fixes (v3):
  (a) the spec file ends with `\n` again;
  (b) the read is now guarded with `|| true` (fields are populated —
  losing the newline must be harmless, not fatal), stray `\r` is
  stripped (CRLF-edited spec files), and an empty pattern/dir is caught
  by an explicit validation that names the file and the expected
  `GREP_PATTERN|CRATE_DIR` format;
  (c) regression-tested by a harness that sed-extracts the EXACT block
  from build.sh (marker-anchored, so the test can never drift from the
  shipped code — the run #28 twin-drift lesson): 8 scenarios — happy
  path, run #29's exact newline-less spec (now passes), CRLF spec, wrong
  crate dir (exit 6), pattern absent (exit 6), file missing (exit 5),
  malformed spec (named error), and a reproduction of the pre-fix silent
  exit-1 (old code + newline-less spec → no output, exit 1) proving the
  diagnosis. Harness: `/home/z/my-project/scripts/test_postcheck.sh`
  (not committed — dev-only).
- **Run #30 (33864029660, main@f46bc60) — GREEN, instrumentation verified
  on-device, and THE ACTUAL TLS BUG ROOT-CAUSED**. The postcheck v3 fix
  held ("postcheck OK: 0002-… -> vendor/curl-sys-0.4.74+curl-8.9.0/curl/
  lib/vtls/openssl.c"), verify.sh passed 105/105 (including the strings
  gate), and the published bundle's cargo binary carried all 4
  RUSTDROID-DEBUG format strings (independently re-verified by extracting
  the release asset). On-device output of `cargo fetch`:
  `fopen(…/.ssl/cacert.pem) OK size=188900 first=32: 23 23 0a 23 23 …`
  (byte-perfect Mozilla bundle header — file layer CLEAN, SELinux/app
  write-path ruled out) followed by exactly ONE OpenSSL error:
  `error:0B084002:x509 certificate routines:X509_load_cert_crl_file:system
  lib`. Cross-referenced against openssl-1.1.1w sources: that error is
  raised in exactly one place — crypto/x509/by_file.c:199, when
  `BIO_new_file(file, "r")` returns NULL. And a BIO fopen failure would
  queue SYS+BIO errors BEFORE it (3 total) — we saw 1. The single-error
  signature points at the `OPENSSL_NO_STDIO` build of bss_file.c, where
  `BIO_new_file()` is `return NULL;` with NO error queued. Root cause
  CONFIRMED in openssl-src 111.28.2+1.1.1w's src/lib.rs: it passes
  `no-stdio` to OpenSSL's Configure for EVERY android target (workaround
  for openssl-src-rs#13, a 2016 OpenSSL-1.0.x/old-NDK issue). With the
  file BIO compiled out, EVERY X509_STORE_load_locations() fails —
  path-independent, pre-network, ~0.3s, exactly the observed error-77
  pattern; curl's own stdio fopen works fine, which is why the fopen
  probe succeeded. The CApath/SSL_CERT_DIR theory from the older README
  troubleshooting is DISPROVEN (CApath was already `none`). Fixes
  (defense in depth):
  (a) NEW post-vendor patch 0003-openssl-src-enable-stdio.patch: removes
      the no-stdio block from vendor/openssl-src/src/lib.rs so the real
      bss_file.c (plain bionic fopen/fread) is compiled — the root fix;
      anchored by its own .postcheck (pattern
      "RUSTDROID-FIX: the `no-stdio` block" vs crate dir openssl-src);
  (b) 0002 upgraded: populate_x509_store now probes BIO_new_file directly
      (runtime proof of the root fix — prints `RUSTDROID-DEBUG:
      BIO_new_file=<ptr>`; NULL means the stub is still there) and on
      X509_STORE_load_locations failure falls back to
      rustdroid_load_ca_fallback(): C-stdio read → BIO_new_mem_buf →
      PEM_X509_INFO_read_bio → X509_STORE_add_cert, i.e. a working CA
      load even on a no-stdio OpenSSL. The fallback body was
      compile-checked AND live-tested on the host against a real OpenSSL
      (150 certs loaded from the system bundle through the exact code
      path). Forward+reverse git-apply validated against pristine
      curl-8.9.0 sources;
  (c) regression harness extended to 10 scenarios (0003 marker absent /
      wrong crate dir now covered) — 10/10 pass.
- Sandbox blockers (no root → no cmake; 4GB RAM; 9GB disk) are all
  irrelevant on Actions runners.

Still pending on-device validation: rustc/cargo actually executing on
Android, `LD_LIBRARY_PATH` resolution of `libc++_shared.so`,
`RUSTDROID_PREFIX` env var read by the patched openssl-probe, and cargo's
`home::cargo_home()` under `/data/data/dev.rustdroid.ide/files/usr`.

## 6. App reliability hardening (external code review, 2026-09-06)

An evidence-based review of the Android app (~18 findings) landed in v0.1.3.
All P0/P1/P2 items fixed and unit-tested (128 JVM tests, 0 failures):

- **P0 install ordering**: `installWith` uninstalled the prefix BEFORE the
  download — any fetch failure destroyed a working toolchain. Now: fetch to
  cache first → free-space preflight → extract into `files/usr.new` staging →
  `ToolchainSwap` (prefix→usr.old→staging→prefix, restore-on-failure). A
  failed download or extraction now costs nothing but the cache zip.
- **P0 writeAtomic**: delete-then-rename had a crash window losing user
  source files. Now NIO `ATOMIC_MOVE|REPLACE_EXISTING` (single `rename(2)`),
  with graceful fallbacks.
- **P1 resume validation**: downloads persist ETag+size in a `.part.meta`
  sidecar; resumes send `If-Range`. Changed assets (200 answer or drifted
  Content-Range total) restart cleanly in the same attempt instead of
  failing checksums for 4 rounds. Resume re-hash reports progress.
- **P1 extraction links**: hard/symlinks resolved in a second pass after all
  files exist (tar ordering is not guaranteed); unresolvable targets fail
  loud; symlink escapes rejected (`Fs.requireInside` canonical containment
  on every write — closes the imported-zip symlink-escape vector); no more
  empty-file placeholders for failed symlinks.
- **P1 ANR**: `ToolchainManager.uninstall()` was `runBlocking` on the mutex
  from a click handler → suspend + Dispatchers.IO.
- **P1 console flood**: `CARGO_HTTP_DEBUG` (libcurl verbose) was
  unconditional in shipping builds → opt-in parameter, default off.
- **P2 console jank**: ConsoleBuffer copied the full 2000-line list per
  appended line → ArrayDeque + 100 ms rate-limited StateFlow publication +
  `flush()`; overflow counter now atomic.
- **P2 orphaned builds**: cancelling killed only cargo (no process groups in
  Java) → `/proc` walk (`ProcTree`, pure JVM) SIGKILLs all descendants via
  `android.os.Process.sendSignal`.
- **P2 Home listing**: `latestMtime` walked the whole project tree including
  `target/` (tens of thousands of files) and could loop forever on symlink
  cycles → skips target/.git/hidden, depth cap, canonical visited-set.
- **P2 misc**: import progress modulo→threshold (progress no longer freezes
  after a short SAF read); free-space preflights before download+extraction;
  dead `ToolchainToolchainProgress` removed; Main-dispatched scope → Default;
  base OkHttp callTimeout 5 min→60 s (bulk callers opt out explicitly).
- **P3**: insecure-TLS marker now prints a console warning every affected
  run; CI builds assembleRelease too (caught a real lint-vital blocker:
  Play-policy `ExpiredTargetSdkVersion` — disabled with justification);
  real-bundle test skip is loud; `applyPosixMode` and `resolveChild` doc
  their deliberate simplifications.

Not addressed (documented as Known v1 limits): instrumented test tier
(device/emulator) — the exec-from-app-data premise still rests on manual
validation; hardlink-restore edge in ToolchainSwap is covered for the
missing-staging case only.

## 7. Crash/cancellation hardening (independent third-pass audit, 2026-09-07)

A follow-up audit focused on process-tree termination, transactional
installs, cancellation propagation, and CI determinism. Landed in v0.1.4
(179 JVM tests, 0 failures):

- **P0 PID-reuse-safe tree kill**: `ProcTree` now tracks every process as
  `ProcessId(pid, /proc/<pid>/stat field-22 start time)`, not a bare PID.
  `terminateTree` is the full sequence: capture root identity → snapshot
  descendants (identity-bearing) → SIGSTOP each still-matching one →
  terminate the root → re-discover late spawns only while the root PID
  still belongs to the original process → bounded SIGKILL sweeps that
  revalidate identity before EVERY signal. A reused PID is never signaled;
  the root is never its own descendant; processes vanishing mid-walk drop
  out; missing `/proc` degrades to destroying the direct child. The old
  `descendantPids` remains as a read-only view.
- **P1 download restart discipline**: `ArtifactDownloader` restarts
  (HTTP 416, If-Range mismatch, drifted Content-Range total, unreadable
  resume prefix) stay INSIDE the attempt — no network-retry budget burned —
  and are themselves bounded (`MAX_RESTARTS_PER_ATTEMPT`). `discardPart`
  reports success; an undeletable partial throws a local-failure
  IOException instead of looping forever. `attemptLoop` is parameterized
  over the HTTP exchange, so the whole discipline is tested against
  scripted responses (no sockets).
- **P1 install as a durable transaction**: `ToolchainTransaction` writes
  an EXTERNAL marker (`files/install-pending.txt`, atomic+fsync) BEFORE
  the destructive swap. `ToolchainSwap.swap` retains the old install in
  `usr.old` until verification passes; `commit` deletes it at READY,
  `rollback` restores it on verification failure — restoring the previous
  known-good toolchain when one exists. Every rename/delete is checked;
  a failed rollback is attached to the original exception via
  `addSuppressed` (both failures stay visible). Startup recovery
  (`recoverInterruptedInstall`, pure decision table
  `recoveryAction` + checked execution) reconciles crash states at every
  boundary: keep-verified / restore-aside / discard. The ready marker and
  every atomic write are now fsync'd before rename (power-loss durable).
  `installWith`'s stage relabeling no longer overwrites a specific
  "verification" failure with a generic "install" one.
- **P1 cancellation propagation**: `ToolchainManager.installWith` and
  `installFromUri` rethrow `CancellationException` before their generic
  catch (a cancelled install is not a "failure"); `ToolchainVerifier`'s
  per-check wrapper does the same, so cancellation from the foreground
  service scope unwinds through verifier → runner → subprocess teardown
  instead of being recorded as a failed check.
- **P1/P2 verifier async**: `verify()` is now `suspend`; the four nested
  `runBlocking` bridges are gone (direct `runner.probe`/`runner.run`
  calls); `CargoRunner` is `open` so tests can inject hanging runners and
  prove cancellation propagation end-to-end.
- **P2 log/console concurrency**: `ToolchainManager.logTail` is private;
  readers get an immutable `logTailSnapshot()` (a live ArrayDeque iterated
  from the UI thread was a CME waiting to happen). `ConsoleBuffer`
  publishes trailing burst lines through a single-shot job owned by the
  INJECTED scope (viewModelScope) — no leaked global scope, cancelled by
  explicit flush; `lastPublishMs` access is fully synchronized.
- **P2 CA bundle mirror**: probe-mirror validity is now digest-based
  (same-size corruption no longer survives); the copy is atomic+fsync
  via `Fs.writeAtomic` and re-verified afterwards (a copy that still does
  not verify is deleted — a corrupt mirror is worse than none). The
  system-store fallback keeps its PEM-in-hashed-CApath parse with the
  layout documented; the APK asset stays the deterministic primary.
- **P2 CI cache correctness**: the LLVM build-tree cache key now covers
  every input that can affect the tree (bootstrap.toml.template, build.sh,
  env.sh — which pins RUST_TAG/NDK, patches/**, shims/**); the broad
  `llvm-` restore-key fallback is REMOVED (a prefix-match restore of an
  incompatible tree is untrusted-by-default; ccache remains the
  content-addressed rebuild insurance). ccache and the tree cache stay
  logically separate with the safety argument documented inline.
- **P2 verify.sh classification**: warnings are now classified. Hard
  failures: unexpected absolute RUNPATH (linker-searched at runtime —
  untrusted by default), unknown PT_INTERP, missing libm stub, missing
  rust-lld, all smoke-compile failures. Documented warnings: no PT_INTERP
  (shared objects — normal), symlinked rust-lld (repacking concern),
  missing libdl stub (dlopen-only), rustc missing the prefix string
  (env vars still route resolution). verify.sh also prints an explicit
  RUNTIME STATUS footer: without `--device` it performed static + LINK
  verification only — no Android execution — and release notes say so
  (the app's install-time ToolchainVerifier smoke test is the on-device
  runtime gate; CI does not run an emulator, by cost/reliability choice).
- **P3 service dedupe**: `ToolchainInstallService` keeps ONE active
  operation job; duplicate starts (same or different action) are ignored
  with a log line and documented policy; only the active job's terminal
  block calls `stopSelf()`, so a redundant start can never stop the
  service out from under a running operation. START_NOT_STICKY unchanged.
- **P3 NDK checksum**: the pinned-archive SHA1 (Google's official
  checksum format for NDK zips) is REQUIRED by default; the workflow sets
  `NDK_SHA1_REQUIRED=1` explicitly; opting out (`=0`) is explicit,
  logged, and reserved for a verified Google rotation. Release builds
  transitively cannot continue past a mismatch (they consume main.yml
  artifacts, which fail the build).
- **P3 probe/stream cleanup**: `CargoRunner.probe` closes reader and all
  three process streams in `finally`; the reader coroutine is joined
  before returning; the timeout path destroys the subprocess.

Remaining known limits: no instrumented-test tier (the app's install-time
smoke test remains the on-device runtime gate); `ToolchainVerifier`'s
startup recovery runs best-effort in the background (a transient
NotInstalled state can flip to Ready within milliseconds of launch);
UI-layer `catch (Exception)` blocks (Home/Deps/Editor VMs) intentionally
keep callback semantics — they hold no subprocesses and die with their
viewModelScope.

## 8. Fourth-pass targeted fixes (follow-up audit, 2026-09-08)

Five targeted correctness fixes over v0.1.4 — no broad rewrite; every
already-fixed area re-verified intact (190 JVM tests, 0 failures).

- **P0 crash-safe install-pending marker**: v0.1.4 could clear
  `install-pending.txt` even when the rollback or the startup recovery
  itself FAILED — a failed restore then looked like a clean state and no
  later startup could retry it. The invariant is now enforced in ONE
  place: `ToolchainTransaction.rollbackFailedInstall` (install-time
  rollback; returns the failure so the caller attaches it to the original
  exception via `addSuppressed` — both stay visible) and
  `ToolchainTransaction.runRecovery` (startup execution of the pure
  decision table; returns whether a known safe final state was reached).
  The marker is cleared ONLY after: verified READY, a completed rollback,
  a completed recovery, a fresh-install failure with nothing to restore,
  or an explicit uninstall. A failed rollback/recovery leaves the marker
  on disk BY DESIGN — it is the retry record; the next startup reads it
  and re-attempts the restore. Regression tests assert the MARKER's
  existence/non-existence directly (not just exceptions): rollback
  succeeds → marker gone; rollback fails → marker remains; recovery
  fails → marker remains; a later startup retries and then clears it.
- **P0 unknown root identity never authorizes late descendant
  rediscovery**: `ProcTree.terminateTree`'s late re-discovery condition
  was effectively `rootId == null || stillMatches(...)` — when the root's
  identity could never be established (its `/proc/<pid>/stat` was already
  unreadable), a reused root PID with fresh children would be walked and
  those innocent processes SIGKILLed. The condition is now the
  conservative `rootId != null && stillMatches(...)`: known + matching →
  allowed; known + reused → stop; unknown → no late traversal at all.
  Everything else (identity snapshots, per-signal revalidation, root
  exclusion, bounded sweeps, /proc-degradation) is unchanged. The
  regression test plants a reused root PID with children after the
  original identity became unavailable and asserts ZERO signals.
- **P1 repeated-restart downloader test made real**: the old test
  exercised exactly one restart (416 → 200 → checksum mismatch). Worse,
  the production restart ceiling was unreachable dead code: every restart
  discards the partial and a fresh request could never trigger another
  restart, so a corrupt-body server escaped the ceiling by burning OUTER
  retries (4 full downloads + 11 s backoff). A checksum mismatch is now
  a bounded clean restart INSIDE the attempt (the partial is still
  discarded — corrupt data is never resumed), and exhausting
  `MAX_RESTARTS_PER_ATTEMPT` throws `RestartBudgetExceededException`,
  which `downloadBlocking` treats as TERMINAL (re-attempting would
  re-download the identical corrupt body). The rewritten test drives
  restart → restart → restart → refused at the exact configured limit
  (4 exchanges, all fresh full-body requests), asserts the bounded
  failure message with the checksum cause preserved, and a second test
  drives `downloadBlocking` itself through an interceptor-scripted
  client (no sockets) proving the outer loop never re-runs the exhausted
  experiment. All prior downloader tests (416 recovery, corrupted
  prefix, asset drift, truncated body, failed discard, successful
  resume, zero-byte download) are preserved and green.
- **P1 probe drains stderr concurrently**: `CargoRunner.probe` read only
  stdout; a child writing more stderr than the ~64 KB pipe buffer blocks
  in write() forever, the waitFor times out and a healthy toolchain
  probes as dead. Both pipes are now consumed concurrently (stdout
  collected, stderr drained/discarded), both pumps are joined with a
  bound, and the finally-block still closes all three streams. The
  regression test runs a real `/bin/sh` child that writes ~1.2 MB of
  stderr before its single stdout line — it FAILS against the old code
  (probe returns "") and passes with the drain (host-JVM subprocess
  tests are reliable here: unit tests run on the developer/CI machine,
  not the Android runtime; hosts without POSIX sh skip via assumeTrue).
- **P3 duplicate service notification**: `ToolchainInstallService` called
  `startInForeground(reverify)` BEFORE the active-job check, so a
  duplicate re-verify start during an install relabeled the notification
  to "Re-verifying…" mid-install. The check now precedes any
  foreground/notification change; the duplicate still answers Android's
  `startForegroundService` contract (every such start must be followed
  by `startForeground`, else "did not then call Service.startForeground"
  — the crash the naive fix would trade in) by re-asserting the CURRENT
  operation's state. Job cancellation/cleanup behavior unchanged.

## 9. Zombie sweep fix + Phase 4 (terminal) architecture pass (2026-09-09)

Landed together, ahead of any terminal implementation code:

- **`ProcTree.terminateTree` zombie-state exclusion (live latency bug in
  `main`, ordered fixed before Phase 3/4 work)**: the final sweep counted
  a captured process as "remaining" whenever `stillMatches` held — but a
  killed descendant that was reparented to init when the root died first
  sits in `/proc` as a state-`Z` zombie pending reap, holding its PID and
  start-time identity, so `remaining` NEVER emptied: the early-return
  never fired and every cancellation burned the full `maxSweeps` budget
  re-signaling corpses that cannot respond to SIGKILL. Fix: the sweep now
  excludes processes whose `/proc/<pid>/stat` state character (first
  token after the last `)`) is `Z` — new `ProcTree.isZombie`, same
  last-`)` parsing discipline as `startTimeOf`. Identity semantics are
  untouched: a zombie still matches its identity (PID-reuse defense
  intact — the exclusion only says "dead is dead, stop signaling"). Three
  regression tests: state-character parsing (incl. zombie-keeps-identity),
  a killed-then-zombie descendant is SIGKILLed exactly once and the
  early-return fires on the next sweep (proven to FAIL on the pre-fix
  code), and a live stubborn descendant still receives the full bounded
  sweep (guards against over-exclusion). Suite: 193 JVM tests, 0 failures.
- **`android.yml` push trigger — false alarm, no fix needed**: an initial
  read of the workflow showed `branches: ain]`, but byte-level inspection
  (`od -c`) proved the file has always contained `branches: [main]` — the
  earlier rendering was the same display artifact that eats `[m`
  sequences. Recorded here so nobody re-reports it; no change was made.
- **Phase 4 architecture plan** (`docs/phase3-terminal-architecture.md`):
  pre-implementation gate document for the native-terminal work —
  vendoring terminal-emulator/terminal-view @ termux-app `3b66f87`
  (Apache-2.0, `termux-shared` explicitly excluded with a no-transitive-
  pull proof), the three upstream JNI defects confirmed at that commit
  and their fix plan (plus regression-test design), an async-signal-safe
  fork→exec restructure, session/process-group teardown (NOT a ProcTree
  extension — new `sessionMembers` keyed on stat field 6), BusyBox 1.38.0
  static build via the Termux recipe pattern with GPL-2.0 source-release
  obligations, generalized bundle distribution, and the CI matrix. No
  implementation code yet — the plan goes to independent review first,
  per the task brief. Companion inventory: `THIRD_PARTY.md` (new).
  (The v1 plan's 1.38.0 pin was superseded by the 1.36.1 re-pin in
  §10 below.)

Version 0.1.6 (versionCode 7).

## 10. Phase 4 plan v2 — adversarial-review response (2026-09-10)

The independent adversarial review of the Phase 4 terminal architecture
plan returned **APPROVE WITH CONDITIONS** (`docs/Report.txt`). All
findings accepted, all conditions adopted; the plan was revised in place
(v2, change log in plan §12.3) with the point-by-point response in
`docs/phase3-review-response.md`. The corrections that change future
*implementation* behavior (not just plan text) are recorded here because
they are deviations from what §9 above originally specified:

- **Teardown semantics (plan §5.2/§5.3)**: `setsid(2)` creates a new
  session — the v1 plan's "a setsid'd daemon keeps `sid == shellPid`"
  claim inverted the syscall. Teardown discovery is now the union of
  three sets (session ∪ ppid-descendants ∪ `tty_nr` match on the pts
  device number), the guarantee is scoped to *attached* processes
  (deliberately detached survivors are by design), and the teardown
  order is close-master → grace → freeze → kill → SIGCONT survivors,
  with the output reader joined before any master-fd close.
- **`close_range` is out of v0 entirely** (seccomp/SIGSYS risk in the
  forked child — device-dependent silent death); the child closes fds
  from the parent's pre-scanned list only. Never probe `close_range`
  from the app process; the only safe probe shape is a throwaway forked
  child (plan §4.4).
- **Child path resets signal dispositions to SIG_DFL** (signals 1..64)
  before `execve` — ART ignores SIGPIPE and SIG_IGN survives exec;
  without the reset, `yes | head -1` hangs forever.
- **BusyBox re-pinned 1.38.0 (upstream-unstable) → 1.36.1 (stable)**,
  checksum from busybox.net's own `.sha256` (recorded in THIRD_PARTY.md);
  network client applets dropped alongside servers (static-bionic NSS).
- **Manifest v2 symlinks** are guarded by `Fs.resolveChild` +
  `Fs.requireInside` with a traversal-rejection regression test — no
  blind reuse of the extractor's link pass.

**targetSdk-28 debt list (additions, review P3-10)**: `TerminalService`
ships in v0 with `foregroundServiceType=dataSync` — correct at
targetSdk 28 (type enforcement off), wrong from API 34 (type
appropriateness enforced) and worse at API 35 (~6 h/day dataSync cap).
The terminal's eventual type is `specialUse` with a declared
justification. This joins the existing targetSdk-28 debt items
(WRITE_EXTERNAL_STORAGE model, etc.) as a *known migration blocker*,
recorded so it is not discovered late.

**Security notes (review Part 2, plan §8.5)**: a terminal is strictly
more dangerous than a build button — it hands the user, and any script
they paste, interactive execution as the app's UID with `INTERNET` and
`WRITE_EXTERNAL_STORAGE`, full read/write of the toolchain prefix, the
CA bundle, and the **insecure-tls marker that disables cargo
certificate verification globally** (a pasted `curl | sh` runs with all
of it). No new permission is requested for the terminal; the sandbox is
unchanged; the honest mitigation is this documentation, mirrored in the
README known-limits at implementation time.

The "193 JVM tests, 0 failures" count in §9 is re-verified at each
implementation step; the running count lives in this section's
implementation log (appended as terminal-layer tests land).

### Implementation log (§12.2 order, each step lands green)

**Step 1 — vendoring (2026-09-10)**:

- `terminal-emulator` + `terminal-view` vendored at pin
  `3b66f8799635a4dba4a206563048ff0e6792c487` (sparse-checkout
  blob-partial clone; `diff -r` of both src trees against the pin:
  empty — verbatim). **No source patches at this step.**
- Pin claims re-verified at vendoring time (third independent check,
  matching plan §2 and review-response §1): no `NOTICE*` file anywhere
  in the tree (`git ls-tree -r`); `rg "termux\.shared|TermuxConstants"`
  over both modules → zero matches; 19 test files (18 classes +
  `TerminalTestCase`); `unitTests.returnDefaultValues = true` in
  upstream `terminal-emulator/build.gradle`; deps exactly
  `androidx.annotation:annotation:1.9.0` + `junit:junit:4.13.2`;
  `termux.c` last touched upstream by `438cd73` (author 2022-11-16,
  committer 2024-09-26).
- New build scripts (ours): Kotlin DSL, `com.android.library`;
  `unitTests.isReturnDefaultValues = true` in BOTH modules (P1-5);
  Java 17 `compileOptions`; `compileSdk 35` / `minSdk 24` / `ndkVersion
  27.2.12479018` (r27c); `abiFilters` arm64-v8a only; upstream cFlags
  carried verbatim (incl. `-Werror`, with the documented in-build-script
  fallback if r27c's clang warns); maven-publish dropped. Full
  file-by-file divergence record: THIRD_PARTY.md.
- `settings.gradle.kts` includes `:terminal-emulator` / `:terminal-view`;
  app gains `implementation(project(…))` on both (terminal-view also
  `api`s terminal-emulator upstream-style); Gradle cache key extended
  to the new build scripts.
- App `proguard-rules.pro`: JNI keep rules for
  `com.termux.terminal.JNI` (P3-11 — inert while `isMinifyEnabled =
  false`, present so a future minify flip cannot ship a broken
  release).
- CI (`android.yml`): vendored-module path triggers; explicit
  `:terminal-emulator:testDebugUnitTest` step — the step-1 gate
  (upstream suite green before any patch lands).
- App-level `ndk { abiFilters += "arm64-v8a" }` added (beyond the
  module-level filters): without it the APK still packaged 3 extra ABIs
  of `androidx.graphics.path.so` (Compose AAR) — "arm64-v8a only,
  everywhere" per the plan; graphics.path degrades to its Java fallback
  on non-arm64 hosts. `terminal-view`'s build script carries the filter
  too (inert — no native code there — but symmetric and future-proof,
  plan §4.1).
- Local verification (JDK 17 Temurin, AGP 8.7.3, NDK r27c
  auto-installed by AGP): `:terminal-emulator:testDebugUnitTest`
  **145 tests, 0 failures**; `:app:testDebugUnitTest` **193 tests,
  0 failures** (1 skipped — the pre-existing opt-in bundle test);
  `:app:assembleDebug` + `:app:assembleRelease` green; both APKs
  contain exactly `lib/arm64-v8a/{libtermux.so,
  androidx.graphics.path.so}` — one ABI, both vendored modules
  packaged. `termux.c` compiles under the verbatim upstream cFlags
  (incl. `-Werror`) with r27c clang — no fallback needed.
- `LICENSES/terminal-{emulator,view}-Apache-2.0.txt` added (canonical
  text, provenance headers; upstream namespaces `com.termux.emulator` /
  `com.termux.view` preserved; Java packages `com.termux.terminal` /
  `com.termux.view` untouched — attribution-preserving vendoring).
- Still **upstream-verbatim in v0 at this step**: `Android.mk`
  `LOCAL_MODULE libtermux` and `JNI.java`
  `System.loadLibrary("termux")` — the `librustdroidpty` rename is
  step 2, by plan §12.2 ordering.

**Step 2 — JNI defect fixes + fork-window restructure + host harness
(2026-09-10)**:

- `termux.c` rewritten per plan §4.3/§4.4/§4.5 (full patch set in the
  file's header comment + THIRD_PARTY.md's file-by-file table):
  defect 1 (release pairing), defect 2 (fd leaks on all three failure
  paths), defect 3 (argv/envp early-return leaks, single-exit cleanup
  with zeroed arrays so partially-built marshalling is safely
  releasable), async-signal-safe child path `rd_child_exec` (between
  `RD_CHILD_PATH` markers, whitelist-grepped in CI), parent-side
  pre-scan of `/proc/self/fd`, parent-side executable resolution
  (absolute / cwd-anchored / PATH search; loud failure, never a silent
  child death), execve with wholesale envp, `_exit(127)` on exec
  failure, `RD_FORK`/`RD_GRANTPT`/`RD_MALLOC` test seams (libc
  defaults in production builds).
- **`close_range` is nowhere in the child path** — the pre-scanned
  close-list is the only fd-cleanup mechanism (P0-2, deliberate).
  The never-probe-from-the-parent trap is recorded in the plan.
- Library renamed: `Android.mk` `LOCAL_MODULE librustdroidpty`,
  `JNI.java` `System.loadLibrary("rustdroidpty")`; Java package and
  JNI symbol names unchanged. APK verified to contain exactly
  `lib/arm64-v8a/librustdroidpty.so` (no `libtermux.so`).
- `sendSignalToProcessGroup(pgid, sig)` → `killpg(2)` added to
  `termux.c` + `JNI.java` (plan §4.5); throws RuntimeException with
  errno on failure; Kotlin call sites will wrap it in `runCatching`
  (condition 8) when the controller lands in step 3.
- **Host-side JNI test harness** (`android/terminal-emulator/
  host-tests/`, plan §6.2): fake `jni.h` + mock JNIEnv with call
  recording and injectable failures; ASan+LSan test binary (9 checks);
  allocation-interposer binary (`--wrap=malloc/.../execve/_exit`,
  no sanitizers — ASan cannot coexist with `--wrap=malloc`);
  `sigprobe` helper; `check_child_path.sh` whitelist grep (forbidden
  identifier list from plan §4.4 incl. `syscall`/`close_range`; also
  enforces the child path stays ≤120 lines).
- **Regression pinning (the repo's established standard — each test
  proven to fail on the unfixed code)**: fixed code = 9 checks, 0
  failures; upstream `termux.c` @ the pin + only the 4 mechanical
  seam substitutions (fork/grantpt/malloc routing — no fixes) =
  **8 of 9 checks failing**, exactly the defect tests:
  release-pairing violation (defect 1), fd-leak `closed=0` on the
  fork/grantpt/critical paths (defect 2), LSan-visible marshalling
  leaks on both injection tests (defect 3), SIGPIPE observed as
  SIG_IGN (no disposition reset), happy-path pairing violation.
  `test_fd_count_200_cycles` passes on both — it is a guard for the
  C-layer fd discipline, not a defect discriminator (as documented in
  tests.c).
- **Honest scope statements** (review Part 2): the malloc interposer
  sees only OUR compilation units — glibc-internal allocations
  (opendir's buffer, in-libc asprintf) are invisible to link-time
  wrapping, so the fork-window zero-allocation test is a guard on OUR
  code, necessary-not-sufficient; it is no Android-seccomp oracle
  (only the §6.6 device matrix is). The harness is glibc/x86.
- A pty-lifetime subtlety surfaced while testing: closing the master
  fd before the child has opened the slave destroys the pty
  (slave-open returns ENOENT — verified empirically). The parent
  always holds the master on success paths, so this only manifests on
  failure paths whose child is doomed anyway; tests reap children
  before closing the master. Recorded because it explains stderr
  noise in failure-injection tests, and because it is exactly the
  reader-join-before-close discipline (P1-4) in miniature.
- Local verification: harness fully green (9/9 + alloc window
  0-allocations/exit-127 + whitelist grep clean over 92 lines);
  `:app:assembleDebug` green with `librustdroidpty.so` packaged;
  vendored JVM suite + app suite green; `termux.c` compiles clean
  under gcc (host, `-D_GNU_SOURCE` for glibc POSIX visibility) and
  NDK r27c clang aarch64 (verbatim upstream cFlags incl. `-Werror`).

**Step 3 — UTF-8 split test + session discovery + teardown controller
(2026-09-10)**:

- **UTF-8 split-across-reads regression test** (plan §6.3): new
  `Utf8SplitAcrossReadsTest` in the vendored module (upstream's 19 test
  files untouched). Every split offset of a mixed 2/3/4-byte string,
  plus 3-way splits inside every multi-byte sequence at each internal
  boundary pair; asserts rendered row AND decoder intermediate state
  (`mUtf8ToFollow == 0`, pending buffer empty — via reflection, the
  fields are private) so a decoder regression is distinguishable from
  a rendering change (review Part 6). Corrupted continuation byte —
  whole or split — yields exactly one U+FFFD; truncated sequences hold
  state without rendering. Suite: **150 tests, 0 failures**.
- **`ProcTree` session discovery** (plan §5.2): `sessionMembers`
  (stat field 6), `ttyMembers` (field 7), `unionMembers`
  (session ∪ descendants ∪ tty_nr, dedup by `ProcessId`); stat field
  parsing unified through `longFieldOf` with the existing last-`)`
  discipline. The kernel-semantics-pinning test (review P0-1): a
  process with `sid == ownPid` is NOT a session member; a
  `setsid`'d-but-tty-holding escapee IS caught by the union via
  tty_nr; a fully detached escapee is invisible to all three sets and
  asserted to be (survives by design). The shell itself IS a member of
  its own session (teardown freezes/kills it too).
- **`TerminalSessionController`** (plan §5.3, exact v2 sequence):
  quiesce (caller contract) → stop reader → bounded join (P1-4) →
  close master (kernel-delivered SIGHUP, P1-3) → bounded 300 ms grace
  → union snapshot (identity map cached for the teardown) → SIGSTOP
  freeze (identity revalidation before every signal) → shell-group
  `killpg(SIGKILL)` (a pgrp cannot span sessions, so it can only hit
  session members) → bounded zombie-excluded sweeps with early return
  → SIGCONT freeze survivors after budget (condition 8). Every
  injected `signal`/`killpg`/`closeMaster`/`stopReader`/`joinReader`
  call wrapped in `runCatching` (condition 8). The kill phase is the
  sweep loop itself (first sweep = kill pass — the same shape as
  `terminateTree`); no separate initial per-PID pass.
- **Retroactive `runCatching` guards on `ProcTree.terminateTree`**
  (condition 8): every `signal`/`destroyRoot` call site now guarded.
- **pts device number out-param** (plan §5.2): `createSubprocess`
  computes `st_rdev` of the pts slave in the parent (open O_NOCTTY +
  fstat) and returns it via a new `int[1]` array (separate JNI
  critical section — two simultaneous critical sections are not
  supported by the JNI contract); `TerminalSession` stores/exposes it
  (`getPtsDevice()`, recorded in THIRD_PARTY.md). **Subtlety found and
  fixed while testing**: the probe must be held open ACROSS the fork —
  closing it before the child opens its own slave puts the master into
  a latched-EIO state and the session's first reads lose output
  (verified empirically; the slave fd count must never hit zero in
  that window). The probe is therefore guaranteed a slot in the
  pre-scanned close-list (appended even if the 256-entry scan window
  overflowed — a child that kept it would never deliver
  EIO-on-exit), the parent closes its copy right after fork, and
  close-list allocation failure now FAILS the session loudly instead
  of being tolerated (a child without the fd discipline is a broken
  session, not a degraded one — the tolerance note in the upstream
  comparison only ever covered opendir failure).
- Host harness updated for the new signature (pts out-array + a
  second GetPrimitiveArrayCritical failure test: the pts critical path
  closes the ptm too) — **10 checks, 0 failures** + alloc window
  clean; the harness also now asserts `ptsDevice > 0` on real ptys.
- Local totals: terminal-emulator **150/0**, app **212/0** (193
  pre-existing + 8 ProcTree session/tty/union tests + 11 controller
  Recorder tests), `:app:assembleDebug` green.

**Step 4 — TerminalEnv + session layer + UI + FGS (2026-09-10)**:

- **`TerminalEnv`** (plan §8.2): derived from — never duplicating —
  `ProcEnv`; the ONE delta is `TERM=xterm-256color` (the vendored
  emulator implements the xterm 256-color subset; `dumb` would disable
  half the toolchain's output). Table-driven JVM test
  (`TerminalEnvTest`, 8 tests): every shared channel
  string-identical to ProcEnv (one source of truth), TERM is the ONLY
  difference, CA-file channels carry through, no `SSL_CERT_DIR`
  (CApath lesson), PATH gains nothing, `CARGO_TERM_COLOR=never` stays.
- **Vendored `TerminalSession.java` restructured** (plan §5.3/P1-4,
  recorded in THIRD_PARTY.md): the reader now polls {master fd,
  wakeup pipe} via `Os.poll` — teardown wakes it through the pipe
  (`requestReaderStop()`), joins it bounded (`joinReader()`), and only
  then does the I/O owner close the master (`closeMasterFd()`,
  idempotent) — the upstream main-thread close under a potentially
  watching reader (the fd-reuse use-after-close) is REMOVED from
  `cleanupResources`. EIO on the master read is normal session end.
  New `JNIHelper` (vendored addition): public facade for Os.kill +
  native killpg, both throwing so Kotlin `runCatching` guards them.
- **`TerminalCenter`**: process-wide session registry (rotation-proof;
  the FGS keeps the process alive per §8.3), single-worker I/O-owner
  dispatcher for every master close + teardown (the fd discipline),
  `TerminalSessionClient` implementation routing view redraws. Sessions
  run `$PREFIX/bin/sh` in `$HOME` with the TerminalEnv env; **no silent
  fallback** — the shell missing (busybox ships in step 5's bundle)
  fails LOUDLY with an explanatory empty state.
  **Backpressure (§8.4, hard constraint)**: no unbounded Kotlin-side
  buffering anywhere in this layer; the child blocks writing the pts
  when the reader falls behind; the vendored ByteQueue is bounded
  (64 KiB) and scrollback is capped (`transcriptRows = 200`).
- **`TerminalService`** (FGS `dataSync`, ships in v0 per §11.2): starts
  at ≥ 1 session, stops at 0; notification "Terminal session running";
  NO session-persistence claim anywhere (§5.4); `dataSync`→`specialUse`
  migration debt already on the targetSdk-28 list. Manifest declares
  the service.
- **`TerminalScreen`/`TerminalViewModel`** (plan §8.1): a first-class
  destination (`Routes.TERMINAL` from the Home toolbar — NOT a mode of
  the Editor console); `AndroidView` interop hosting the vendored
  `TerminalView` following the sora-editor gesture lesson (NO Compose
  draggable overlays over the interop area — scrolling/selection stay
  inside the View); v0 session tab strip + per-tab close + FAB new
  session; extra-keys row (Esc/Tab/one-shot Ctrl via
  `readControlKey()`/arrows/PgUp/PgDn, §11.4) fixed BELOW the interop
  area, not floating over it.
- Local totals: app **220/0** (+8 TerminalEnv tests), terminal-emulator
  **150/0** (patched TerminalSession compiles; the JVM suite never
  calls the Android-only threading path), `assembleDebug/Release`
  green; APK = `lib/arm64-v8a/{librustdroidpty.so,
  androidx.graphics.path.so}` only.

**Step 5 — BusyBox build + manifest-v2 bundle + Licenses screen (2026-09-10)**:

- **`busybox/` (source pin, plan §7.2)**: `build.sh` downloads
  busybox-1.36.1.tar.bz2, SHA-256-pinned from busybox.net's own
  `.sha256` file (`b8cc24c9…`, re-verified; v1's 1.38.0 pin was
  upstream-labelled unstable — review P1-6). The pin is the pin: a
  mismatch fails LOUD, never silently re-pins (the openssl-probe
  lesson). Patches: Termux's set retargeted `@TERMUX_PREFIX@` →
  build-time `@RUSTDROID_PREFIX@` (substituted AFTER patching — the
  `patches/post-vendor/0001` pattern). Dropped from the Termux set, with
  reasons in `busybox/patches/README.md`: 0000 (build.sh drives clang
  itself), 0007-0009 + 0011 (ftpd/httpd/tftp/tc — applets disabled),
  0015 (SELinux off — static), 0016 (yescrypt patch targets ≥1.37
  code that 1.36.1 does not have). 0002 carried with its telnetd.c
  hunk removed (dead applet, stale 1.38 context would fail the FATAL
  apply). NEW `0017-rd-bionic-syscalls`: NDK r27c bionic provides
  getsid/sethostname/adjtimex, duplicating busybox's ANDROID wrappers
  at static-link time (ld.lld duplicate-symbol) — the wrappers are
  removed, pivot_root keeps its wrapper; an NDK bump fails loudly
  either way. `oldconfig` (not olddefconfig — 1.36.1 has none; `yes ''
  |` with a local pipefail exception for the expected SIGPIPE death of
  `yes`) resolves the 1.38.0-flavored Termux template symbols.
- **Config defect found and fixed mid-step (regression-pinned)**: the
  Termux-derived template carried network applets plan §7.2 (condition
  9) drops. First audit pass: TFTP, NTPD, RDATE, NETCAT (Termux's
  1.38.0 symbol; 1.36.1's NC was already off). Second pass, from the
  built binary's applet table, caught four more the symbol audit
  missed: SENDMAIL (SMTP client), UDPSVD (UDP superserver), WHOIS
  (client), POPMAILDIR (POP3 client) + FEATURE_CROND_CALL_SENDMAIL.
  All dropped; `build.sh` now asserts BOTH directions post-oldconfig
  (check_on for the shell pins, check_off for 31 network symbols —
  the negative form survives symbol renames and kconfig dropping
  unknown symbols). Proven the repo way: re-enabling CONFIG_TFTP in
  the input config makes the build die with exit 1 at
  "config drift: CONFIG_TFTP is ENABLED".
- **Kept-vs-dropped classification (stated, not silent — the plan's
  "…" resolved by its own rationale)**: applets that CONNECT to or
  RESOLVE network hosts are dropped (static bionic has no dynamic
  NSS/DNS path — "wget is broken" reports are worse than no wget);
  purely-local network-admin tools are KEPT: ifconfig, route, ip,
  netstat, arp (+ crond/crontab/makemime/reformime, all local). The
  final binary's applet table was string-audited: 29 network
  client/server names absent, sh/ash/vi/env/ls + local admin applets
  present.
- **End-to-end local build (same NDK r27c pin as CI)**: 1,286,680
  bytes, static AArch64 ELF, no PT_INTERP, no DT_NEEDED, applet smoke
  (sh/ash/vi/ls/env) green. `busybox.yml` (decoupled from main.yml,
  plan §9: a config line must never cost a 2.5 h toolchain rebuild):
  manual dispatch + push under `busybox/**`, NDK r27c, artifact
  `rustdroid-busybox-aarch64` (binary + its sha256).
- **Manifest v2 (plan §7.3)**: `BundleManifest` gains a `busybox`
  `ComponentInfo` (file/sha256/size — the same per-entry discipline as
  the tarballs) and a `symlinks` list (`SymlinkSpec`: name + target).
  `ArtifactExtractor` installs the busybox payload to
  `$PREFIX/bin/busybox` (exec mode, manifest-sha256-verified) and
  creates the symlinks through the P2-8 guard: `Fs.resolveChild`
  (lexical: no absolute, no `..`) AND `Fs.requireInside` (canonical
  containment) on the link path, plus target-resolution containment
  — every rejection is LOUD and install-blocking, never
  warn-and-continue, never an empty-file placeholder. A v1-tag bundle
  carrying v2 payloads is REJECTED (never install bytes the pinned
  manifest does not describe); a v2-expecting app on a v1 manifest
  fails naming busybox. Zip entries can't carry symlinks portably —
  that is why the manifest owns them. `ToolchainDistro`: tag
  `toolchain-1.85.0-aarch64-bb1.36.1` (self-describing), expectedEntries
  + "busybox", `SHA256` intentionally empty until the busybox-carrying
  release exists (the pin blocks downloads of the old bundle, whose
  layout this app version can no longer accept; dev builds set "").
  `ToolchainPaths` gains busybox/sh/ash.
- **Verifier extension (plan §6.5)**: three new install-time checks —
  busybox present/executable/static-AArch64-no-PT_INTERP (pure-Kotlin
  64-byte ELF header + program-header parse; the INVERSE of the
  toolchain's PT_INTERP-required check), `busybox sh -c 'echo ok'`
  through the EXACT TerminalEnv the terminal sessions use, and
  sh/ash-symlinks-resolve-to-busybox. Failures block Ready with
  actionable messages. The pty round-trip smoke stays on the §6.6
  on-device checklist (interactive bring-up) — the verifier stays a
  pure install-time gate.
- **GPL-2.0 surface (plan §7.4/§7.5)**:
  `LICENSES/busybox-GPL-2.0.txt` (provenance header + license) and
  the upstream LICENSE verbatim in `assets/licenses/`. Settings →
  Licenses: list (component, license, pin, upstream URL) with full
  text on tap, from `LICENSE_INDEX.json`; the BusyBox row carries the
  REQUIRED source-asset URL (`busybox-1.36.1-src.zip` on the same
  release as the bundle) as always-visible content, not buried behind
  the tap — the bundle reaches recipients through the app's own
  downloader, not the release page. `publish-release.yml` consumes
  the busybox artifact (latest successful busybox.yml run, sha
  re-verified at publish time — never trust transit), assembles the
  v2 bundle (busybox + symlinks in the manifest, busybox payload in
  the zip), builds `busybox-1.36.1-src.zip` (pristine tarball + our
  config + patches + build script — the complete corresponding
  source, GPL §7.4), and covers all three assets in SHA256SUMS.txt.
- Local totals: app **226/0** (1 pre-existing skip; +6 manifest-v2
  tests: busybox+symlinks install, `../../etc/passwd`, absolute
  `/data/local/tmp/x`, deep `../`-chain escape, sha mismatch,
  v1-drift), terminal-emulator 150/0, terminal-view (no JVM tests,
  upstream has none), `assembleDebug/Release` green; APK =
  `lib/arm64-v8a/` only, `assets/licenses/` embedded. CI: steps 1-4
  green (faf6efd = step 4, run 34464468685).

## 11. On-device hotfix: busybox gate inverted verdict (2026-09-10, v0.1.7)

First real on-device install of the manifest-v2 bundle: download,
extraction, and every check up to the busybox gate passed, then
"check 'busybox present, executable, static AArch64 ELF' failed: not an
ELF file (magic mismatch)" — reported for a perfectly VALID static
AArch64 busybox.

- **Root cause (ToolchainVerifier §6.5 gate)**: the check wrapped
  `elfStaticAarch64`'s result in a `when` that inverted its
  null-means-valid contract — `null` (valid static AArch64, no
  PT_INTERP) was mapped to the failure detail "not an ELF file (magic
  mismatch)", and the parser's own non-ELF diagnostic (plain "not an
  ELF file", no suffix) was the only other reachable branch. The
  observed "(magic mismatch)" text itself proves the good-file branch
  fired: no install could ever pass the gate. The 226-test suite
  missed it because no test reached the busybox check's success path
  (the cancellation test parks at the version probes; the
  empty-prefix test fails earlier). Fixed by returning the parser's
  result directly; three new regression pins (valid ELF passes;
  non-ELF and PT_INTERP fail with the parser's own diagnostics)
  exercise the gate through the public `verify()`. 229/0 (1 skip).
- **Gate rescue path (GateScreen)**: a first install whose verification
  fails leaves the extracted prefix on disk with no ready marker (the
  rollback has nothing to restore, by design). The gate's NotInstalled
  prompt now offers "Verify files already on this device…" when
  `isInstalled()` holds — the foreground-service re-verify, no
  re-download. Recovers exactly the stranded-prefix situation this
  incident produced on the reporting device (its prefix verified
  healthy through the cargo-version probes before the false failure).
- **SHA-256 re-pin (ToolchainDistro)**: the documented step-5 follow-up,
  now that the v2 release exists — hash taken from the release's
  SHA256SUMS.txt (`20fd976c…` for rustdroid-app-bundle-aarch64.zip,
  118,262,640 bytes). Downloads are integrity-pinned end to end.
- versionCode 8 / versionName 0.1.7 so the fixed build is identifiable
  on the device.

## 12. Terminal on-device bring-up hotfix + crash flight recorder (2026-09-10, v0.1.8)

First on-device terminal session open (user report, Android device,
toolchain verified Ready): "opening a terminal session instantly closes
the app". No reproducible environment here (no emulator/KVM), so this
iteration pairs a forensic audit of the entire bring-up path with an
in-app flight recorder that makes the next occurrence self-diagnosing —
in the app, with zero external files.

Audit results (all verified by reading the full chain: Gate → Home →
TerminalScreen → TerminalViewModel → TerminalCenter → vendored
TerminalSession/TerminalView/termux.c):

- **JNI surface**: all 6 `Java_com_termux_terminal_JNI_*` symbols present
  in the shipped librustdroidpty.so (readelf on the APK's copy); the 5
  declared natives all resolve. Not the failure.
- **Fixed: null-view-client window (TerminalScreen)** — the vendored view
  can invoke `mClient.onEmulatorSet()` from its first `updateSize()`
  during the frame's layout pass; the client was only set in a
  post-composition `LaunchedEffect`. An NPE in that window is an instant
  app death exactly matching the report. The client is now set at view
  creation (inside the `remember` block).
- **Fixed: upstream `System.exit(1)` (TerminalSession.wrapFileDescriptor,
  vendored)** — the FileDescriptor reflection fallback killed the process
  SILENTLY on failure (no dialog, no reportable trace). Replaced with a
  thrown RuntimeException (recorded in THIRD_PARTY.md). If this ever
  fires on a device, it is now caught, persisted and surfaced.
- **Fixed: notification-ID collision** — TerminalService reused
  ToolchainInstallService's id 42; a concurrent download + terminal
  clobbered each other's ongoing notifications. Now 43.
- **Hardened: createSession never kills the process** — TerminalCenter
  wraps session construction in runCatching → new
  `CreateResult.Error(detail)` surfaced in the terminal's existing error
  state (loud, never silent, never fatal).
- **Flight recorder (CrashRecorder, new)** — default
  uncaught-exception handler persists thread + exception + cause chain +
  stack + breadcrumb ring to files/last-crash.txt, then CHAINS to the
  platform handler; next launch shows an in-app dialog (AppRoot) with
  Copy/Dismiss. Breadcrumbs bracket every risky step
  (create:start/env-built/session-built/fgs-started, view-attach/
  view-attached) — the fork happens between view-attach and first draw,
  so even a NATIVE death (uncatchable in Java) is localized by the last
  written breadcrumb. 5 JVM tests pin the persistence contract
  (234 total).
- **Workflow (the PyDroid-style ask)**: new sessions now start in the
  PROJECTS directory (files/projects) instead of the empty $HOME —
  `cd <project> && cargo run / cargo fetch` work directly (PATH leads
  with $PREFIX/bin; CARGO_HOME/CA channels unchanged, all absolute).
  HOME remains $RUSTDROID_HOME via env. TerminalScreen's empty state
  now says exactly this.

## 13. Terminal view-attach NPE — the renderer half of the host contract (2026-09-10, v0.1.9)

The v0.1.8 flight recorder earned its keep on the very next session
open: the persisted crash (files/last-crash.txt, pasted back by the
user) is an NPE with a complete breadcrumb chain —

    terminal:create:start → env-built → session-built → fgs-started
    → view-attach  ← crash, 71ms after create:start

    NullPointerException: Attempt to read from field 'float
    com.termux.view.TerminalRenderer.mFontWidth' on a null object reference
      at TerminalView.updateSize(TerminalView.java:990)
      at TerminalView.attachSession(TerminalView.java:298)
      at TerminalScreenKt$TerminalPane$2$1.invokeSuspend(TerminalScreen.kt:214)

Root cause (verified against the vendored tree, which matches upstream
3b66f87 line-for-line in this region — the crash line :990 IS upstream's
`mRenderer.mFontWidth` line): v0.1.8 fixed ONE half of an undocumented
upstream host contract (set the view client before attach, because
updateSize() calls mClient.onEmulatorSet() at :996). The OTHER half:
`setTextSize()` is the ONLY place a TerminalRenderer is ever created
(TerminalView.java:515; the constructor creates none, setTypeface only
replaces an existing one). TerminalPane never called it. The zero-size
guard at :987 could not save us: the create pipeline resolves ~70ms
after the first frame, so the view was already measured when
attachSession ran — width/height non-zero, mTermSession just assigned,
mRenderer never created → :990 dereferences null. Deterministic, not a
race: every session open on every device.

Two follow-on facts from the same audit, both handled:

- **Unit trap**: upstream's javadoc on setTextSize claims
  "density-independent pixels", but TerminalRenderer hands the value
  straight to Paint.setTextSize() — PIXELS. The host must convert.
- **The hazard chain continues past :990**: with a null renderer,
  onDraw() at :1019 (mRenderer.render, guarded only on mEmulator) would
  be the NEXT NPE after the first resize — attach-without-setTextSize
  is unconditionally fatal in stock upstream code. Upstream never sees
  this because Termux's own activity always configures the view before
  attaching.

Fix (both halves, same commit):

- **TerminalScreen (host contract, root cause)** — `setTextSize()` now
  called at view creation in the `remember` block, immediately after
  the v0.1.8 `setTerminalViewClient()`. 14dp × density → px (14 matches
  the code editor's `editor.setTextSize(14f)`; constant
  `TERMINAL_FONT_SIZE_DP`, documented with the px-not-dp trap). Safe
  pre-attach: setTextSize → updateSize early-returns while
  mTermSession == null; the real resize then happens inside
  attachSession as upstream intends.
- **Vendored TerminalView (defense-in-depth, recorded in
  THIRD_PARTY.md)** — `updateSize()` now guards `mRenderer == null`:
  auto-creates the renderer at a 14dp px-converted default and logs a
  loud `android.util.Log.w` (mClient logging is unusable here — an
  unset client is part of the failure class being guarded). The log is
  a tripwire, not noise: if it ever appears, some host call site is
  still missing setTextSize() and the terminal is running on the
  fallback font. setTextSize() itself calls updateSize(), so the guard
  recurses exactly one level and terminates.

Verification honesty: no build/test environment here (no Android SDK —
same constraint as v0.1.8), so verification was static: the vendored
file was diffed line-for-line against pristine upstream 3b66f87 in the
crash region; the renderer-creation invariant (only :515/:520 assign
mRenderer) was re-checked against the pin; and the Kotlin edit compiles
by inspection against the existing imports (no new imports needed).
No host-side regression test exists for this defect class, deliberately:
reaching :990 requires an attached session (a real TerminalSession
forks via JNI — untestable on the JVM), and under
`returnDefaultValues` both View.layout and Paint.measureText are
no-ops, so even the guard's fallback metrics would all read zero.
Robolectric + a session seam would be the honest host test — recorded
as a follow-up, not smuggled in unverifiable. On-device checklist for
the next open: (1) session opens, prompt renders at 14dp; (2) logcat
contains NO "updateSize() called before setTextSize()" warning;
(3) rotate/split → `stty size` follows (SIGWINCH through updateSize);
(4) crumbs now reach terminal:view-attached; (5) 200 open/close cycles
→ no renderer regression, no fd growth.
