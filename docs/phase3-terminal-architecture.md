# Phase 4 — Native Terminal: Architecture Plan (pre-implementation)

> **Status of this document**: architecture plan for the pre-implementation
> review gate. No Phase-4 implementation code has been written. The only code
> change landed before this plan is the `ProcTree.terminateTree` zombie-state
> fix ordered separately in the task brief (see §12.1) — it is a live bug in
> `main` independent of the terminal work and Phase 4's teardown builds on
> that file.
>
> **Naming note**: the task brief calls this "Phase 3"; the repo's README
> already numbers Phase 3 as the IDE experience (editor/diagnostics/deps,
> shipped). To keep the README phase table honest, the terminal work is
> **Phase 4** here. All other terminology follows the brief.

---

## 1. Executive summary

RustDroid today runs `rustc`/`cargo` as child processes with pipes, surfaced
through a line-oriented console. Phase 4 adds a **real PTY terminal**: a
vendored, defect-fixed copy of Termux's Apache-2.0 `terminal-emulator` +
`terminal-view` libraries, a self-hosted BusyBox (`ash` + coreutils) built
statically for `aarch64-linux-android` and reparented to
`/data/data/dev.rustdroid.ide/files/usr`, and a new session/process layer
with **session- and process-group-aware teardown** — deliberately *not* an
extension of the current ppid-walking `ProcTree` kill path.

Non-goals (explicit): no `apt`/`dpkg`-style package manager (out of scope by
brief), no Termux compatibility layer, no multi-ABI builds (arm64-v8a only,
everywhere), no change to the on-device prefix or `targetSdk 28`.

The plan below pins exact upstream commits and versions, defines the module
layout, the process model, the licensing compliance surface, the CI and
release changes, and the full regression-test matrix for every defect named
in the brief.

## 2. Preconditions verified (primary sources, this session)

