package dev.rustdroid.ide.ui.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.runtime.terminal.TerminalCenter
import dev.rustdroid.ide.ui.components.EmptyState
import dev.rustdroid.ide.ui.components.RdIcons
import kotlin.math.roundToInt

/**
 * The terminal screen (plan §8.1): a FIRST-CLASS destination — a
 * character-grid renderer with cursor addressing, resize semantics and
 * ANSI state — NOT a mode of the Editor's diagnostic console (line
 * events + problems navigation; a different rendering and data model).
 *
 * v0.2: the terminal lives with the PROJECTS, not the Home menu —
 * [projectRef] (set when opened from a project card or the editor
 * toolbar) makes the first session start IN that project so `cargo
 * run`, `cargo fetch` and `cargo test` work with no cd.
 *
 * The vendored [TerminalView] (Android View) is hosted through
 * AndroidView interop — the exact pattern of the sora-editor
 * integration, including its hard-won gesture lesson (the v0.1.3
 * drawer-gesture fix): NO Compose draggable overlays on the interop
 * area; terminal scrolling, pinch font scaling, selection and tap all
 * happen INSIDE the View, and nothing Compose-side watches pointer
 * events over it.
 *
 * Extra-keys row (plan §11.4, minimal v0): Esc, Tab, Ctrl (toggle),
 * arrows, PgUp/PgDn — soft keyboards lack these. The Ctrl toggle is
 * one-shot: the next key the IME delivers is ctrl-ified through the
 * view's own readControlKey() pipeline, then the toggle resets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    container: AppContainer,
    onBack: () -> Unit,
    projectRef: String? = null,
) {
    val vm: TerminalViewModel = viewModel(
        factory = TerminalViewModel.factory(container.terminalCenter)
    )
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val createError by vm.lastCreateError.collectAsState()

    // Where new sessions land: the project we were opened from (project
    // card / editor toolbar), else the projects root (v0.1.8 default).
    val projectDir = remember(projectRef) {
        projectRef?.takeIf { it.isNotBlank() }?.let { ref ->
            runCatching { container.projectRepository.resolve(ref) }.getOrNull()
                ?.takeIf { it.isDirectory }
        }
    }

    // First visit auto-opens one session (the top-bar +, the FAB and the
    // empty state create more — always in [projectDir] for this screen).
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) vm.createSession(projectDir)
    }
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

    val title = projectDir?.let { "Terminal · ${it.name}" } ?: "Terminal"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // New session — in the same project this screen was
                    // opened for (else the projects root).
                    IconButton(onClick = { vm.createSession(projectDir) }) {
                        Icon(Icons.Filled.Add, contentDescription = "New session")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge-to-edge neutralizes adjustResize (the Editor screen's
                // comment applies here too): IME insets must be applied
                // manually or the keyboard covers the extra-keys row and the
                // active prompt line.
                .imePadding()
        ) {
            // ---- session tab strip (v0: simple strip + close per tab) ----
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
                    TerminalEmptyState(
                        hasError = createError != null,
                        error = createError,
                        onNewSession = { vm.createSession(projectDir) },
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
                            entry.title.ifBlank { "sh" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (entry.finished) {
                            Spacer(Modifier.width(6.dp))
                            // session over (shell exited): dim marker instead
                            // of the old trailing "·" — reads as a state, not
                            // a stray character in the name
                            Box(
                                Modifier
                                    .width(6.dp)
                                    .height(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outline,
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                            )
                        }
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = { onClose(entry.id) },
                            modifier = Modifier.height(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close session",
                                modifier = Modifier.height(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
 * sora-editor pane. The view handles its own scrolling, selection,
 * pinch font scaling and resize; nothing Compose overlays it (gesture
 * lesson).
 */
