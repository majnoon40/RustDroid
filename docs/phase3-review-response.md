# Phase 4 plan — review response (round 1)

Response to `docs/Report.txt` (adversarial review of
`docs/phase3-terminal-architecture.md`, verdict: **APPROVE WITH
CONDITIONS**). Disposition: **all findings accepted, all conditions
adopted.** Every accepted correction below is already applied to the
plan document (v2, same file) and to THIRD_PARTY.md; the section
numbers refer to the revised plan.

One note on the report text itself: `docs/Report.txt` line 111 is
corrupted — the SIG_IGN correction and the start of the manifest-symlink
finding were merged by a paste glitch (the sentence cuts off mid-`for`
loop and jumps to "`…/data/data/com.other/files/x must be rejected`").
The missing content was reconstructed from Part 5 and the Conditions
list, which state both findings unambiguously: (a) reset signal
dispositions to SIG_DFL in the child before execve alongside
`sigprocmask(SIG_SETMASK, empty)`; (b) route manifest-v2 symlinks through
`Fs.resolveChild` + `Fs.requireInside` with a traversal-rejection test.
If the reconstruction is wrong anywhere, the plan follows Part 5 +
Conditions, not this document's guess.

## 1. Verification addendum (claims the reviewer could not check)

The reviewer explicitly marked these UNVERIFIED. Re-checked this
session, against the actual pinned commit `3b66f87` (not master):

