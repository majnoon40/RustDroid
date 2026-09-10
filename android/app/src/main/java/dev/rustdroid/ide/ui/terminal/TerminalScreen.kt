package dev.rustdroid.ide.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ui.components.RdIcons
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.runtime.terminal.TerminalCenter

/**
 * The terminal screen (plan §8.1): a FIRST-CLASS destination — a
 * character-grid renderer with cursor addressing, resize semantics and
 * ANSI state — NOT a mode of the Editor's diagnostic console (line
 * events + problems navigation; a different rendering and data model).
 *
 * The vendored [TerminalView] (Android View) is hosted through
 * AndroidView interop — the exact pattern of the sora-editor
 * integration, including its hard-won gesture lesson (the v0.1.3
 * drawer-gesture fix): NO Compose draggable overlays on the interop
 * area; terminal scrolling and text selection are handled INSIDE the
 * View, and nothing Compose-side watches pointer events over it.
 *
 * Extra-keys row (plan §11.4, minimal v0): Esc, Tab, Ctrl (toggle),
 * arrows, PgUp/PgDn — soft keyboards lack these. The Ctrl toggle is
 * one-shot: the next key the IME delivers is ctrl-ified through the
 * view's own readControlKey() pipeline, then the toggle resets.
 */
@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(container.terminalCenter))
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val createError by vm.lastCreateError.collectAsState()

    // First visit auto-opens a session (the FAB and the empty state also do).
    LaunchedEffect(sessions.size) {
        if (sessions.isNotEmpty() && currentId == null) vm.switchTo(sessions.first().id)
    }

    val current = sessions.firstOrNull { it.id == currentId } ?: sessions.firstOrNull()

    // ONE Ctrl state shared by the view's key pipeline and the extra-keys
    // row (the pane's view client reads it; the row's button sets it).
    val ctrlState = remember { TerminalCtrlState() }
    var ctrlVisual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ctrlState.onConsumed = { ctrlVisual = false }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- session tab strip (v0: simple strip + close per tab + FAB) ----
        SessionTabs(
            sessions = sessions,
            currentId = current?.id,
            onSwitch = { vm.switchTo(it) },
            onClose = { vm.closeSession(it) },
        )

        // ---- the terminal itself (AndroidView interop) ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (current != null) {
                TerminalPane(
                    entry = current,
                    center = container.terminalCenter,
                    ctrlState = ctrlState,
                )
            } else {
                EmptyState(
                    hasError = createError != null,
                    error = createError,
                    onNewSession = { vm.createSession() },
                    onBack = onBack,
                )
            }
        }

        // ---- extra keys row (fixed BELOW the interop area — not an overlay) ----
        if (current != null) {
            ExtraKeysRow(
                entry = current,
                ctrlState = ctrlState,
                ctrlVisual = ctrlVisual,
                onCtrlToggled = { ctrlVisual = it },
            )
        }
    }

    LaunchedEffect(createError) {
        if (createError != null) {
            // The failure is also visible in the empty state; auto-clear
            // after a beat so the next create attempt starts fresh.
            kotlinx.coroutines.delay(6_000)
            vm.dismissError()
        }
    }
}

