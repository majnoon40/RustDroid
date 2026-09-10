package dev.rustdroid.ide.runtime.terminal

import dev.rustdroid.ide.runtime.CaBundle
import dev.rustdroid.ide.runtime.ProcEnv
import java.io.File

/**
 * The TERMINAL session environment (plan §8.2) — derived from, never
 * duplicating, [ProcEnv]: every shared channel (HOME, CARGO_HOME, PATH,
 * LD_LIBRARY_PATH, TMPDIR, RUSTDROID_PREFIX, the CA-file channels,
 * CARGO_TERM_COLOR, LC_ALL) comes from ProcEnv with IDENTICAL strings —
 * one source of truth — and only the terminal deltas are applied here:
 *
 *  - `TERM=xterm-256color`: the vendored emulator implements the xterm
 *    256-color subset; `TERM=dumb` (ProcEnv's build-console value) would
 *    disable half the output the toolchain emits (cargo's progress bars,
 *    colors in `ls`/`grep` when asked for).
 *  - `PATH` gains nothing new: `$PREFIX/bin` already leads (busybox `sh`
 *    resolves there once the BusyBox step ships; until then the session
 *    fails to start and the UI says so — no silent fallback).
 *  - `LC_ALL=C` stays (stable sort order; the UTF-8 path is the pty's
 *    IUTF8 termios flag plus the emulator's incremental decoder, not
 *    the locale).
 *  - `CARGO_TERM_COLOR=never` stays: cargo's own color choice is
 *    unrelated to the terminal's capability.
 *
 * Shell launch parameters: `$PREFIX/bin/sh`, argv `["sh"]`, cwd `$HOME`
 * (plan §8.2).
 *
 * Pure JVM — the env deltas are table-testable against ProcEnv.
 */
object TerminalEnv {

    /** The one ProcEnv value the terminal overrides. */
    const val TERM = "xterm-256color"

    fun shellPath(prefix: File): File = File(ProcEnv.binDir(prefix), "sh")
    fun shellArgs(): Array<String> = arrayOf("sh")

    fun env(
        prefix: File,
        filesDir: File,
        extraPath: String = "/system/bin",
        assetProvider: (() -> ByteArray?)? = null,
        caBundle: File? = CaBundle.ensure(filesDir, prefix, assetProvider = assetProvider),
    ): Map<String, String> =
        ProcEnv.env(
            prefix = prefix,
            filesDir = filesDir,
            extraPath = extraPath,
            assetProvider = assetProvider,
            caBundle = caBundle,
        ) + mapOf("TERM" to TERM)

    /** Keys ProcEnv and TerminalEnv must agree on (one source of truth). */
    val SHARED_KEYS = listOf(
        "HOME", "CARGO_HOME", "PATH", "LD_LIBRARY_PATH", "TMPDIR",
        "RUSTDROID_PREFIX", "CARGO_TERM_COLOR", "LC_ALL",
    )
}