| Claim | Re-verification result |
|---|---|
| `3b66f8799635a4dba4a206563048ff0e6792c487` exists | Yes — fetched and checked out via a blob-partial clone. Coincidentally, termux-app's master HEAD *is* `3b66f87` right now, so the reviewer's master-based checks are pin-identical. Recorded as luck, not as method; the pin stays the pin. |
| NOTICE files absent in both modules | Confirmed: no `NOTICE*` anywhere under `terminal-emulator/` or `terminal-view/` (module roots and recursive). Apache-2.0 §4(d) therefore imposes nothing beyond license-text preservation, which `LICENSES/terminal-{emulator,view}-Apache-2.0.txt` carries. |
| `rg "termux.shared\|TermuxConstants"` zero matches in the two modules | Confirmed at the pin: zero matches. |
| `termux.c` unchanged since `438cd73` | Commit hash and conclusion confirmed: at the pin, `438cd73` is the last commit touching `termux.c`. Date disambiguated: `438cd73` has **author date 2022-11-16 and committer date 2024-09-26** (authored 2022, rebased into history two years later) — both are real git dates; v1 cited the author date, which was not *wrong*, just unspecified. The plan (§2/§4.1) now states both; drift-risk conclusion unchanged either way. |
| "18 JUnit test classes" | 19 test files exist; one (`TerminalTestCase.java`) is a shared base extending `junit.framework.TestCase`, not a test class of its own. 18 actual test classes + 1 base — the count was right, now stated precisely (§4.6). |
| Upstream build details | Confirmed at the pin: `unitTests.returnDefaultValues = true` exists in `terminal-emulator/build.gradle` (the reviewer's P1 is real — it is now carried into our build scripts); `abiFilters 'x86','x86_64','armeabi-v7a','arm64-v8a'`; deps are exactly `androidx.annotation:annotation:1.9.0` + `junit:junit:4.13.2` (emulator) and `androidx.annotation` + `api project(":terminal-emulator")` (view); cFlags include `-Werror`. |
| BusyBox 1.38.0 unstable / 1.36.1 stable | Confirmed from busybox.net's own news listing: "1.38.0 (unstable), 1.37.0 (unstable), 1.36.1 (stable)". busybox.net publishes per-tarball `.sha256` files — the authoritative checksum source the review asked for. |
| The 1.38.0 SHA-256 "cross-checked against Termux" | The checksum value was in fact correct (matches busybox.net's published `.sha256`), and Termux's `packages/busybox/build.sh` does pin 1.38.0 with that exact value — the cross-check claim was accurate. It is nonetheless **superseded**: we re-pin to stable **1.36.1**, `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314`, taken from `https://busybox.net/downloads/busybox-1.36.1.tar.bz2.sha256` directly (provenance recorded in §7.2). Termux running 1.38.0 is a data point about a distro that tracks upstream with quick revisions, not a maturity signal for an app shipping a frozen utility set. |
| "193 JVM tests, 0 failures" | Not re-run this session (the 193 count is the §12.1/DEVIATIONS §9 record of the landed zombie-fix suite; a local Android SDK install was started but did not complete in this session). It is re-verified at each implementation step; the running count lives in DEVIATIONS.md §10's implementation log, appended as terminal-layer tests land. |

## 2. Findings disposition

### P0-1 — `setsid()` changes the session ID (§5.2/§5.4/§6.4/§6.6)

**Accepted in full.** The plan conflated "reparented to init" (SID
retained) with "setsid'd" (new session, SID = own PID). The reviewer is
right that the sentence "a setsid'd daemon keeps `sid == shellPid`
forever and stays visible" inverts `setsid(2)` semantics, and that a
test written to that belief would pass while encoding the wrong kernel
model — exactly the test-of-the-implementation failure mode.

Changes applied (plan §5.2, §5.4, §6.4, §6.6):

- **Guarantee restated**: everything still *attached* to the terminal
  dies on teardown; **deliberately detached processes survive by
  design** — that is what `nohup`/`setsid` are *for*, and a terminal
  that kills them is arguably wrong Unix. The plan no longer claims
  escape-immunity.
- **Union discovery** (best-effort beyond the guarantee):
  `members = sessionMembers(procRoot, shellPid) ∪ descendants(procRoot,
  shellPid) ∪ ttyNrMatches(procRoot, ptsDevice)` — dedup by
  `ProcessId`, identity revalidation before every signal unchanged.
  The pts device number is computed in the parent at PTY creation
  (`fstat` the slave, `st_rdev`) and handed to the controller.
  `/proc/<pid>/stat` field 6 (session) and field 7 (tty_nr) are parsed
  with the file's existing last-`)` discipline.
- **The discriminating test the review asked for**: synthetic /proc
  with a process having `sid == ownPid`, `ppid == 1`,
  `tty_nr == ptsDev` — assert `sessionMembers` alone does **NOT**
  return it (pins true kernel semantics so the misconception cannot be
  re-introduced), and that the union **does**. Plus: a truly detached
  escapee (`setsid` + tty released → tty_nr 0) is invisible to all
  three sets — and survives **by design** (asserted, not lamented).

### P0-2 — `close_range()` dies under Android's seccomp allowlist (§4.4)

**Accepted; close_range removed from v0 entirely.** The failure mode
the review describes — SIGSYS in the forked child between fork and
exec, silent blank terminal, no diagnostic, device-dependent — is the
worst bug class in this project's history, and the plan's own
residual-risk note conceded the pre-scanned close-list is "strictly no
worse than the status quo." Changes applied (plan §4.4):

- Child path closes leftover fds **only** via the parent-pre-scanned
  close-list: pure `close(2)` per fd (unconditionally allowlisted).
- The startup-probe design (throwaway forked child, WIFSIGNALED +
  WTERMSIG == SIGSYS detection) is recorded in the plan as the *only*
  safe probe shape, explicitly **not** implemented in v0 — and the
  never-probe-in-the-parent trap is written down so nobody "just tries
  it" later.
- The §6.6 on-device checklist keeps a multi-API-level device matrix
  (9/11/14) for terminal bring-up generally; the host harness is
  documented as **not** an Android-seccomp oracle (Part 6 gap closed).

### P1-3 — SIGSTOP freeze makes the SIGHUP step dead code (§5.3)

**Accepted; teardown sequence rewritten.** The two goals (freeze to
prevent escape vs. graceful hangup notification) are mutually
exclusive, and the old sequence asserted both. The revised sequence
(plan §5.3), matching real terminals:

1. quiesce the session's input path (no new writes to the master);
2. stop the output reader (wakeup-pipe, **join with bounded timeout**);
3. **close the master fd** — the kernel delivers SIGHUP to the
   terminal's foreground process group with correct semantics, for
   free, and this is the graceful phase;
4. bounded grace period (300 ms default; 200–500 ms range);
5. snapshot via union discovery; freeze (SIGSTOP) members;
6. SIGKILL frozen members; bounded identity-checked, zombie-excluded
   sweeps with early-return;
7. **SIGCONT any freeze survivor still alive after the budget is
   exhausted** — never leave a process we stopped stopped forever;
8. every injected signal/`killpg`/`destroy` call wrapped in
   `runCatching`: a teardown must not crash on an ESRCH/EPERM race or
   a throwing lambda (same guard added to `terminateTree`).

Recorder-based ordering test added: no SIGSTOP precedes the
SIGHUP-equivalent (master close) for a given PID; the grace delay is
observed (§6.4).

### P1-4 — master-fd close vs. blocked reader: use-after-close (§5.3)

**Accepted.** Close-while-blocked-read is the classic fd-reuse hazard
(cross-session ptmx, OkHttp sockets) and it is now specified, not
implied: the **reader is stopped and joined before any close of the
master fd**, and the close is performed by the session's I/O-owner
thread after the join. The reader loop polls {master fd, wakeup pipe}
so teardown can wake it without closing anything under it; **EIO on
the master read is normal end-of-session** (Linux behavior after the
child side is gone), surfaced as "session ended", never as an error.
Stress item added to §6.6: 200 open/close cycles with a concurrent
download, assert no cross-talk and stable thread count; fd-count
(`/proc/self/fd`) assertion around repeated create/destroy — host-side
in the JNI harness (Linux has /proc) and on-device.

### P1-5 — vendored tests need `unitTests.returnDefaultValues` (§4.1/§4.6)

**Accepted.** Confirmed at the pin and carried into both new
`build.gradle.kts` files: `testOptions { unitTests.isReturnDefaultValues
= true }`, `compileOptions` at Java 17 (upstream 1.8; raising is safe),
and the `-Werror` note: upstream cFlags are kept in our build script,
with the documented fallback of dropping `-Werror` or adding targeted
`-Wno-` flags **in our build script** (never patching upstream C for
warning cosmetics) if NDK r27c's newer clang warns. Implementation
step 1 now explicitly includes a green `:terminal-emulator:
testDebugUnitTest` before any patch lands.

### P1-6 — BusyBox 1.38.0 is upstream-unstable (§7.2/§2)

**Accepted; re-pinned to 1.36.1 (stable).** The maturity argument that
justified BusyBox over Toybox now points at the stable line. SHA-256
pinned from busybox.net's own `.sha256` file (see §1 above); the
Termux cross-check note corrected (their recipe is at 1.38.0 — true
but not our basis). Bundle tag becomes
`toolchain-1.85.0-aarch64-bb1.36.1`.

### P2-7 — SIG_IGN survives execve (§4.4)

**Accepted.** The child path now resets **dispositions** as well as the
mask: a `sigaction(i, SIG_DFL)` loop over signals 1..64 (KILL/STOP
reject harmlessly) before `execve`, alongside
`sigprocmask(SIG_SETMASK, empty)`. `sigaction` is async-signal-safe
(POSIX) and a thin syscall wrapper in bionic — it joins the child-path
whitelist. Regression: the host harness sets SIGPIPE to SIG_IGN in the
parent (mimicking ART), spawns a `sigprobe` helper through the fixed
`create_subprocess`, and asserts the post-exec disposition is SIG_DFL;
on-device, `yes | head -1` must terminate (§6.2, §6.6). This also
joins the child-path whitelist-grep enforcement list.

### P2-8 — manifest-v2 symlinks and traversal (§7.3)

**Accepted.** The plan no longer describes symlink creation as "reuse
of the existing link pass" hand-waving: manifest v2 carries a
`symlinks` section; every target is resolved with `Fs.resolveChild`
(lexical: no absolute, no `..`) **and** `Fs.requireInside` (canonical
containment through existing symlinked ancestors) before creation —
the same guard the extractor uses for tar entries. A
traversal-rejection test is specified (§6.5): a manifest with
`../../etc/passwd`, an absolute `/data/local/tmp/x`, and a
`ln -s ../../../../../../data/data/com.other/files/x` style target must
all be rejected loud, install-blocking.

### P3-9 — static bionic NSS (§7.2, README known-limits)

**Accepted, both halves:** network **client** applets
(wget/telnet/ftpget/ftpput/nslookup/ping…) are dropped from the v0
config alongside the servers, **and** the known-limits section
documents why (static bionic can lose the dynamic resolution path;
"wget is broken" reports are worse than no wget). Revisit is a
recorded follow-up, not a silent gap.

### P3-10 — dataSync FGS type is future-wrong (§8.3)

**Accepted and recorded as migration debt**: correct at targetSdk 28
(type enforcement off), wrong from API 34+ (type-appropriateness
enforced) and worse at API 35 (dataSync ~6 h/day cap). The terminal's
eventual type is `specialUse` with a declared justification. Recorded
in the plan, DEVIATIONS.md, and the repo's targetSdk-28 debt list.

### P3-11 — R8/ProGuard keep rules now (§4.2, step 1)

**Accepted.** `android/app/proguard-rules.pro` gains
`-keepclasseswithmembernames class com.termux.terminal.JNI { native
<methods>; }` plus keeps for any class the vendored C calls into
(none today — `termux.c` never calls back into Java — recorded so the
rule set is reviewed when that changes). Landed in implementation
step 1, while `isMinifyEnabled = false` makes it inert but enforced by
review.

### Part 2 — risks

- **Fork-window residual risk**: accepted as "likely fine, not proven
  fine." The whitelist-grep and malloc interposer remain (necessary,
  not sufficient); the on-device **soak under memory pressure** is now
  a budgeted §6.6 item (hundreds of session open/cycles with low
  memory) rather than an aspiration.
- **EIO/EOF**: specified (see P1-4 above) — normal session end.
- **UTF-8 test coupling**: §6.3 now asserts the decoder's intermediate
  state (`mUtf8ToFollow == 0`, buffer empty) after every split
  reassembly, in addition to the rendered row, so a decoder regression
  is distinguishable from a rendering change.
- **Backpressure**: stated as an explicit non-goal + design constraint
  (new §8.4): no unbounded Kotlin-side output buffering; the child
  blocks writing to the pts when the reader falls behind (kernel flow
  control); scrollback is capped; a 64 MB `cargo build --verbose`
  must translate to bounded memory, not OOM.
- **/proc scan cost**: union discovery reads each member's stat once
  per snapshot into a cached identity map; sweeps re-validate only
  captured PIDs (single-stat reads), never a full /proc re-scan per
  sweep. Stated in §5.3.
- **Threat model**: new §8.5 — "a terminal is strictly more dangerous
  than a build button": interactive execution as the app UID with
  INTERNET + WRITE_EXTERNAL_STORAGE, full read/write of the prefix,
  the CA bundle, and the insecure-tls marker that disables cargo
  certificate verification globally. Mirrored into DEVIATIONS.md's
  security notes.

### Part 3 — licensing

- **termux-shared carve-out precision**: THIRD_PARTY.md and plan §3.2
  now state the actual structure (verified at the pin this session):
  MIT overall; GPLv3-only applies to
  `src/main/java/com/termux/shared/termux/*` **unless specifically
  overridden** — `TermuxConstants.java` and
  `TermuxPropertyConstants.java` are specifically MIT (the plan's
  "holds TermuxConstants" reasoning was wrong in the detail while
  right in the conclusion); `com/termux/shared/file/filesystem/*` is
  GPLv2+Classpath-exception (ojluni); `StreamGobbler.java` is
  Apache-2.0 (libsuperuser). Exclusion decision unchanged.
- **"Mere aggregation" labeled as legal interpretation**, with the
  FSF-position note and the "have a lawyer confirm if this ever
  matters commercially" footnote.
- **NOTICE absence**: now verified at the pin (§1 above).
- **Compliance reaching bundle recipients**: the BusyBox source-asset
  URL is a **requirement** of the in-app Licenses screen (§7.5), not a
  nicety — the bundle ships via the app's downloader, so the release
  page is not where recipients look.

### Part 4 — BusyBox vs Toybox

No change to the conclusion; adjustments adopted: network clients
dropped (P3-9); the config now explicitly pins
`CONFIG_SH_IS_ASH=y`, `CONFIG_ASH_JOB_CONTROL=y`,
`CONFIG_ASH_INTERNAL_GLOB=y`, `CONFIG_FEATURE_EDITING=y`,
`CONFIG_FEATURE_EDITING_HISTORY=y`,
`CONFIG_FEATURE_TAB_COMPLETION=y` (a shell without history/tab
completion reads as broken regardless of POSIX correctness).

### Part 6 — testing audit

All four gaps closed in the plan: close_range is out of v0 and the
host harness is documented as seccomp-blind (on-device matrix is the
only oracle); the malloc interposer is labeled necessary-not-sufficient
with the on-device soak as the real test; the §6.4 setsid expectations
are inverted to pin true kernel semantics; §6.3 gains decoder-state
assertions. The four missing tests the review listed are all added:
SIGPIPE disposition (host sigprobe + on-device `yes | head -1`),
master-close-vs-reader-join ordering (Recorder), manifest-v2 symlink
traversal rejection, and the fd-leak count around 200 create/destroy
cycles (host + on-device).

## 3. Conditions checklist (from the review's final section)

| # | Condition | Where |
|---|---|---|
| 1 | setsid semantics corrected; guarantee scoped to attached processes; union discovery; §6.4/§6.6 expectations fixed incl. kernel-semantics-pinning test | plan §5.2, §5.4, §6.4, §6.6 |
| 2 | close_range removed for v0; pre-scanned close-list; SIGSYS-probe shape recorded, not shipped | plan §4.4 |
| 3 | SIGSTOP/SIGHUP resolved: close master → grace → freeze → kill | plan §5.3 |
| 4 | reader stopped+joined before master close; EIO = normal end | plan §5.3 |
| 5 | SIG_DFL reset loop in child alongside sigprocmask(SIG_SETMASK, empty) | plan §4.4 |
| 6 | `unitTests.returnDefaultValues = true` in both vendored build scripts | plan §4.1, §4.6 |
| 7 | BusyBox 1.36.1 with busybox.net-verified checksum | plan §7.2 |
| 8 | runCatching on every injected signal/killpg/destroy (terminateTree + controller); SIGCONT for freeze survivors after budget | plan §5.3; `ProcTree.kt` + controller tests |
| 9 | manifest-v2 symlinks via Fs.resolveChild + Fs.requireInside + traversal-rejection test | plan §7.3, §6.5 |
| 10 | every "@ 3b66f87" claim re-verified at the pin; NOTICE absence confirmed | this document §1 |
| 11 | termux-shared carve-out corrected; mere-aggregation labeled interpretation | THIRD_PARTY.md, plan §3.2 |

The plan is implementable as revised; implementation follows the §12.2
order (unchanged, plus proguard keeps in step 1 and the green-upstream-
tests gate).
