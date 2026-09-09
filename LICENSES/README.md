# LICENSES/

Per-component third-party license texts for everything RustDroid ships.

Populated at Phase 4 implementation time (see
`docs/phase3-terminal-architecture.md` §3 and `THIRD_PARTY.md`):

- `busybox-GPL-2.0.txt` — GPL-2.0-only text for the BusyBox executable
- `terminal-emulator-Apache-2.0.txt` / `terminal-view-Apache-2.0.txt` —
  Apache-2.0 texts + attribution for the vendored Termux libraries
  (no upstream NOTICE files exist — verified at the pinned commit)

Until then, the authoritative inventory (with licenses, pins, and
provenance for every existing component) is `../THIRD_PARTY.md`.
