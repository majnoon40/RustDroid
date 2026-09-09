# Phase 4 — Native Terminal: Architecture Plan (pre-implementation)

> **Status of this document**: architecture plan for the pre-implementation
> review gate. No Phase-4 implementation code has been written. The only code
> change landed before this plan is the `ProcTree.terminateTree` zombie-state
> fix ordered separately in the task brief (see §12.1) — it is a live bug in
> `main` independent of the terminal work and Phase 4's teardown builds on
> that file.
>
> **v2 (post-review)**: revised after the adversarial review
> (`docs/Report.txt`, verdict APPROVE WITH CONDITIONS; point-by-point
> response in `docs/phase3-review-response.md`). All conditions adopted;
> the substantive changes: setsid semantics corrected and teardown
> guarantee rescoped with union discovery (§5.2/§5.3/§5.4), close_range
> dropped from the child path (§4.4), teardown reordered to
> close-master→grace→freeze→kill (§5.3), reader-join-before-close and
> EIO-as-normal-end specified (§5.3), SIG_DFL disposition reset added
> (§4.4), `unitTests.returnDefaultValues` carried (§4.1), BusyBox
> re-pinned to stable 1.36.1 (§7.2), runCatching guards + SIGCONT
> after budget (§5.3), manifest-v2 symlink traversal guard (§7.3),
> backpressure and threat-model sections (§8.4/§8.5). Change log: §12.3.
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
| `toysh` not a reliable POSIX shell drop-in; Android keeps `mksh` | android.googlesource.com *"Android's shell and utilities"*: *"Since IceCreamSandwich Android has used mksh as its shell… Marshmallow almost everything is supplied by toybox instead"*; landley.net toybox news shows toysh still maturing (milestone framed as *"toysh making it all the way through toyroot's init script"*); AOSP RISC-V port (plctlab) ships mksh as the shell. | **Confirmed.** AOSP shipping Toybox for utilities while keeping mksh for the shell is direct evidence the project does not consider toysh production-ready as a system shell. BusyBox `ash` (Alpine's system shell, decades of deployment) is the choice — pinned to the **stable** line (§7.2, review P1-6). |

**Pin re-verification (post-review, this session)**: every "@ `3b66f87`" claim in this
plan was re-checked against the actual commit (blob-partial clone, checkout):
the full SHA exists; no `NOTICE*` file exists anywhere in either module;
`rg "termux\.shared|TermuxConstants"` over both modules returns zero matches;
`termux.c`'s last upstream touch is `438cd73` — **author date
2022-11-16, committer date 2024-09-26** (the patch was authored in 2022
and rebased into history two years later; the v1 plan cited the author
date, the v2 draft over-corrected to the committer date — both are real
git dates and neither changes the drift conclusion); the test
sources are 19 files = 18 test classes + the shared `TerminalTestCase` base;
upstream `terminal-emulator/build.gradle` contains `unitTests.returnDefaultValues
= true` (carried into our build scripts), 4-ABI `abiFilters`, `-Werror` cFlags,
and dependencies of exactly `androidx.annotation:annotation:1.9.0` + JUnit.
Details: `docs/phase3-review-response.md` §1.

## 3. Licensing architecture

### 3.1 Component inventory and licenses

| Component | License | How consumed | Compliance action |
|---|---|---|---|
| `terminal-emulator` (Termux) | Apache-2.0 | **Vendored source**, Gradle module, pinned commit `3b66f8799635a4dba4a206563048ff0e6792c487` | `LICENSES/terminal-emulator-Apache-2.0.txt` + THIRD_PARTY.md entry (upstream repo, commit hash, origin = jackpal Android-Terminal-Emulator); no NOTICE file exists upstream in either module (verified) |
| `terminal-view` (Termux) | Apache-2.0 (includes `support/PopupWindowCompatGingerbread.java` from AOSP, Apache-2.0, header preserved) | Same | Same |
| BusyBox 1.36.1 (stable; re-pinned v2, §7.2) | **GPL-2.0-only** | Separate static executable, `execve`'d | `LICENSES/busybox-GPL-2.0.txt`; complete corresponding source published as a release asset (§7.4); in-app license screen (§7.5) |
| Toybox | 0BSD | **Not used** (decision §7.1) | — |
| RustDroid app (Kotlin) | MIT (unchanged) | — | BusyBox is *mere aggregation*: a separate process, no shared address space, no linking — GPL-2.0 does not reach the app code |
| sora-editor, textmate grammar, Mozilla CA bundle, Rust, NDK | (existing, unchanged) | — | recorded in the new THIRD_PARTY.md |

The repo's overall MIT license is **not** changed by any of this.

### 3.2 No-`termux-shared` guarantee (explicit confirmation)

`termux-shared` is not vendored, not referenced by any Gradle module we ship,
and — verified directly at the pinned commit — **neither `terminal-emulator`
nor `terminal-view` contains a single reference to `com.termux.shared` or
`TermuxConstants`** (`rg -l "termux.shared|TermuxConstants" terminal-emulator/
terminal-view/` → zero matches). Our `settings.gradle.kts` will include
only `:app`, `:terminal-emulator`, `:terminal-view`. The two vendored
modules' only external dependencies are `androidx.annotation:annotation` and
each other (`terminal-view` → `terminal-emulator`) — verified in both
upstream build files at the pin. There is therefore **no code path, direct or
transitive, that pulls in `termux-shared`**; the GPLv3 subtree of the Termux
repositories is not present in our build at all.

Precision (review Part 3; verified at the pin): `termux-shared` is MIT
overall; the **GPLv3-only** scope is `src/main/java/com/termux/shared/termux/*`
*unless specifically overridden* — `TermuxConstants.java` and
`TermuxPropertyConstants.java` are specifically **MIT**. (`com/termux/shared/
file/filesystem/*` is GPLv2+Classpath-exception — ojluni-derived — and
`StreamGobbler.java` is Apache-2.0 — libsuperuser-derived.) The exclusion
decision is unchanged; the stated reason is now precise.

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
  (termux-app HEAD at vendoring time, 2026-08-24; re-verified to exist at
  checkout this session). The JNI code we patch (`termux.c`) is unchanged
  upstream since commit `438cd73` (the winsize-pixel fix — author date
  2022-11-16, committer date 2024-09-26, see §2), so
  drift risk is low.
- **Build-script obligations from upstream, carried verbatim into our
  `build.gradle.kts`** (review P1-5 — without the first, the vendored test
  suite fails immediately): `testOptions { unitTests.isReturnDefaultValues =
  true }` in BOTH modules; `compileOptions` raised from upstream's 1.8 to
  Java 17 (safe raise, recorded as our divergence). Upstream cFlags
  (`-std=c11 -Wall -Wextra -Werror -Os …`) are kept in OUR build script —
  if NDK r27c's newer clang produces warnings, we drop `-Werror` or add
  targeted `-Wno-` flags **in the build script**, never patching upstream C
  for warning cosmetics. Upstream's maven-publish blocks and `abiFilters`
  are dropped (arm64-v8a only, `ndk { abiFilters += "arm64-v8a" }`).
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
  patch set — §4.3/4.4/4.5, `JNI.java` loadLibrary line). Upstream ships
  `proguard-rules.pro` boilerplate (comments only, no active rules) in both
  modules — vendored as-is; the real JNI keep rules live in the APP's
  `proguard-rules.pro` (§4.2).

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
2. **Child (async-signal-safe calls only)**: `sigprocmask(SIG_SETMASK,
   &empty_set, NULL)` (explicit empty-mask form — unblock everything) →
   **reset every signal disposition to SIG_DFL**: a `sigaction(i, NULL,
   {SIG_DFL})` loop over signals 1..64 (SIGKILL/SIGSTOP reject harmlessly;
   `sigaction` is async-signal-safe per POSIX and a thin syscall wrapper in
   bionic). execve resets *caught* signals but explicitly preserves
   **SIG_IGN**, and the ART runtime ignores several signals — SIGPIPE is the
   classic — so without this loop the shell and everything it runs inherit
   `SIGPIPE == SIG_IGN` and pipelines misbehave exactly like the classic
   JVM-subprocess footgun: `yes | head -1` never terminates,
   `cargo build 2>&1 | head` hangs (review P2-7) → `close(ptm)` →
   `setsid()` → `open(pts)` (no `O_NOCTTY`, so it becomes the controlling
   terminal of the new session) → `dup2(pts, 0/1/2)` → close leftover fds
   **from the parent's pre-scanned close-list only**: pure `close(2)` per
   fd, unconditionally allowlisted → `chdir(cwd)` (pure syscall; on failure
   `write(2)` the error — no stdio — and continue to exec, matching
   upstream behavior) → `execve(absPath, argv, envp)` — **`envp` replaces
   the environment wholesale**, so `clearenv`/`putenv` are unnecessary. On
   exec failure: `write(2, …)` then `_exit(127)`.

   **`close_range(2)` is NOT used in v0** (review P0-2): kernel version
   does not determine syscall availability on Android — since Android O,
   app processes run under a seccomp-bpf **allowlist** derived from
   bionic's supported set, and a syscall outside it does not return
   ENOSYS; it delivers **SIGSYS and kills the caller**. In the forked
   child that means a silently dead shell: the parent holds a valid master
   fd and PID, sees no error, and the terminal opens blank — the
   device-dependent, no-diagnostic bug class this repo exists to avoid.
   close_range postdates some deployed seccomp policies. The pre-scanned
   close-list is sufficient (see residual risk). The only safe probe
   shape — if this is ever revisited — is a throwaway forked child whose
   sole job is to attempt the syscall and `_exit(0)`, with the parent
   interpreting WIFSIGNALED/WTERMSIG == SIGSYS; **probing from the parent
   process is forbidden** (a blocked syscall kills the app itself).
   Recorded here so nobody re-adds it casually.
