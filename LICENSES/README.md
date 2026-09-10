# LICENSES/

Per-component third-party license texts for everything RustDroid ships.
The authoritative inventory (with licenses, pins, and provenance for
every component) is `../THIRD_PARTY.md`.

- `terminal-emulator-Apache-2.0.txt` — Apache-2.0 text + attribution for
  the vendored Termux `terminal-emulator` library (no upstream NOTICE
  file exists — verified at the pinned commit `3b66f87`)
- `terminal-view-Apache-2.0.txt` — same for the vendored
  `terminal-view` library (includes the AOSP-derived
  `support/PopupWindowCompatGingerbread.java` attribution)
- `busybox-GPL-2.0.txt` — GPL-2.0-only text for the BusyBox executable
  (added at the BusyBox build step; see
  `docs/phase3-terminal-architecture.md` §7)