| Claim in brief | Verification | Result |
|---|---|---|
| `terminal-emulator`/`terminal-view` are Apache-2.0 | `termux-app/LICENSE.md` @ `3b66f87` — explicit carve-out: *"Terminal Emulator for Android code is used which is released under Apache 2.0 license. Check `terminal-view` and `terminal-emulator` libraries."* | **Confirmed.** Using them requires Apache-2.0 attribution, not relicensing of RustDroid (repo stays MIT). |
| `termux-shared` is out of scope | `termux-shared/LICENSE.md` @ `3b66f87`: library is MIT overall, **but `src/main/java/com/termux/shared/termux/*` is GPLv3-only** (that subtree holds `TermuxConstants` and the hardcoded `/data/data/com.termux/...` assumptions). | **Confirmed excluded.** See §3.2 for the no-transitive-pull proof. |
| Upstream JNI PTY code has 3 defect classes | Read `terminal-emulator/src/main/jni/termux.c` @ `3b66f87` (file unchanged since 2022-11-16, commit `438cd73`). | **All 3 confirmed** — see §4.3 for line-level analysis. |
| Prebuilt AAR ships 4 ABIs | `terminal-emulator/build.gradle` @ `3b66f87`: `abiFilters 'x86','x86_64','armeabi-v7a','arm64-v8a'` + prebuilt `.so` via ndkBuild | **Confirmed** — vendoring required for F-Droid + ABI strip. |
| `toysh` not a reliable POSIX shell drop-in; Android keeps `mksh` | android.googlesource.com *"Android's shell and utilities"*: *"Since IceCreamSandwich Android has used mksh as its shell… Marshmallow almost everything is supplied by toybox instead"*; landley.net toybox news shows toysh still maturing (milestone framed as *"toysh making it all the way through toyroot's init script"*); AOSP RISC-V port (plctlab) ships mksh as the shell. | **Confirmed.** AOSP shipping Toybox for utilities while keeping mksh for the shell is direct evidence the project does not consider toysh production-ready as a system shell. BusyBox `ash` (Alpine's system shell, decades of deployment) is the choice. |

## 3. Licensing architecture

### 3.1 Component inventory and licenses

| Component | License | How consumed | Compliance action |
|---|---|---|---|
| `terminal-emulator` (Termux) | Apache-2.0 | **Vendored source**, Gradle module, pinned commit `3b66f8799635a4dba4a206563048ff0e6792c487` | `LICENSES/terminal-emulator-Apache-2.0.txt` + THIRD_PARTY.md entry (upstream repo, commit hash, origin = jackpal Android-Terminal-Emulator); no NOTICE file exists upstream in either module (verified) |
| `terminal-view` (Termux) | Apache-2.0 (includes `support/PopupWindowCompatGingerbread.java` from AOSP, Apache-2.0, header preserved) | Same | Same |
| BusyBox 1.38.0 | **GPL-2.0-only** | Separate static executable, `execve`'d | `LICENSES/busybox-GPL-2.0.txt`; complete corresponding source published as a release asset (§7.4); in-app license screen (§7.5) |
| Toybox | 0BSD | **Not used** (decision §7.1) | — |
| RustDroid app (Kotlin) | MIT (unchanged) | — | BusyBox is *mere aggregation*: a separate process, no shared address space, no linking — GPL-2.0 does not reach the app code |
| sora-editor, textmate grammar, Mozilla CA bundle, Rust, NDK | (existing, unchanged) | — | recorded in the new THIRD_PARTY.md |

The repo's overall MIT license is **not** changed by any of this.

### 3.2 No-`termux-shared` guarantee (explicit confirmation)

`termux-shared` is not vendored, not referenced by any Gradle module we ship,
and — verified directly — **neither `terminal-emulator` nor `terminal-view`
contains a single reference to `com.termux.shared` or `TermuxConstants`**
(`rg -l "termux.shared|TermuxConstants" terminal-emulator/ terminal-view/`
→ zero matches at the pinned commit). Our `settings.gradle.kts` will include
only `:app`, `:terminal-emulator`, `:terminal-view`. The two vendored
modules' only external dependencies are `androidx.annotation:annotation` and
each other (`terminal-view` → `terminal-emulator`). There is therefore **no
code path, direct or transitive, that pulls in `termux-shared`**; the GPLv3
subtree of the Termux repositories is not present in our build at all.

### 3.3 Why vendored source instead of the AAR (both reasons, required)

1. **F-Droid from-source policy**: the published AAR ships prebuilt `.so`
   files for 4 ABIs pulled at build time from JitPack — hostile to F-Droid's
   "build everything from source" rule. Vendoring the C source and compiling
   it in our CI (NDK r27c, arm64-v8a only) satisfies the policy.
2. **The three JNI defects** (§4.3) live in the native code and can only be
   fixed on source we control; the fixes need regression tests, and the
   fork-window needs restructuring (§4.4).

## 4. Vendoring `terminal-emulator` + `terminal-view`

### 4.1 Pin and layout

- **Pinned upstream commit**: `3b66f8799635a4dba4a206563048ff0e6792c487`
  (termux-app HEAD at vendoring time, 2026-08-24). The JNI code we patch
  (`termux.c`) is unchanged upstream since 2022-11-16, so drift risk is low.
- **Location**: `android/terminal-emulator/` and `android/terminal-view/`
  as Gradle library modules; source trees copied verbatim from the pinned
  commit (Java sources, C source, Android.mk, res, upstream tests — see
  §4.6). `settings.gradle.kts` gains `include(":terminal-emulator",
  ":terminal-view")`.
- **Build scripts are ours** (upstream's Groovy build files reference their
  CI properties and publishing): new `build.gradle.kts` per module with
  `compileSdk 35`, `minSdk 24`, `ndkVersion "27.2.12479018"` (NDK r27c —
  matches the toolchain build's pin in `env.sh`, verified as the correct
  sdkmanager id), Java toolchain 17, `ndk { abiFilters += "arm64-v8a" }`.
  Upstream's maven-publish blocks are dropped.
- Every divergence from the pinned commit is recorded in THIRD_PARTY.md
  (file-by-file: build scripts replaced, JNI library renamed, `termux.c`
  patch set — §4.3/4.4/4.5, `JNI.java` loadLibrary line).

### 4.2 JNI library rename

`Android.mk` `LOCAL_MODULE` changes `libtermux` → **`librustdroidpty`**, and
`JNI.java`'s `System.loadLibrary("termux")` → `System.loadLibrary("rustdroidpty")`.
The Java package stays `com.termux.terminal` (JNI symbol names
`Java_com_termux_terminal_*` stay stable; vendored namespaces preserved
upstream-style, which is exactly the attribution-preserving way to vendor).
Rationale (per brief): an installed Termux app and RustDroid both having a
`libtermux.so` in /proc/<pid>/maps, bug reports, and crash dumps is confusion
we can avoid for free; there is no runtime collision (each APK loads its own
`/data/app/<pkg>/lib/…` copy).

### 4.3 The three upstream JNI defects — confirmed and fixed on import

All in `terminal-emulator/src/main/jni/termux.c` at the pinned commit:

1. **`jstring` release mismatch** (JNI contract violation): the JNI wrapper
   obtains `cmd_cwd` from the `cwd` jstring but releases it against `cmd`:
   `(*env)->ReleaseStringUTFChars(env, cmd, cmd_cwd)` — the pointer and the
   jstring argument disagree, which corrupts string-pinning bookkeeping on
   some runtimes. **Fix**: release each pinned buffer against the jstring it
   came from (`env, cwd, cmd_cwd`).
2. **PTY master fd leak on `fork()` failure**: `create_subprocess` returns
   `throw_runtime_exception(env, "Fork failed")` without `close(ptm)` — and
   the same leak exists on the `grantpt/unlockpt/ptsname_r` failure path and
   the `GetPrimitiveArrayCritical` failure path. Failures cluster under
   memory pressure (exactly when `fork()` fails), and each leaked master fd
   also pins a pty slave device. **Fix**: single-exit cleanup structure
   (§4.4) so every error path closes `ptm`.
3. **argv/envp marshalling leaks**: early returns in the JNI wrapper
   (`GetStringUTFChars` failure loops, `malloc` failure for `envp`) return
   without freeing the partially- or fully-built `argv`/`envp` arrays and
   their `strdup`'d strings; the `envp` malloc failure leaks the *entire*
   already-built `argv`. **Fix**: same single-exit cleanup; ownership table
   documented in a header comment.

**Regression tests** for each defect: host-compiled C harness with a mock
`JNIEnv` (§6.2) — fails on the unfixed code, passes after.

### 4.4 Async-signal-safe fork→exec window (restructure)

The brief is explicit: *no JVM/allocator activity between `fork()` and
`exec()` in a multithreaded process*. Upstream's child path violates this:

| Upstream child-path call | Violation |
|---|---|
| `opendir/readdir/closedir("/proc/self/fd")` | opendir/closedir allocate (malloc) — allocator state is the other threads' fork snapshot; a contended malloc lock deadlocks the child silently |
| `clearenv()` + `putenv()` loop | both free/allocate the global `environ` |
| `asprintf` + `perror` + `fflush` on `chdir`/`exec` failure | allocator + stdio |
| `exit(-1)` (pts-open failure path) | runs atexit handlers — use `_exit` |
| `execvp` | PATH search may allocate; not async-signal-safe per POSIX (execve is) |

**Redesign** (all string/array/fd-enumeration work moves BEFORE `fork`, into
the parent where the JVM and allocator are alive and well):

1. **Pre-fork (parent)**: the existing JNI marshalling already builds
   `argv[]`/`envp[]` as C arrays. Additionally: (a) resolve the executable
   to an absolute path here (string work — the app always passes absolute
   paths via `ProcEnv.toolchainCommand`-style call sites; a `PATH` search,
   if ever needed, happens in the parent); (b) open the ptmx, set termios
   (IUTF8, no IXON/IXOFF) and winsize as today; (c) **pre-scan
   `/proc/self/fd` here** (opendir is fine pre-fork) into a close-list
   `int[]`, excluding 0/1/2 and `ptm`.
2. **Child (async-signal-safe calls only)**: `sigprocmask(SIG_UNBLOCK, full)` →
   `close(ptm)` → `setsid()` → `open(pts)` (no `O_NOCTTY`, so it becomes the
   controlling terminal of the new session) → `dup2(pts, 0/1/2)` → close
   leftover fds: prefer `close_range(3, ~0U, 0)` via direct
   `syscall(SYS_close_range)` (Linux ≥ 5.9; syscall number 436 on
   arm64/x86_64 — declared locally, no bionic header dependency) when
   available, else close each fd from the parent's pre-scanned list (pure
   `close(2)` calls) → `chdir(cwd)` (pure syscall; on failure `write(2)`
   the error — no stdio — and continue to exec, matching upstream
   behavior) → `execve(absPath, argv, envp)` — **`envp` replaces the
   environment wholesale**, so `clearenv`/`putenv` are unnecessary. On
   exec failure: `write(2, …)` then `_exit(127)`.