3. **Parent**: returns `ptm` and the child pid as today.

**Residual risk, stated honestly**: a fd opened by another JVM thread after
the pre-scan and before `fork` escapes the close-list unless it is
`O_CLOEXEC`. ART marks runtime fds `O_CLOEXEC` by default, our ptmx is opened
`O_CLOEXEC`, and Java `ProcessBuilder` (the current build path) closes no
extra fds at all — so this is strictly no worse than the status quo. With
`close_range` rejected on seccomp grounds, that is the whole of the residual:
state it, ship it, revisit only behind the forked-probe design above. A
second residual, equally honest: the whitelist-grep and malloc interposer
below prove OUR code is clean, not that bionic's `sigaction/close/dup2/
setsid/open/chdir/execve` wrappers are pure syscall shims — they very likely
are (review Part 2 agrees), and the on-device soak (§6.6) is the only test
that would catch a surprise. Necessary, not sufficient — no overclaiming.

**Enforcement**: (a) the child path is restructured into one short function
containing only whitelisted calls, reviewable by reading it; (b) a CI check
scripts a whitelist-grep over the child-path function body (forbidden
identifiers: `opendir`, `readdir`, `closedir`, `malloc`, `calloc`, `free`,
`strdup`, `putenv`, `clearenv`, `asprintf`, `perror`, `printf`, `fflush`,
`execvp`, `exit`, **`syscall`**, **`close_range`**); (c) the malloc interposer
in the test harness (§6.2) counts allocations and fails the test if any
occur between the fork marker and exec. Automation + review, stated as such
— no overclaiming. The harness is glibc/x86 and carries **no Android
seccomp policy**: it is an oracle for OUR discipline, never for platform
syscall availability (review Part 6) — that belongs to the §6.6 device
matrix.

### 4.5 Additional native functions: process-group signaling

The session teardown (§5) needs `killpg(2)`. We add one JNI function to the
same vendored `termux.c` + one method to the vendored `JNI.java`:
`sendSignalToProcessGroup(int pgid, int sig)` → `killpg(pgid, sig)`. It is
recorded in THIRD_PARTY.md as a RustDroid addition on top of the pinned
commit. (Rationale for JNI over `android.os.Process`: we already own a
native module; `killpg` semantics are unconditional and API-level
independent; identity validation before every signal stays in Kotlin, where
the /proc discipline lives — and every Kotlin-side call site wraps the
signal in `runCatching` so an ESRCH/EPERM race can never crash a teardown,
review condition 8.)

### 4.6 Upstream test suite

`terminal-emulator`'s JUnit test sources — **19 files: 18 test classes + the
shared `TerminalTestCase` base** (TerminalTest, ResizeTest, UnicodeInputTest,
…) — are vendored and run as a module in CI
(`:terminal-emulator:testDebugUnitTest`), **behind `testOptions {
unitTests.isReturnDefaultValues = true }`** carried from upstream's build
script (review P1-5: the tests touch Android framework stubs; without the
flag they throw "not mocked" and the suite fails at step 1). They are pure
JVM otherwise and pin the emulator's behavior before our changes. Our UTF-8
split-across-reads regression test (§6.3) joins them. `terminal-view` has no
upstream tests (the flag is carried there too — harmless, and it keeps the
modules symmetric).

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
its semantics unchanged, for the build-cancel path — and the deeper reason
it must remain (review Part 5, stated outright): **cargo runs in the app's
own session and process group** (spawned via `ProcessBuilder`, no
`setsid`), so a session-keyed teardown on the build path would enumerate
*the app process itself* and kill it. The two kill models are disjoint by
necessity: ppid-walk for same-session children, session/process-group for
terminal children that left the app's session.

### 5.2 Discovery: three keyed sets, unioned — with corrected semantics

**Kernel semantics, stated correctly (review P0-1)**: `setsid(2)` creates a
NEW session whose SID equals the caller's own PID and makes the caller its
leader. A process that calls `setsid()` therefore has `sid == itsOwnPid`,
NOT `shellPid` — it leaves `sessionMembers(procRoot, shellPid)` immediately
and permanently. What IS true (and is the genuine advantage over the ppid
walk): **reparenting to init does not change the SID** — an orphaned but
still-attached process stays visible — and `/proc/<pid>/stat` **field 7
(`tty_nr`)** keeps the controlling-terminal device number even after
`setsid()`, unless the process explicitly detaches from the tty. The v1
claim that a `setsid`'d daemon "keeps `sid == shellPid` forever" inverted
`setsid(2)`; it is gone, and a regression test now pins the true semantics
so the misconception cannot be re-introduced (§6.4).

**The guarantee, scoped honestly (review Part 5)**: *everything still
attached to the terminal dies on teardown; deliberately detached processes
survive **by design*** — that is what `nohup`/`setsid` are for, and a
terminal that killed them would be wrong Unix. Beyond the guarantee,
discovery is best-effort through a **union of three sets**:

```
members = sessionMembers(procRoot, shellPid)   // sid == shellPid (field 6)
        ∪ descendants(procRoot, shellPid)        // the existing ppid walk
        ∪ ttyMembers(procRoot, ptsDevice)        // tty_nr == pts dev (field 7)