@Composable
private fun SessionTabs(
    sessions: List<TerminalCenter.SessionEntry>,
    currentId: Long?,
    onSwitch: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
    if (sessions.isEmpty()) return
    ScrollableTabRow(
        selectedTabIndex = sessions.indexOfFirst { it.id == currentId }.coerceAtLeast(0),
        edgePadding = 0.dp,
    ) {
        sessions.forEach { entry ->
            Tab(
                selected = entry.id == currentId,
                onClick = { onSwitch(entry.id) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.title.ifBlank { "sh" } + if (entry.finished) " ·" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.width(6.dp))
                        androidx.compose.material3.IconButton(
                            onClick = { onClose(entry.id) },
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("×", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
            )
        }
    }
}

/**
 * The interop pane. The view is created ONCE and re-bound on session
 * switches (attachSession) — the same remember-key discipline as the
 * sora-editor pane. The view handles its own scrolling, selection and
 * resize; nothing Compose overlays it (gesture lesson).
 */
@Composable
private fun TerminalPane(
    entry: TerminalCenter.SessionEntry,
    center: TerminalCenter,
    ctrlState: TerminalCtrlState,
) {
    val context = LocalContext.current

    val view = remember(context, ctrlState) {
        TerminalView(context, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // v0.1.8 hardening: set the client HERE, at creation — not in a
            // post-composition effect. The vendored view invokes
            // mClient.onEmulatorSet() from its first updateSize() (which
            // can run during THIS frame's layout pass, before a
            // LaunchedEffect fires); a null client there is an NPE that
            // killed the app the instant a session opened.
            setTerminalViewClient(RdViewClient(this, ctrlState))
        }
    }

    // Bind/unbind the view for redraw routing.
    DisposableEffect(entry.session) {
        center.bindView(entry.session, view)
        onDispose { center.unbindView(entry.session) }
    }

    LaunchedEffect(entry.session) {
        // Breadcrumbs bracket the attach — the vendored session forks on
        // first size (inside attach → layout), and a native death in that
        // window is only localizable through them (the crash recorder
        // covers the Java side).
        dev.rustdroid.ide.runtime.CrashRecorder.crumb("terminal:view-attach")
        view.attachSession(entry.session)
        dev.rustdroid.ide.runtime.CrashRecorder.crumb("terminal:view-attached")
        view.requestFocus()
    }

    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * One-shot Ctrl toggle shared between the extra-keys row and the view's
 * input pipeline. Deliberately NOT Compose state: the view queries it from
 * its own input pipeline; the [onConsumed] callback lets the UI mirror
 * clear when a key consumes the toggle.
 */
class TerminalCtrlState(var onConsumed: (() -> Unit)? = null) {
    @Volatile
    var active: Boolean = false

    /** Consume the toggle (called when a key gets ctrl applied). */
    fun take(): Boolean {
        val was = active
        if (was) {
            active = false
            onConsumed?.invoke()
        }
        return was
    }
}

/**
 * The view client: maps the vendored view's callbacks into v0 behavior.
 * The Ctrl toggle answers [readControlKey]; when the view then reports a
 * code point with ctrlDown == true ([onCodePoint]), the toggle is
 * consumed (one-shot, like Termux's extra keys).
 */
private class RdViewClient(
    private val view: TerminalView,
    private val ctrl: TerminalCtrlState,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = view.hasFocus()

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = ctrl.active

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (ctrlDown) ctrl.take() // one-shot: consumed by this key
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag ?: "TerminalView", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag ?: "TerminalView", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag ?: "TerminalView", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag ?: "TerminalView", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag ?: "TerminalView", message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(tag ?: "TerminalView", message, e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        android.util.Log.e(tag ?: "TerminalView", "", e)
    }
}

@Composable
private fun EmptyState(
    hasError: Boolean,
    error: String?,
    onNewSession: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.align(Alignment.Center)) {
            if (hasError) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.terminal_not_ready_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    "No terminal sessions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sessions run $PREFIX_LITERAL/bin/sh in your projects " +
                        "directory — cd into a project and run cargo build, " +
                        "cargo run or cargo fetch directly (rustc, cargo and " +
                        "the CA bundle are all on the session's PATH).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
            ExtendedFloatingActionButton(onClick = onNewSession) {
                Icon(RdIcons.Terminal, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New session")
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

private const val PREFIX_LITERAL = "\$PREFIX"

/**
 * The minimal v0 extra-keys row (plan §11.4): Esc, Tab, Ctrl (toggle),
 * arrows, PgUp/PgDn. Keys write standard escape sequences straight to
 * the session; the Ctrl toggle participates in the view's key pipeline
 * (see [TerminalCtrlState]).
 */
@Composable
private fun ExtraKeysRow(
    entry: TerminalCenter.SessionEntry,
    ctrlState: TerminalCtrlState,
    ctrlVisual: Boolean,
    onCtrlToggled: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ExtraKey("Esc") { entry.session.write("\u001b") }
            ExtraKey("Tab") { entry.session.write("\t") }
            ExtraKey(
                label = "Ctrl",
                highlighted = ctrlVisual,
            ) {
                ctrlState.active = !ctrlState.active
                onCtrlToggled(ctrlState.active)
            }
            ExtraKey("←") { entry.session.write("\u001b[D") }
            ExtraKey("↑") { entry.session.write("\u001b[A") }
            ExtraKey("↓") { entry.session.write("\u001b[B") }
            ExtraKey("→") { entry.session.write("\u001b[C") }
            ExtraKey("PgUp") { entry.session.write("\u001b[5~") }
            ExtraKey("PgDn") { entry.session.write("\u001b[6~") }
        }
    }
}

@Composable
private fun ExtraKey(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