3. **Parent**: returns `ptm` and the child pid as today.

**Residual risk, stated honestly**: a fd opened by another JVM thread after
the pre-scan and before `fork` escapes the close-list unless it is
`O_CLOEXEC`. ART marks runtime fds `O_CLOEXEC` by default, our ptmx is opened
`O_CLOEXEC`, and Java `ProcessBuilder` (the current build path) closes no
extra fds at all — so this is strictly no worse than the status quo, and
`close_range` (kernel ≥ 5.9, i.e. Android 11+ devices and all modern
kernels) closes the gap entirely on those devices.

**Enforcement**: (a) the child path is restructured into one short function
containing only whitelisted calls, reviewable by reading it; (b) a CI check
scripts a whitelist-grep over the child-path function body (forbidden
identifiers: `opendir`, `readdir`, `closedir`, `malloc`, `calloc`, `free`,
`strdup`, `putenv`, `clearenv`, `asprintf`, `perror`, `printf`, `fflush`,
`execvp`, `exit`); (c) the malloc interposer in the test harness (§6.2)
counts allocations and fails the test if any occur between the fork marker
and exec. Automation + review, stated as such — no overclaiming.

### 4.5 Additional native function: process-group signaling

The session teardown (§5) needs `killpg(2)`. We add one JNI function to the
same vendored `termux.c` + one method to the vendored `JNI.java`:
`sendSignalToProcessGroup(int pgid, int sig)` → `killpg(pgid, sig)`. It is
recorded in THIRD_PARTY.md as a RustDroid addition on top of the pinned
commit. (Rationale for JNI over `android.os.Process`: we already own a
native module; `killpg` semantics are unconditional and API-level
independent; identity validation before every signal stays in Kotlin, where
the /proc discipline lives.)