@Composable
private fun TerminalPane(
    entry: TerminalCenter.SessionEntry,
    center: TerminalCenter,
    ctrlState: TerminalCtrlState,
) {
    val context = LocalContext.current

    // Font state: base size at creation, then pinch-scaled through the
    // view client's onScale (Termux-style). Kept OUTSIDE Compose state —
    // the view's gesture pipeline drives it directly.
    val fontState = remember(context) { TerminalFontState(context) }

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
            setTerminalViewClient(RdViewClient(this, ctrlState, fontState))
            // v0.1.9 hardening: create the renderer HERE, at creation — the
            // second half of the same upstream host contract. setTextSize()
            // is the ONLY place a TerminalRenderer is ever created
            // (TerminalView.java:515), and attachSession() -> updateSize()
            // reads mRenderer.mFontWidth at :990 the moment a session is
            // attached. By then the view is already measured (the create
            // pipeline resolves ~70ms later than the first frame), so the
            // zero-size guard at :987 does not apply — the v0.1.8 flight
            // recorder caught exactly this NPE at the terminal:view-attach
            // crumb. NOTE: the value is PIXELS despite the upstream javadoc
            // claiming dp (TerminalRenderer hands it straight to
            // Paint.setTextSize()). 11dp is the v0.2 default (Termux-sized,
            // a third smaller than v0.1.9's 14 which read as "UI very big";
            // pinch adjusts it live).
            setTextSize(fontState.appliedPx)
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
        // v0.2 input fix: requestFocus alone does NOT show the soft
        // keyboard (documented Android behavior — and Compose interop
        // inherits it); Termux's app layer calls showSoftInput explicitly,
        // ours never did, so the terminal opened focused but could not be
        // typed into. Show it here AND on every tap (view client).
        requestFocusAndShowKeyboard(view)
    }

    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * v0.2 input fix, shared by the pane attach and the view client's tap
 * handler: focus + explicit IME show. The vendored view is a text editor
 * (onCheckIsTextEditor == true) so the IMM serves it an InputConnection
 * — but only ASKS to show the keyboard when the framework decides to,
 * which in Compose interop it never does on its own.
 */
private fun requestFocusAndShowKeyboard(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    view.requestFocus()
    imm?.showSoftInput(view, 0)
}

/**
 * Terminal font: 11dp base, pinch-scaled between 0.5x and 2.5x. The
 * vendored view ACCUMULATES the pinch scale across gestures and hands
 * the total to [TerminalViewClient.onScale]; this class clamps it and
 * applies the resulting px size (skipping redundant setTextSize calls —
 * each one recreates the TerminalRenderer).
 */
private class TerminalFontState(context: Context) {
    val basePx: Int = (TERMINAL_FONT_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    @Volatile
    var appliedPx: Int = basePx
        private set

    @Synchronized
    fun apply(view: TerminalView, accumulatedScale: Float): Float {
        val clamped = accumulatedScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val px = (basePx * clamped).roundToInt().coerceAtLeast(MIN_PX)
        if (px != appliedPx) {
            appliedPx = px
            view.setTextSize(px)
        }
        return clamped
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f
        const val MIN_PX = 8
    }
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
 * consumed (one-shot, like Termux's extra keys). Pinch answers [onScale]
 * (v0.2: live font scaling); taps focus AND show the keyboard (v0.2
 * input fix).
 */
private class RdViewClient(
    private val view: TerminalView,
    private val ctrl: TerminalCtrlState,
    private val font: TerminalFontState,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = font.apply(view, scale)

    override fun onSingleTapUp(e: MotionEvent?) {
        requestFocusAndShowKeyboard(view)
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
private fun TerminalEmptyState(
    hasError: Boolean,
    error: String?,
    onNewSession: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) {
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
                EmptyState(
                    icon = RdIcons.Terminal,
                    title = "No terminal sessions",
                    subtitle = "Open one from a project card or the editor toolbar — the session starts in that project, so cargo run, cargo fetch and cargo test work directly.",
                )
                Spacer(Modifier.height(16.dp))
            }
            ExtendedFloatingActionButton(onClick = onNewSession) {
                Icon(RdIcons.Terminal, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New session")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/** Terminal font size in dp (11, Termux-sized; pinch adjusts live). */
private const val TERMINAL_FONT_SIZE_DP = 11

/**
 * The minimal v0 extra-keys row (plan §11.4): Esc, Tab, Ctrl (toggle),
 * arrows, PgUp/PgDn. Keys write standard escape sequences straight to the
 * session; the Ctrl toggle participates in the view's key pipeline
 * (see [TerminalCtrlState]). v0.2 polish: evenly weighted full-height
 * touch targets instead of cramped text chips.
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
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExtraKey("Esc", Modifier.weight(1f)) { entry.session.write("\u001b") }
            ExtraKey("Tab", Modifier.weight(1f)) { entry.session.write("\t") }
            ExtraKey(
                label = "Ctrl",
                modifier = Modifier.weight(1f),
                highlighted = ctrlVisual,
            ) {
                ctrlState.active = !ctrlState.active
                onCtrlToggled(ctrlState.active)
            }
            ExtraKey("←", Modifier.weight(1f)) { entry.session.write("\u001b[D") }
            ExtraKey("↑", Modifier.weight(1f)) { entry.session.write("\u001b[A") }
            ExtraKey("↓", Modifier.weight(1f)) { entry.session.write("\u001b[B") }
            ExtraKey("→", Modifier.weight(1f)) { entry.session.write("\u001b[C") }
            ExtraKey("PgUp", Modifier.weight(1.25f)) { entry.session.write("\u001b[5~") }
            ExtraKey("PgDn", Modifier.weight(1.25f)) { entry.session.write("\u001b[6~") }
        }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
