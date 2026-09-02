package dev.rustdroid.ide.runtime

import dev.rustdroid.ide.model.ConsoleLine
import dev.rustdroid.ide.model.Stream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ring buffer of console output + live line feed. VMs expose this to the UI;
 * the console panel renders the buffer tail and appends on flow emissions.
 */
class ConsoleBuffer(private val capacity: Int = 2000) {

    private val _lines = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val lines: StateFlow<List<ConsoleLine>> = _lines.asStateFlow()

    private _newLines = MutableSharedFlow<ConsoleLine>(extraBufferCapacity = 256)
    val newLines: SharedFlow<ConsoleLine> = _newLines.asSharedFlow()

    @Volatile var droppedOverflow: Int = 0
        private set

    fun append(line: ConsoleLine) {
        _newLines.tryEmit(line)
        val cur = _lines.value
        val next = if (cur.size >= capacity) {
            droppedOverflow++
            cur.subList(cur.size - capacity + 1, cur.size) + line
        } else {
            cur + line
        }
        _lines.value = next
    }

    fun system(text: String) = append(ConsoleLine(Stream.SYSTEM, text))

    fun clear() {
        droppedOverflow = 0
        _lines.value = emptyList()
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