```

- `sessionMembers` — pure function, synthetic-/proc testable, reusing the
  file's identity machinery: `/proc/<pid>/stat` field 6 parsed with the
  same last-`)` discipline as starttime; members returned as
  `ProcessId(pid, starttime)` (PID-reuse-safe identity, reused as-is).
- `descendants` — the existing `ProcTree` walk, unchanged; catches
  not-yet-reparented escapees and plain children alike.
- `ttyMembers` — same stat read, **field 7** (`tty_nr`, the controlling
  terminal's device number): the strongest signal for a terminal, because
  it survives `setsid()` while the pts is still held. The pts device number
  is computed in the PARENT at PTY creation — `fstat()` the slave after
  `grantpt/unlockpt`, take `st_rdev` — and handed to the controller
  (a new JNI return value alongside the master fd and pid; §4.5 records
  it in THIRD_PARTY.md).

Union, dedupe by `ProcessId`, identity revalidation before **every**
signal, zombie exclusion, bounded sweeps — the full `ProcTree` discipline
applies unchanged. Cost note (review Part 2): the union snapshot reads each
`/proc/<pid>/stat` once into a cached identity map; sweeps re-validate only
captured PIDs — never a full `/proc` re-scan per sweep.

### 5.3 Teardown sequence (`TerminalSessionController`) — v2, reordered

The v1 sequence SIGSTOPped everything and *then* sent SIGHUP "so the shell
gets to forward the hangup" — but a stopped process never runs signal
handlers: the freeze made the graceful step dead code, and the two goals
were mutually exclusive (review P1-3). The v2 sequence picks the real-
terminal ordering — **kernel-delivered hangup first, freeze only for the
kill phase**:

1. **Quiesce input**: no new writes to the master fd from this point
   (input writes already guarded by `runCatching`; a post-close write
   surfaces EBADF and is ignored).
2. **Stop the output reader BEFORE any close** (review P1-4): the reader
   thread `poll()`s {master fd, wakeup pipe}; teardown writes the wakeup
   pipe, the reader exits its loop, and the controller **joins it with a
   bounded timeout**. Closing the master while a reader is blocked in
   `read(2)` is the classic use-after-close: the fd number becomes
   immediately reusable, and another thread's socket/file/ptmx can steal
   it — the reader then drains an unrelated descriptor into the terminal.
   The master fd is only ever closed by the session's I/O-owner thread
   after the reader is joined.
3. **Close the master fd → the kernel delivers SIGHUP to the terminal's
   foreground process group** — correct semantics, for free, with no
   signal the shell must be running to forward. (On Linux, once the child
   side is gone, reads on the master return **EIO** — the reader treats
   EIO/EOF as normal session end, never an error; review Part 2.)
4. **Bounded grace** (300 ms default, 200–500 ms range): an interactive
   shell may run its EXIT/`trap` cleanup and forward the hangup to its
   jobs while it still can.
5. **Snapshot via union discovery** (§5.2) — identity map cached for the
   whole teardown.
6. **Freeze**: SIGSTOP every captured member that still matches its
   identity — nothing new spawns, nothing escapes between discovery and
   the kill. (Rediscovery while the shell PID still provably belongs to
   the original process — exactly like `terminateTree`'s step 5 — catches
   late spawns before the sweep.)
7. **SIGKILL frozen members**, then bounded identity-checked,
   zombie-excluded sweeps with early-return (same budget discipline as
   `terminateTree`: 3 sweeps / 50 ms).
8. **SIGCONT any freeze survivor still alive after the budget is
   exhausted** (review condition 8): we stopped it, so we must never leave
   it stopped forever. SIGKILL wins over SIGCONT for dying members; a
   survivor that ignored SIGKILL is SIGCONTed and left to the OS.

Every injected `signal`/`killpg`/`destroy` lambda call is wrapped in
`runCatching`: an ESRCH/EPERM race between identity check and signal — or
   a throwing test lambda — must never crash a teardown (same guard added
   to `ProcTree.terminateTree` itself).

The controller is a pure-Kotlin, /proc-driven object with injected
`signal`/`killpg`/`closeMaster`/`stopReader` lambdas — unit-tested with a
Recorder exactly like `ProcTreeTest` does (ordering assertions: no SIGSTOP
precedes the master-close/SIGHUP-equivalent for a PID; the grace delay is
observed; SIGCONT fires after budget), plus a synthetic-/proc suite for
the union discovery (§6.4).

### 5.4 What survives what (honesty section — no overclaiming)

| Event | Terminal sessions | Why |
|---|---|---|
| App foregrounded / screen rotation | **Survive** | Process alive; activity `configChanges` already handled (manifest). |
| App backgrounded briefly | Usually survives (cached process) | No guarantee — Android may kill cached processes at any time under memory pressure. |
| App swiped away / process death | **Sessions end.** The pty master fd closes; the kernel sends SIGHUP to the terminal's foreground process group; attached shell and children die. A **deliberately detached** survivor (`nohup`/`setsid` + tty released) lingers by design until the OS reclaims it — stated as-is, no "survives death" claim anywhere in UI or docs. A `setsid`'d process still holding the tty is caught by the tty_nr union during an *in-app* teardown (§5.2), but nothing can catch it after the app process itself is gone. | No session-persistence magic exists in this design. |
| Foreground service running (`TerminalService`, §8.3) | Backgrounding survived *much* more reliably (FGS is not cached-killable under normal pressure) | Still not process-death survival — and no claim of it. |

Phase 4 v0 ships **without** claiming session persistence. The FGS
question is resolved per the review (§11.2): `TerminalService` ships in
v0.

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
  count via a pipe before `_exit`). **Caveat, stated (review Part 6):**
  the interposer proves OUR code doesn't allocate — it says nothing about
  bionic internals; it is necessary, not sufficient, and the §6.6 on-
  device soak under memory pressure is the real test.
- **Signal-disposition reset (review P2-7)**: the harness parent sets
  `SIGPIPE` to `SIG_IGN` (mimicking ART), spawns a tiny `sigprobe` helper
  through the fixed `create_subprocess`; the probe `sigaction(SIGPIPE,
  NULL, &old)` and writes the observed disposition to stdout; the test
  asserts **SIG_DFL**. (The companion on-device check: `yes | head -1`
  must terminate, §6.6.)
- **fd-leak assertion**: count `/proc/self/fd` entries (the host is
  Linux) before/after 200 create/destroy cycles of real PTY sessions
  through `create_subprocess` + close — fails on any fd-number growth
  (covers the master-fd close discipline at the C layer).
- Happy-path test: spawn a real `/bin/sh -c 'echo ok'` through the fixed
  `create_subprocess`, assert output through the pty master and clean
  teardown.

These tests run on the host (the JNI layer is plain POSIX; the harness
stubs the JNI boundary). No device required, deterministic, in CI on every
push touching the vendored modules. **The harness is glibc/x86 and carries
no Android seccomp policy — it is an oracle for our code discipline only,
never for platform syscall availability (review Part 6); that belongs to
the §6.6 device matrix.**

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
malformed-sequence handling. **Decoder-state assertions (review Part 6):**
the test additionally asserts the decoder's intermediate state after each
split reassembly — `mUtf8ToFollow == 0` and the pending byte buffer empty —
so a decoder regression is distinguishable from a grid-rendering change.

### 6.4 Session-discovery and teardown tests (JVM)

- **Union discovery, expectations CORRECTED (review P0-1 / Part 6)**:
  synthetic /proc —
  - plain child (sid == shellPid, ppid == shellPid): appears in the union;
  - grandchild: appears;
  - **`setsid`'d process, tty still held** (`sid == itsOwnPid`, `ppid == 1`,
    `tty_nr == ptsDev`): **assert `sessionMembers` alone does NOT return
    it** — this pins the true kernel semantics so the v1 misconception
    cannot be re-introduced — **and the union DOES return it** (via
    tty_nr);
  - **truly detached escapee** (`setsid` + tty released: `tty_nr == 0`):
    invisible to all three sets — and asserted to be, because surviving
    is **by design** (§5.2);
  - unrelated process with a different sid and different tty: excluded;
  - vanished-mid-walk entry: dropped;
  - PID-reuse inside the captured set: never signaled.
- `TerminalSessionController.teardown`: Recorder-based —
  - the reader is stopped and joined **before** the master-fd close
    (ordering recorded per step);
  - master close precedes every SIGSTOP (no freeze before the
    SIGHUP-equivalent — review P1-3) and a grace delay is observed
    between them;
  - SIGCONT is sent to freeze survivors after the sweep budget is
    exhausted (review condition 8);
  - a `signal`/`killpg` lambda that throws (ESRCH race) does not crash
    teardown — `runCatching` guard;
  - zero signals to non-session processes; bounded sweeps; early return
    on zombie-only remainder.
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

**Manifest-v2 symlink safety (review P2-8)**: manifest v2's `symlinks`
section is not created by a blind "reuse the existing link pass". Every
link target is resolved through **`Fs.resolveChild`** (lexical: rejects
absolute paths and `..` components) **and** `Fs.requireInside` (canonical
containment through existing symlinked ancestors — the same guard the
extractor applies to tar entries) before creation. A JVM regression test
pins the rejections: a manifest entry targeting `../../etc/passwd`, an
absolute `/data/local/tmp/x`, and a
`../../../../../../data/data/com.other/files/x`-style traversal must all
fail **loud and install-blocking**, never warn-and-continue.

### 6.6 On-device validation checklist (manual, mirrors Phase 1/2 culture)

adb session checklist recorded in DEVIATIONS.md when executed:

- **Bring-up device matrix (review P0-2 / Part 6)**: at least one device
  each on Android 9, 11, and 14 — terminal opens, shell execs, output
  flows. The host JNI harness runs glibc/x86 with no Android seccomp
  policy and is **not** an oracle for syscall availability; only this
  matrix is.
- **SIGPIPE disposition (P2-7)**: `yes | head -1` terminates;
  `find | head` does not hang — the shell pipelines the disposition reset
  exists to protect.
- **Teardown semantics — expectations CORRECTED (P0-1)**: a `setsid
  sleep 300` escapee **still holding the tty** is caught by union teardown
  (tty_nr, §5.2); a **truly detached** escapee (tty released) **survives
  by design** — asserted, documented, not lamented. Interactive ash +
  job control (`sleep 100 &`, `jobs`, `fg`).
- **Session stress (P1-4)**: 200 open/close cycles while a background
  download runs — no cross-talk, no garbage bytes in any session,
  `Thread.activeCount` stable, `/proc/self/fd` count back at baseline
  after the run.
- **Soak under memory pressure (Part 2)**: hundreds of session cycles
  with low available memory and background load — the only test that can
  surface a fork-window allocator surprise (the malloc interposer is
  necessary, not sufficient). Explicitly budgeted, not aspirational.
- UTF-8 split output (`printf` with cut sequences), resize behavior,
  backgrounding behavior, app-death SIGHUP behavior, 64 KB burst output,
  long `cargo run` inside the terminal (exercises the whole env chain).

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

### 7.2 Build (CI, new `busybox.yml`) — v2, re-pinned (review P1-6)

- **Version pin**: BusyBox **1.36.1 — the latest release busybox.net
  marks stable** (its news listing: "1.38.0 (unstable), 1.37.0 (unstable),
  1.36.1 (stable)", re-fetched this session). v1 pinned 1.38.0, which is
  upstream-labelled unstable — undercutting the shell-maturity argument
  that justified BusyBox over Toybox in the first place. The v1 checksum
  happened to be correct (it matches busybox.net's published
  `busybox-1.38.0.tar.bz2.sha256`, as does Termux's recipe pin), but
  "correct checksum on an unstable release" is not the pin we want.
  Source: `https://busybox.net/downloads/busybox-1.36.1.tar.bz2`,
  SHA-256 pinned from busybox.net's own
  `busybox-1.36.1.tar.bz2.sha256` (fetched and re-verified this session):
  `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314`.
  CI fails loudly on mismatch — no silent re-pin. Provenance recorded
  here, not inferred.