### 4.6 Upstream test suite

`terminal-emulator`'s 18 JUnit test classes (TerminalTest, ResizeTest,
UnicodeInputTest, …) are vendored and run as a module in CI
(`:terminal-emulator:testDebugUnitTest`) — pure JVM, zero Android
dependencies, and they pin the emulator's behavior before our changes. Our
UTF-8 split-across-reads regression test (§6.3) joins them. `terminal-view`
has no upstream tests.

## 5. Process model — the architectural crux

### 5.1 Why `ProcTree`'s model does not map (and will not be extended)

`ProcTree.descendants` walks parent links and **skips `ppid <= 1`**. Every
process that calls `setsid()` or daemonizes (i.e. is reparented to init)
becomes invisible to that walk. A terminal shell is *by construction* a
session leader (`setsid()` in the child path) and its job-control children
migrate process groups freely; a user daemon spawned from the terminal is
invisible to a ppid walker from birth. The brief's requirement is met by
design, not adaptation: **terminal teardown is keyed on session id and
process groups, not parent links.** The existing ppid walker remains, with
its semantics unchanged, for the build-cancel path (cargo runs in the app's
own session/process group — that model is correct there).

### 5.2 New: `ProcTree.sessionMembers` (additive, same file, same discipline)

Pure function, synthetic-/proc testable, reusing the file's identity
machinery verbatim:

```
sessionMembers(procRoot, sid): List<ProcessId>
```

- Reads `/proc/<pid>/stat` field 6 (**session id** — parsed with the same
  last-`)` discipline already used for starttime; reparenting never changes
  a process's sid, which is precisely what makes session keying immune to
  the ppid problem).
- Returns processes whose sid == the shell's pid (the shell being the
  session leader created by our `setsid()`), each as `ProcessId(pid,
  starttime)` — the PID-reuse-safe identity, reused as-is.
- Excludes nothing by ppid; a `setsid`'d daemon keeps `sid == shellPid`
  forever and stays visible.

### 5.3 Teardown sequence (`TerminalSessionController`)

Every step reuses `ProcTree`'s invariants: PID+starttime identity
revalidated before **every** signal, PID-reuse never signaled, unknown
identity never authorizes traversal, zombie (state Z) corpses not counted
(the §12.1 fix), bounded sweeps.

1. Snapshot `sessionMembers(procRoot, shellPid)`.
2. **Freeze**: SIGSTOP every member that still matches its captured
   identity (excluding the shell itself last) — nothing new spawns, nothing
   escapes between discovery and the kill.
3. `killpg(shellPgid, SIGHUP)` then `killpg(shellPgid, SIGKILL)` on the
   shell's own group (the classic terminal teardown: SIGHUP first so an
   interactive shell gets to forward the hangup to its jobs before the
   hard kill).
