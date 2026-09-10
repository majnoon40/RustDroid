package dev.rustdroid.ide.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.rustdroid.ide.runtime.terminal.TerminalCenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI adapter over [TerminalCenter] (plan §8.1): sessions LIVE in the
 * center (surviving rotation — the VM is disposable); the VM only tracks
 * which session is CURRENT for the tab strip and forwards create/close.
 * The foreground service — not this VM — owns the session lifetime.
 */
class TerminalViewModel(val center: TerminalCenter) : ViewModel() {

    val sessions: StateFlow<List<TerminalCenter.SessionEntry>> = center.sessions

    private val _currentId = MutableStateFlow<Long?>(null)
    val currentId: StateFlow<Long?> = _currentId

    /** User-visible failure explanation for the last create attempt (never silent). */
    private val _lastCreateError = MutableStateFlow<String?>(null)
    val lastCreateError: StateFlow<String?> = _lastCreateError

    fun switchTo(id: Long) {
        _currentId.value = id
    }

    fun createSession() {
        when (val result = center.createSession()) {
            is TerminalCenter.CreateResult.Ok -> _currentId.value = result.entry.id
            is TerminalCenter.CreateResult.NotInstalled -> _lastCreateError.value = result.detail
        }
    }

    fun closeSession(id: Long) {
        center.closeSession(id)
        if (_currentId.value == id) {
            _currentId.value = center.sessions.value.lastOrNull { it.id != id }?.id
        }
    }

    fun dismissError() {
        _lastCreateError.value = null
    }

    companion object {
        fun factory(center: TerminalCenter): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TerminalViewModel(center) as T
            }
    }
}