- **Toolchain**: NDK r27c clang, `aarch64-linux-android`, API 24 — the
  same pins as the Rust toolchain build (`env.sh`).
- **Config**: starts from Termux's `busybox.config`
  (termux-packages @ HEAD this session) as the template, with these
  deviations: `CONFIG_STATIC=y` (static — no libc dependency, no
  `DT_NEEDED`, runs from any exec-allowed dir), `CONFIG_PREFIX=
  /data/data/dev.rustdroid.ide/files/usr` (the baked prefix, same
  philosophy as the toolchain RPATH), network **servers** dropped
  (`telnetd`, `ftpd`, `httpd`, `tftpd` — attack surface we do not need),
  network **clients also dropped** (review P3-9: `wget`, `telnet`,
  `ftpget`, `ftpput`, `nslookup`, `ping`… — static bionic loses the
  dynamic NSS/DNS resolution path, so these applets may fail to resolve
  hostnames even with working connectivity; "wget is broken" reports are
  worse than no wget. Documented in the README known-limits section;
  revisit is a recorded follow-up, not a silent gap), and the shell
  settings pinned explicitly (review Part 4 — a shell without history or
  tab completion reads as broken regardless of POSIX correctness):
  `CONFIG_SH_IS_ASH=y`, `CONFIG_ASH_JOB_CONTROL=y`,
  `CONFIG_ASH_INTERNAL_GLOB=y`, `CONFIG_FEATURE_EDITING=y`,
  `CONFIG_FEATURE_EDITING_HISTORY=y`,
  `CONFIG_FEATURE_TAB_COMPLETION=y`.
