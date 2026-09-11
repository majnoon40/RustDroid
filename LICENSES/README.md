# LICENSES/

Per-component third-party license texts for everything RustDroid ships.
The authoritative inventory (with licenses, pins, and provenance for
every component) is `../THIRD_PARTY.md`.

These are the REPO-side copies (full provenance headers, reviewable in
the tree). The copies the app actually displays live in
`../android/app/src/main/assets/licenses/` and are indexed by
`LICENSE_INDEX.json` there. The two sets are kept in sync deliberately;
`LicenseIndexTest` guards the shipped set's integrity (every referenced
file exists, and each row points at its own component's text rather than
another's).

- `terminal-emulator-Apache-2.0.txt` — Apache-2.0 text + attribution for
  the vendored Termux `terminal-emulator` library (no upstream NOTICE
  file exists — verified at the pinned commit `3b66f87`)
- `terminal-view-Apache-2.0.txt` — same for the vendored
  `terminal-view` library (includes the AOSP-derived
  `support/PopupWindowCompatGingerbread.java` attribution)
- `busybox-GPL-2.0.txt` — GPL-2.0-only text for the BusyBox executable
  (added at the BusyBox build step; see
  `docs/phase3-terminal-architecture.md` §7)
