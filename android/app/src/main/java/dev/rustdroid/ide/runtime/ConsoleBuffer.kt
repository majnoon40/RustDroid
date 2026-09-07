package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ring buffer of console output + live line feed. VMs expose this to the UI;
 * the console panel renders the buffer tail and appends on flow emissions.
 *
 * Performance contract: append() is O(1) (ArrayDeque, no per-line list
 * copy). The [lines] StateFlow is republished at most once per
 * [publishIntervalMs] — a verbose cargo build emits hundreds of lines per
 * second, and publishing a 2000-element snapshot per line (the old
 * `subList + copy` design) was the top source of build-time jank. Bursts
 * land in the deque immediately and become visible on the next publish
 * window; [flush] forces them out (call it when a run ends, and every
 * [system] message publishes at once — user-facing status lines must never
 * wait behind a rate limit).
 *
 * TRAILING-LINE GUARANTEE: the rate limiter only ever DELAYS a publish —
 * lines are never dropped. A burst whose last line lands inside the
 * rate-limit window would otherwise sit unpublished forever (no further
 * appends, nobody calls flush). [append] therefore schedules a trailing
 * publish through [scheduleTrailingFlush], owned by the buffer itself:
 * a single-shot coroutine on the injected [scope] that fires when the
 * window elapses and publishes whatever is dirty. The scope is provided
 * by the OWNER (a VM's viewModelScope / the service's scope) — the buffer
 * never creates a leaked global scope of its own, and closing that scope
 * cancels the pending publish. [flush] stays authoritative: an explicit
 * flush cancels any scheduled trailing publish (it already published).
 *
 * [lastPublishMs] lives under [lock] (written in publish, read in
 * maybePublish) — it was previously a plain field read from arbitrary
 * append threads, which is a data race (torn/stale values on JMM).
 */
class ConsoleBuffer(
    private val capacity: Int = 2000,
    private val publishIntervalMs: Long = 100,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val deque = ArrayDeque<ConsoleLine>()

    private val _lines = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val lines: StateFlow<List<ConsoleLine>> = _lines.asStateFlow()

    private val _newLines = MutableSharedFlow<ConsoleLine>(extraBufferCapacity = 256)
    val newLines: SharedFlow<ConsoleLine> = _newLines.asSharedFlow()

    private val dropped = AtomicInteger()
    val droppedOverflow: Int get() = dropped.get()

    /** True when the deque holds changes not yet in [lines]. */
    private var dirty = false
    private var lastPublishMs = 0L

    /**
     * Single trailing publish scheduled when a burst lands inside the
     * rate-limit window; null when nothing is scheduled. A new burst
     * reschedules only when none is pending (the earliest deadline wins —
     * later lines are covered by an already-scheduled publish).
     */
    private var trailingJob: Job? = null

    fun append(line: ConsoleLine) {
        _newLines.tryEmit(line)
        synchronized(lock) {
            if (deque.size >= capacity) {
                deque.removeFirst()
                dropped.incrementAndGet()
            }
            deque.addLast(line)
            dirty = true
        }
        maybePublish()
    }

    /** Status/system lines are rare and important — always visible at once. */
    fun system(text: String) {
        append(ConsoleLine(Stream.SYSTEM, text))
        flush()
    }

    /** Publishes pending lines if the rate-limit window has elapsed. */
    private fun maybePublish() {
        val now = clock()
        val withinWindow = synchronized(lock) {
            now - lastPublishMs < publishIntervalMs
        }
        if (!withinWindow) {
            publish()
        } else {
            scheduleTrailingFlush(now - lastPublishMs)
        }
    }

    /**
     * Schedules the one-shot trailing publish (no leaked scope: the job is
     * a child of the injected [scope] and is cancelled by [flush]/[clear]).
     */
    private fun scheduleTrailingFlush(waitMs: Long) {
        val sc = scope ?: return // no scope: explicit flush() is required
        synchronized(lock) {
            if (trailingJob?.isActive == true) return
            trailingJob = sc.launch {
                delay(publishIntervalMs - waitMs.coerceAtLeast(0L))
                synchronized(lock) { trailingJob = null }
                publish()
            }
        }
    }

    /** Publishes the current deque snapshot regardless of the rate limit. */
    fun flush() {
        synchronized(lock) {
            trailingJob?.cancel()
            trailingJob = null
        }
        publish()
    }

    private fun publish() {
        synchronized(lock) {
            trailingJob?.cancel()
            trailingJob = null
            if (!dirty) return
            _lines.value = deque.toList()
            dirty = false
            lastPublishMs = clock()
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            dirty = true
        }
        dropped.set(0)
        publish()
    }
}

/**
 * Strips ANSI CSI SGR sequences (colors/bold) — used by the diagnostics
 * parser, which must see plain text.
 */
object Ansi {
    private val CSI = Regex("\u001b\\[[0-9;]*[A-Za-z]")

    fun strip(line: String): String = CSI.replace(line, "")
}