- **Patches**: Termux's path-reparenting patches
  (`0002-hardcoded-paths-fix` et al.) are the template — retargeted from
  `@TERMUX_PREFIX@` to a build-time `@RUSTDROID_PREFIX@` substitution, the
  same pattern `patches/post-vendor/0001` uses for `openssl-probe`
  (DEVIATIONS.md lineage). Their compiled binaries are NOT used (hardcoded
  to `/data/data/com.termux/...`) — only recipe/patch knowledge, exactly
  like the rust-toolchain build started from Termux's `rust` recipe.
  (Termux's recipe at HEAD pins 1.38.0; retargeting to 1.36.1 may shift
  patch context — patch application stays **fatal, not warn**, the
  repo's post-vendor lesson.)
- Output: one static `busybox` binary (~1 MB) + our config + patches +
  build script, uploaded as the `rustdroid-busybox-aarch64` Actions
  artifact.

### 7.3 Distribution — through the EXISTING install pipeline, generalized

BusyBox ships **inside the app bundle** (the ~117 MB
`rustdroid-app-bundle-aarch64.zip` the app already downloads): the bundle
gains `busybox` + manifest entries; the manifest becomes **v2**: the
busybox record (size + sha256, same per-entry pattern as the tarballs)
**plus an explicit `symlinks` section** (name + target per link). Symlink
creation is no longer described as "the existing link pass, reused" (v1
hand-waved it; review P2-8): the link pass is generalized into a
manifest-driven creator guarded by §6.5's contract — every target passes
`Fs.resolveChild` (lexical) **and** `Fs.requireInside` (canonical
containment through existing symlinked ancestors) before
`ArtifactExtractor` creates it, and the traversal-rejection test pins the
rejections. (Zip entries can't carry symlinks portably — that is why the
manifest owns them.) `ArtifactExtractor` installs the binary to
`$PREFIX/bin/busybox` and creates `$PREFIX/bin/sh`, `$PREFIX/bin/ash`
from the manifest's symlink section; `ToolchainVerifier` gates it
(§6.5). `ToolchainDistro.expectedEntries` is bumped to match. **No
second install system**: same downloader, same transaction/swap/rollback,
same verifier loop, same ready marker. `ToolchainPaths` gains
`busybox`/`sh`/`ash` paths.
New bundle tag: `toolchain-1.85.0-aarch64-bb1.36.1` (self-describing;
`ToolchainDistro.RELEASE_TAG` + `SHA256` + `expectedEntries` bumped
together, per the existing pinning discipline). The toolchain tarballs
are re-assembled from the existing dist — no rust rebuild needed.

### 7.4 GPL-2.0 obligations (managed, per brief)

- `LICENSES/busybox-GPL-2.0.txt` in the repo.
- **Complete corresponding source** for the exact shipped build, as a
  release asset on the same GitHub release as the bundle: a
  `busybox-1.36.1-src.zip` containing the pristine upstream tarball, our
  `busybox.config`, the patch set, and the build script (the inputs that
  deterministically produce the shipped binary). Published by the extended
  `publish-release.yml`. This reuses the existing release infrastructure,
  as the brief anticipates — incremental work, not new machinery.
- Mere aggregation (separate executable via `execve`, no linking) keeps
  the app's Kotlin/Apache-2.0/MIT code out of GPL-2.0's reach — **stated
  as legal interpretation, not verified fact (review Part 3)**. It is
  the FSF's own stated position for separate programs communicating at
  arm's length via `execve` with no shared address space and no linking,
  and the same basis on which countless Android apps ship GPL binaries;
  GPLv2 §2's final paragraph explicitly permits aggregating independent
  works on a distribution volume, which covers busybox inside the bundle
  zip. The busybox binary is never `dlopen`ed, linked, or its source
  compiled into any app or library module. If this ever matters
  commercially, have a lawyer confirm.
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
a rewrite of Settings. **The BusyBox source-asset URL is a compliance
requirement, not a nicety (review Part 3)**: the bundle reaches
recipients through the app's own downloader, not the release page —
the GPLv2 source offer must be visible where recipients actually are,
and the Licenses screen is where that is.

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
rotation via ViewModel; the FGS (resolved: ships in v0, §11.2) owns the
coroutine scope, not the VM.

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

### 8.3 Foreground service — resolved per review: ship in v0

`TerminalService` (foregroundServiceType `dataSync` — the same type the
manifest already declares for `ToolchainInstallService`; type enforcement
is off at targetSdk 28 per the manifest's own comment), started while ≥ 1
session is alive, notification "Terminal session running", stops when the
last session closes. No session-persistence claims in UI or docs.

**Migration debt, recorded (review P3-10)**: `dataSync` is the wrong FGS
type for a terminal on any future targetSdk — type-appropriateness
enforcement starts at API 34, and API 35 adds a ~6 h/day cap on
`dataSync`. A terminal's eventual type is `specialUse` with a declared
justification. Correct today at targetSdk 28; recorded in DEVIATIONS.md
§10 and the repo's targetSdk-28 debt list so the migration blocker is
not discovered late.

### 8.4 Backpressure (explicit non-goal + design constraint)

No unbounded Kotlin-side output buffering — stated as a design
constraint, not an aspiration. The child writing to the pts **blocks**
when the reader falls behind: that is the kernel doing flow control for
us, and it is the correct behavior. The risk this forecloses is someone
"fixing" a perceived stall by buffering output in Kotlin — which turns
a 64 MB `cargo build --verbose` into an OOM. Scrollback is capped; the
emulator's buffers are bounded; the reader delivers to the UI at the
rate the UI can consume. A 64 MB burst must translate to bounded memory
plus a capped scrollback, never to an unbounded queue.

### 8.5 Threat model — what a terminal changes (security honesty)

Nothing here escapes the app sandbox — but a terminal is **strictly more
dangerous than a build button**, and the plan says so plainly: it hands
the user — and any script they paste — interactive execution as the
app's UID, with `INTERNET` and `WRITE_EXTERNAL_STORAGE`, full read/write
of the toolchain prefix, the CA bundle, and the **insecure-tls marker
that disables cargo certificate verification globally**. A pasted
`curl | sh` runs with all of that. Mitigations are the sandbox and the
user's own judgment; the honest action is documentation — this section,
mirrored in DEVIATIONS.md §10's security notes and the README
known-limits. No new permission is requested for the terminal.

## 9. CI changes

| Workflow | Change |
|---|---|
| `android.yml` | (a) Path triggers gain `android/terminal-emulator/**`, `android/terminal-view/**`. (b) Run `:terminal-emulator:testDebugUnitTest` (upstream suite + our UTF-8 test). (c) New step: host JNI harness (clang + ASan + mock JNIEnv; §6.2). (d) `:app:assembleDebug/Release` now also builds/links the two vendored modules (arm64-v8a only). |
| `busybox.yml` (new) | Manual dispatch + push to `busybox/**`: NDK r27c static build, SHA-256 pin of the source tarball, static-ELF/AArch64/no-PT_INTERP verification, applet-name smoke via `strings`, artifact `rustdroid-busybox-aarch64`. ~3 min. |
| `publish-release.yml` | Consumes BOTH the toolchain dist artifact (main.yml) and the busybox artifact (busybox.yml) when assembling the app bundle; bundle manifest v2 (busybox entry + guarded symlink list + per-entry sha256); publishes `busybox-1.36.1-src.zip` (GPL §7.4) alongside; SHA256SUMS.txt covers all assets. |
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
2. The pre-scanned close-list is the v0 fd discipline (`close_range` is
   rejected on seccomp grounds, §4.4); ART's `O_CLOEXEC` default makes
   the residual inheritance risk no worse than Java `ProcessBuilder`
   today. (v2: this assumption is now scoped to the close-list only —
   the kernel-version clause is gone with `close_range` itself.)
3. Android's init reaps orphaned zombies promptly; teardown budget (3
   sweeps × 50 ms) tolerates slower reaping because of the zombie
   exclusion (§12.1 fix).
4. BusyBox 1.36.1 (stable, §7.2) builds cleanly with NDK r27c at API 24
   with the Termux patch set adapted (Termux's recipe is at 1.38.0, so
   retargeting may shift patch context); any patch drift fails the
   busybox workflow loudly (patch application is fatal, not warn — the
   repo's post-vendor lesson).
5. The existing bundle's zip+tar.xz structure carries the additional
   `busybox` entry without format changes (manifest v2 is additive).

**Open decisions for the reviewer** (with recommendations):

1. **`TerminalService` FGS — RESOLVED per review: include in v0** (a
   terminal whose sessions die on routine backgrounding undermines the
   feature; the service pattern is proven in this repo; the review
   agreed and asked for the `specialUse` migration debt to be recorded —
   done, §8.3/DEVIATIONS.md §10).
2. **ash profile/rc files** (`$PREFIX/etc/profile` machinery): recommend
   defer to a follow-up (v0 launches plain interactive `sh`).
3. **BusyBox applet set**: recommend the pruned-server config (§7.2);
   reviewer may want `less`/`vi` on/off confirmation (`vi` stays on —
   in-terminal quick edits are a real terminal use case).
4. **Extra-keys row contents** (soft keyboards lack Ctrl/Esc/Tab/arrows):
   recommend minimal v0 row: Esc, Tab, Ctrl (toggle), arrows, PgUp/PgDn.
5. **Bundle tag string**: `toolchain-1.85.0-aarch64-bb1.36.1` (§7.3) —
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

1. Vendor the two modules at the pinned commit, our build scripts
   (carrying `unitTests.isReturnDefaultValues = true`, §4.1), license
   files, THIRD_PARTY.md entries, and the app-level ProGuard keep rules
   for the JNI class (P3-11 — inert while `isMinifyEnabled = false`,
   enforced by review) — **no source patches yet**; CI builds the APK
   with the modules, and `:terminal-emulator:testDebugUnitTest` is green
   before any patch lands (the step-1 gate the review asked for).
2. JNI defect fixes + fork-window restructure + lib rename + `RD_FORK`
   test seams + host harness (§6.2) — all defects now regression-pinned.
3. UTF-8 split test (§6.3); `ProcTree.sessionMembers` + controller + tests
   (§6.4).
4. `TerminalEnv` + `TerminalScreen`/VM + navigation + extra-keys row.
5. `busybox.yml` + config/patches; verifier extension + bundle assembly
   (`publish-release.yml`) + `ToolchainDistro` bump; GPL source asset.
6. On-device validation checklist executed, DEVIATIONS.md postmortem,
   README phase table update (Phase 4 row).

### 12.3 v2 change log (post-review, this session)

Revision after the adversarial review (`docs/Report.txt`, verdict
APPROVE WITH CONDITIONS; point-by-point disposition:
`docs/phase3-review-response.md`). **All conditions adopted.** Every
change below is plan-text only — no implementation code exists yet:

- **§5.2/§5.4/§6.4/§6.6 (P0-1)** — `setsid(2)` semantics corrected: the
  v1 "a setsid'd daemon keeps `sid == shellPid` forever" claim inverted
  the syscall. Teardown guarantee rescoped to *attached* processes
  (deliberately detached survivors are by-design); union discovery
  adopted (`session ∪ descendants ∪ tty_nr`, pts device number computed
  in the parent via `fstat`/`st_rdev`); §6.4/§6.6 expectations inverted,
  with a test that pins the true kernel semantics so the misconception
  cannot be re-introduced.
- **§4.4 (P0-2)** — `close_range` removed from v0 entirely: seccomp
  allowlist vs. SIGSYS-in-child risk; pre-scanned close-list only; the
  safe forked-child probe shape recorded for posterity; parent-process
  probing explicitly forbidden; `syscall`/`close_range` added to the
  whitelist-grep forbidden list.
- **§5.3 (P1-3/P1-4, condition 8)** — teardown reordered: quiesce →
  stop+join reader → close master (kernel-delivered SIGHUP) → bounded
  grace → union snapshot → freeze → SIGKILL → SIGCONT survivors;
  `runCatching` on every injected signal/killpg/destroy (also added to
  `terminateTree`); EIO on the master read is normal session end; the
  /proc-scan cost note added.
- **§4.4 (P2-7)** — SIG_DFL disposition-reset loop over signals 1..64
  added to the child path (SIG_IGN survives `execve`; ART ignores
  SIGPIPE); `sigaction` joins the whitelist.
- **§4.1/§4.6 (P1-5)** — `unitTests.isReturnDefaultValues = true`
  carried into both vendored build scripts; Java 17 `compileOptions`;
  `-Werror` fallback strategy (build-script-level, never patch upstream
  C); step-1 gate now includes green upstream tests.
- **§7.2 (P1-6, P3-9, Part 4)** — BusyBox re-pinned 1.38.0 (unstable) →
  **1.36.1 (stable)**, SHA-256 from busybox.net's own `.sha256` file;
  network *client* applets dropped alongside the servers (static-bionic
  NSS); shell config options pinned explicitly (job control, history,
  tab completion, internal glob).
- **§7.3/§6.5 (P2-8)** — manifest v2 `symlinks` section with
  `Fs.resolveChild` + `Fs.requireInside` guards; traversal-rejection
  test specified; "reuse the existing link pass" hand-wave removed.
- **§8.3 (P3-10)** — FGS resolved: ship in v0; `dataSync` →
  `specialUse` migration debt recorded (API 34 enforcement, API 35
  ~6 h/day cap).
- **§8.4/§8.5 (Part 2)** — new: backpressure non-goal (kernel flow
  control, capped scrollback, bounded memory) and terminal threat-model
  honesty (interactive execution as app UID; the insecure-tls marker).
- **§2/§4.1 (condition 10)** — pin re-verification block: every
  "@ `3b66f87`" claim re-checked against the actual commit (blob-partial
  clone); NOTICE-file absence confirmed; `438cd73` date disambiguated
  (author date 2022-11-16, committer date 2024-09-26 — both real).
- **§3.2/§7.4 (Part 3, condition 11)** — `termux-shared` carve-out
  stated precisely (`TermuxConstants.java` specifically MIT);
  mere-aggregation reasoning labeled legal interpretation with the
  FSF-position note and lawyer footnote.
- **§4.2 (P3-11)** — ProGuard keep rules for the vendored JNI class,
  landed in implementation step 1.
- **§6.2/§6.6 (Part 6)** — host-harness limits stated (glibc/x86, no
  seccomp oracle); on-device items added: bring-up matrix (Android
  9/11/14), SIGPIPE (`yes | head -1`), 200-cycle stress with
  concurrent download + thread/fd-count assertions, memory-pressure
  soak; malloc interposer labeled necessary-not-sufficient.
- **§6.2** — fd-leak assertion around 200 create/destroy cycles added
  (host, Linux `/proc/self/fd`).
- **Companion records** — THIRD_PARTY.md (carve-out precision, date,
  1.36.1, NOTICE-at-pin) and DEVIATIONS.md §10 (review-response
  addendum: targetSdk-28 FGS debt, terminal threat-model security
  notes) updated in the same change.