4. Re-discover session members while the shell PID still provably belongs
   to the original process (identity-guarded, exactly like
   `terminateTree`'s step 5), to catch late spawns.
5. Bounded SIGKILL sweeps over captured members: identity-checked,
   zombie-excluded, early-return on empty — the same budget discipline as
   `terminateTree` (default 3 sweeps / 50 ms).
6. Close the pty master fd (kernel then SIGHUPs any survivor in the
   foreground process group of that terminal — belt and braces for a
   member that escaped all lists).

The controller is a pure-Kotlin, /proc-driven object with injected
`signal`/`killpg`/`destroy` lambdas — unit-tested with a Recorder exactly
like `ProcTreeTest` does, plus a synthetic-/proc suite for
`sessionMembers` (incl. a reparented daemon and a `setsid`'d escapee).

### 5.4 What survives what (honesty section — no overclaiming)

| Event | Terminal sessions | Why |
|---|---|---|
| App foregrounded / screen rotation | **Survive** | Process alive; activity `configChanges` already handled (manifest). |
| App backgrounded briefly | Usually survives (cached process) | No guarantee — Android may kill cached processes at any time under memory pressure. |
| App swiped away / process death | **Sessions end.** The pty master fd closes; the kernel sends SIGHUP to the terminal's foreground process group; shell and most children die. A daemon that ignores SIGHUP and holds no terminal may linger until the OS reclaims it — stated as-is, no "survives death" claim anywhere in UI or docs. | No session-persistence magic exists in this design. |
| Foreground service running (`TerminalService`, §8.3) | Backgrounding survived *much* more reliably (FGS is not cached-killable under normal pressure) | Still not process-death survival — and no claim of it. |

Phase 4 v0 ships **without** claiming session persistence. The FGS question
is an explicit open decision (§11.2) with a recommendation.

## 6. Test plan (every defect in the brief gets a regression test)

### 6.1 Already landed this session (pre-plan, ordered by the brief)

- **Zombie-state bug** (`ProcTree.terminateTree` counting state-Z corpses as
  remaining): fixed — sweep excludes `/proc/<pid>/stat` state `Z` (first
  token after the last `)`), with 3 regression tests (state parsing; a
  killed-then-zombie descendant is signaled exactly once and the
  early-return fires; a live stubborn descendant still gets the full
  bounded sweep). Proven to fail on the pre-fix code, pass after; full JVM
  suite: 193 tests, 0 failures.

### 6.2 Host-side JNI harness (new — runs in `android.yml` on ubuntu-latest)

A C test harness compiled with clang + `-fsanitize=address` (LeakSanitizer
included) exercising the vendored `termux.c` through a **mock `JNIEnv`**
(~150 lines: controllable `GetStringUTFChars`/`ReleaseStringUTFChars`/
`GetPrimitiveArrayCritical` + call recording + injectable failures):

- **Defect 1**: assert every `ReleaseStringUTFChars` pairs with the *same*
  jstring its chars came from (the mock records pairs) — fails on upstream
  code.
- **Defect 2**: fork is routed through a `RD_FORK()` macro (test build
  overrides it to return `-1` deterministically); assert the error is
  thrown **and** `fcntl(ptm, F_GETFD)` returns `EBADF` (fd was closed) on
  the fork-failure path, the `grantpt`-failure path (injected via a
  `RD_GRANTPT_FAIL` hook), and the `GetPrimitiveArrayCritical`-failure path
  (mock-controlled).
- **Defect 3**: mock-injected `GetStringUTFChars` failure at argv[i=k] and
  envp malloc failure — LeakSanitizer reports zero leaks only with the
  single-exit cleanup (fails on upstream with leak reports).
- **Fork-window allocator discipline**: a malloc interposer (link-time
  `--wrap=malloc` etc.) armed at the fork marker fails the test if any
  allocation occurs in the child before `execve` (the child reports the
  count via a pipe before `_exit`).
- Happy-path test: spawn a real `/bin/sh -c 'echo ok'` through the fixed
  `create_subprocess`, assert output through the pty master and clean
  teardown.

These tests run on the host (the JNI layer is plain POSIX; the harness
stubs the JNI boundary). No device required, deterministic, in CI on every
push touching the vendored modules.

### 6.3 UTF-8 split-across-reads regression test (JVM, in `terminal-emulator`)

`TerminalEmulator.processByte` is already a stateful incremental UTF-8
decoder (state `mUtf8ToFollow`/`mUtf8InputBuffer` persists across
`append()` calls) — the upstream design satisfies the requirement, but the
split-boundary behavior is unpinned by an explicit test. New test in the
vendored module: feed a string mixing 2-, 3- and 4-byte sequences byte-wise
with `append()` called at every possible split offset (each multi-byte
sequence cut at each internal boundary, across two and three calls) and
assert the rendered row contains the original text with zero replacement
characters; the same input fed with a corrupted continuation byte must
yield exactly one replacement character. Covers: legitimate splitting AND
malformed-sequence handling.

### 6.4 Session-teardown tests (JVM)

- `sessionMembers`: synthetic /proc — plain child, grandchild, a
  reparented-to-init daemon with `sid == shellPid` (invisible to the ppid
  walker, must appear), an unrelated process with a different sid
  (excluded), a vanished-mid-walk entry (dropped), PID-reuse inside the
  session (never signaled).
- `TerminalSessionController.teardown`: Recorder-based — freeze-before-kill
  ordering, shell group gets SIGHUP before SIGKILL, late spawn caught by
  identity-guarded rediscovery, bounded sweeps, early return on
  zombie-only remainder, zero signals to non-session processes.
- `TerminalEnv` (env builder): TERM/colored-output deltas vs `ProcEnv.env`
  (§8.2) — table-driven JVM test.

### 6.5 Toolchain-verifier extension (on-device, install-time gate)

`ToolchainVerifier` gains: busybox exists, is executable, is a **static
AArch64 ELF with no PT_INTERP** (static ⇒ interpreter-less — the check is
the inverse of the toolchain's PT_INTERP check), `busybox sh -c 'echo ok'`
executes via the exact env the terminal will use, `sh`/`ash` symlinks
resolve inside the prefix, and a pty round-trip smoke (create session →
echo → teardown) once the terminal layer exists. Failures block Ready with
actionable messages, same as the existing 12 checks.

### 6.6 On-device validation checklist (manual, mirrors Phase 1/2 culture)

adb session checklist recorded in DEVIATIONS.md when executed: interactive
ash + job control (`sleep 100 &`, `jobs`, `fg`), `setsid sleep 300` escapee
killed by session teardown, UTF-8 split output (`printf` with cut
sequences), resize behavior, backgrounding behavior, app-death SIGHUP
behavior, 64 KB burst output, long `cargo run` inside the terminal
(exercises the whole env chain).

## 7. BusyBox

### 7.1 Choice (verified, not vibes)

BusyBox over Toybox **because of shell quality**, as the brief requires and
this session's fresh checks confirm (§2, last row): AOSP itself ships
Toybox for utilities but keeps `mksh` as the system shell — the strongest
available signal that `toysh` is not trusted as a POSIX shell even by its
biggest deployer; toybox's own news still frames toysh milestones as
progress work. Meanwhile BusyBox `ash` is Alpine's system shell with
decades of deployment, and Rust build tooling shells out constantly
(`build.rs`, `cc` wrappers, `pkg-config` probes) — a real POSIX-ish shell
is load-bearing for this app specifically. Toybox's 0BSD advantage is
real but not decisive against this.

### 7.2 Build (CI, new `busybox.yml`)

- **Version pin**: BusyBox **1.38.0**, source from
  `https://busybox.net/downloads/busybox-1.38.0.tar.bz2`, SHA-256 verified
  in CI against the pinned checksum `34f9ea6ff8636f2c9241153b9114eefa9e65674a45318ae1ef95bb5f31c53bb2`
  (cross-checked against the Termux recipe's pin; CI fails loudly on
  mismatch — no silent re-pin).
- **Toolchain**: NDK r27c clang, `aarch64-linux-android`, API 24 — the
  same pins as the Rust toolchain build (`env.sh`).
- **Config**: starts from Termux's `busybox.config`
  (termux-packages @ HEAD this session) as the template, with these
  deviations: `CONFIG_STATIC=y` (static — no libc dependency, no
  `DT_NEEDED`, runs from any exec-allowed dir), `CONFIG_PREFIX=
  /data/data/dev.rustdroid.ide/files/usr` (the baked prefix, same
  philosophy as the toolchain RPATH), network **servers** dropped
  (`telnetd`, `ftpd`, `httpd`, `tftpd` — attack surface we do not need),
  client utilities and full ash with job control kept
  (`CONFIG_ASH_JOB_CONTROL=y`, `CONFIG_SH_IS_ASH=y`).
- **Patches**: Termux's path-reparenting patches
  (`0002-hardcoded-paths-fix` et al.) are the template — retargeted from
  `@TERMUX_PREFIX@` to a build-time `@RUSTDROID_PREFIX@` substitution, the
  same pattern `patches/post-vendor/0001` uses for `openssl-probe`
  (DEVIATIONS.md lineage). Their compiled binaries are NOT used (hardcoded
  to `/data/data/com.termux/...`) — only recipe/patch knowledge, exactly
  like the rust-toolchain build started from Termux's `rust` recipe.
- Output: one static `busybox` binary (~1 MB) + our config + patches +
  build script, uploaded as the `rustdroid-busybox-aarch64` Actions
  artifact.

### 7.3 Distribution — through the EXISTING install pipeline, generalized

BusyBox ships **inside the app bundle** (the ~117 MB
`rustdroid-app-bundle-aarch64.zip` the app already downloads): the bundle
gains `busybox` + manifest entries; `ToolchainDistro.expectedEntries` and
the manifest json gain the busybox record (size + sha256, same per-entry
pattern as the tarballs); `ArtifactExtractor` installs it to
`$PREFIX/bin/busybox` and creates `$PREFIX/bin/sh`, `$PREFIX/bin/ash`
symlinks in its existing post-pass link-resolution phase (zip entries
can't carry symlinks portably; the extractor already owns a link pass).
`ToolchainVerifier` gates it (§6.5). **No second install system**: same
downloader, same transaction/swap/rollback, same verifier loop, same
ready marker. `ToolchainPaths` gains `busybox`/`sh`/`ash` paths.
New bundle tag: `toolchain-1.85.0-aarch64-bb1.38.0` (self-describing;
`ToolchainDistro.RELEASE_TAG` + `SHA256` + `expectedEntries` bumped
together, per the existing pinning discipline). The toolchain tarballs
are re-assembled from the existing dist — no rust rebuild needed.

### 7.4 GPL-2.0 obligations (managed, per brief)

- `LICENSES/busybox-GPL-2.0.txt` in the repo.
- **Complete corresponding source** for the exact shipped build, as a
  release asset on the same GitHub release as the bundle: a
  `busybox-1.38.0-src.zip` containing the pristine upstream tarball, our
  `busybox.config`, the patch set, and the build script (the inputs that
  deterministically produce the shipped binary). Published by the extended
  `publish-release.yml`. This reuses the existing release infrastructure,
  as the brief anticipates — incremental work, not new machinery.
- Mere aggregation (separate executable via `execve`, no linking) keeps
  the app's Kotlin/Apache-2.0/MIT code out of GPL-2.0's reach. The busybox
  binary is never `dlopen`ed, linked, or its source compiled into any app
  or library module.
- F-Droid: everything builds from source in-repo/CI; the GPL source asset
  is end-user compliance, not a build input.

### 7.5 In-app license surface

Settings → About becomes Settings → **Licenses**: a list (component,
license, upstream URL, vendored commit where applicable) with full license
text on tap — covers sora-editor (LGPL-2.1), the textmate grammar (MIT),
the Mozilla CA bundle (MPL-2.0), Rust (MIT/Apache-2.0),
terminal-emulator/terminal-view (Apache-2.0, with the Termux/jackpal
attribution line), and BusyBox (GPL-2.0, with the source-asset URL of the
release it shipped in). Text files live in `assets/licenses/`; the screen
is a simple list + detail composable. This is a new Settings section, not
a rewrite of Settings.

## 8. App integration

### 8.1 UI placement — new screen, not a console merge

`Routes.TERMINAL = "terminal"`, reachable from the Home toolbar (terminal
icon) — a first-class destination, **not** a mode of the Editor console.
Rationale (brief): the Editor console is a tagged diagnostic stream
(line events, problems-panel navigation, stdin bar); a terminal is a
character-grid renderer with cursor addressing, resize semantics, ANSI
state. Different rendering models, different data flow (StateFlow<Console
lines> vs emulator cell buffer + invalidate), different input model.
`TerminalScreen` hosts the vendored `TerminalView` (Android View) behind
`AndroidView` interop — the exact pattern the sora-editor integration
uses, including its hard-won gesture lessons: no Compose draggable
overlays on the interop area (the v0.1.3 drawer-gesture fix), terminal
scrolling handled inside the View.

`TerminalViewModel`: session list (create/switch/close), one
`TerminalSession`+`TerminalSessionController` per session, survives
rotation via ViewModel; the FGS (if adopted, §11.2) owns the coroutine
scope, not the VM.

### 8.2 Environment

New `runtime/terminal/TerminalEnv.kt` — **derived from**, not duplicating,
`ProcEnv`: same HOME/CARGO_HOME/LD_LIBRARY_PATH/TMPDIR/RUSTDROID_PREFIX/
CA-file channels (identical strings — one source of truth in ProcEnv,
TerminalEnv composes and overrides), with terminal deltas:
`TERM=xterm-256color` (the vendored emulator implements the xterm 256-color
subset; `TERM=dumb` would disable half the output the toolchain emits),
keep `LC_ALL=C` (stable sort order for tools; the UTF-8 path is the pty's
IUTF8 + the emulator's decoder, not locale), `CARGO_TERM_COLOR=never`
unchanged (cargo's own color choice is unrelated to the terminal's
capability), `PATH` gains nothing new (`$PREFIX/bin` already leads).
Shell: `$PREFIX/bin/sh` (busybox ash), argv `["sh"]`, cwd `$HOME`.

### 8.3 Foreground service (open decision — see §11.2)

If adopted: `TerminalService` (foregroundServiceType `dataSync` — the same
type the manifest already declares for `ToolchainInstallService`; type
enforcement is off at targetSdk 28 per the manifest's own comment),
started while ≥ 1 session is alive, notification "Terminal session
running", stops when the last session closes. No session-persistence
claims in UI or docs in either case.

## 9. CI changes

| Workflow | Change |
|---|---|
| `android.yml` | (a) Path triggers gain `android/terminal-emulator/**`, `android/terminal-view/**`. (b) Run `:terminal-emulator:testDebugUnitTest` (upstream suite + our UTF-8 test). (c) New step: host JNI harness (clang + ASan + mock JNIEnv; §6.2). (d) `:app:assembleDebug/Release` now also builds/links the two vendored modules (arm64-v8a only). |
| `busybox.yml` (new) | Manual dispatch + push to `busybox/**`: NDK r27c static build, SHA-256 pin of the source tarball, static-ELF/AArch64/no-PT_INTERP verification, applet-name smoke via `strings`, artifact `rustdroid-busybox-aarch64`. ~3 min. |
| `publish-release.yml` | Consumes BOTH the toolchain dist artifact (main.yml) and the busybox artifact (busybox.yml) when assembling the app bundle; bundle manifest v2 (busybox entry + symlink list + per-entry sha256); publishes `busybox-1.38.0-src.zip` (GPL §7.4) alongside; SHA256SUMS.txt covers all assets. |
| `main.yml` | **No change.** The rust build is untouched — busybox is decoupled so bundle iteration never costs a 2.5 h toolchain rebuild. |

## 10. Repo layout after Phase 4

```
├── LICENSES/                          # NEW: per-component license texts
│   ├── busybox-GPL-2.0.txt
│   ├── terminal-emulator-Apache-2.0.txt
│   └── terminal-view-Apache-2.0.txt   # (+ existing components' texts)
├── THIRD_PARTY.md                     # NEW: inventory + pinned commits
├── busybox/                           # NEW: build.sh, busybox.config, patches/
├── docs/phase3-terminal-architecture.md   # this document
├── android/
│   ├── settings.gradle.kts            # + :terminal-emulator, :terminal-view
│   ├── app/                           # + runtime/terminal/, ui/terminal/, assets/licenses/
│   ├── terminal-emulator/             # vendored @ 3b66f87 + patch set (documented)
│   └── terminal-view/                 # vendored @ 3b66f87 (build script only)
└── (toolchain build files unchanged)
```

## 11. Assumptions and open decisions (explicit — no silent guesses)

**Assumptions** (stated, will be validated during implementation):

1. The pinned commit `3b66f87` is the vendoring baseline; upgrades are a
   re-vendor with patch-set review, not a merge.
2. `close_range` fallback design (§4.4) covers kernel < 5.9 via the
   pre-scan close-list; ART's O_CLOEXEC default makes the residual
   inheritance risk no worse than Java `ProcessBuilder` today.
3. Android's init reaps orphaned zombies promptly; teardown budget (3
   sweeps × 50 ms) tolerates slower reaping because of the zombie
   exclusion (§12.1 fix).
4. BusyBox 1.38.0 builds cleanly with NDK r27c at API 24 with the Termux
   patch set adapted; any patch drift fails the busybox workflow loudly
   (patch application is fatal, not warn — the repo's post-vendor lesson).
5. The existing bundle's zip+tar.xz structure carries the additional
   `busybox` entry without format changes (manifest v2 is additive).

**Open decisions for the reviewer** (with recommendations):

1. **`TerminalService` FGS now or deferred?** Recommend: include now (a
   terminal whose sessions die on routine backgrounding undermines the
   feature; the service pattern is proven in this repo), but it is
   scope-additive — defer is defensible for v0.
2. **ash profile/rc files** (`$PREFIX/etc/profile` machinery): recommend
   defer to a follow-up (v0 launches plain interactive `sh`).
3. **BusyBox applet set**: recommend the pruned-server config (§7.2);
   reviewer may want `less`/`vi` on/off confirmation (`vi` stays on —
   in-terminal quick edits are a real terminal use case).
4. **Extra-keys row contents** (soft keyboards lack Ctrl/Esc/Tab/arrows):
   recommend minimal v0 row: Esc, Tab, Ctrl (toggle), arrows, PgUp/PgDn.
5. **Bundle tag string**: `toolchain-1.85.0-aarch64-bb1.38.0` (§7.3) —
   bikesheddable.
6. **Multiple sessions UX**: v0 = simple session tab strip + FAB new
   session + swipe-close; no Termux-style drawer.

## 12. Sequencing & pre-plan change log

### 12.1 Landed before this plan (per the brief's explicit order)

- `ProcTree.terminateTree` zombie fix + 3 regression tests (§6.1) — the
  brief ordered this fixed *before* Phase 3/4 work since the teardown
  builds on this file. 193 JVM tests, 0 failures; regression proven
  discriminating (fails on pre-fix code).
- Version bump to `0.1.6` (versionCode 7), DEVIATIONS.md §9, THIRD_PARTY.md
  created, README known-limits updated.

### 12.2 Implementation order (each step lands green on `main`)

1. Vendor the two modules at the pinned commit, our build scripts, license
   files, THIRD_PARTY.md entries — **no source patches yet**; CI builds
   the APK with the modules, upstream tests green.
2. JNI defect fixes + fork-window restructure + lib rename + `RD_FORK`
   test seams + host harness (§6.2) — all defects now regression-pinned.
3. UTF-8 split test (§6.3); `ProcTree.sessionMembers` + controller + tests
   (§6.4).
4. `TerminalEnv` + `TerminalScreen`/VM + navigation + extra-keys row.
5. `busybox.yml` + config/patches; verifier extension + bundle assembly
   (`publish-release.yml`) + `ToolchainDistro` bump; GPL source asset.
6. On-device validation checklist executed, DEVIATIONS.md postmortem,
   README phase table update (Phase 4 row).
