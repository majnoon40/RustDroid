package dev.rustdroid.ide.ui.editor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.rustdroid.ide.model.JumpRequest
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sora-editor interop: Rust grammar + TextMate color scheme + jump-to-line.
 * The editor view is owned by Compose via AndroidView; content changes are
 * forwarded to the VM through [onTextChange] (programmatic setText events
 * are filtered so loading a tab does not mark it dirty).
 */
object RustEditor {

    @Volatile private var grammarReady = false

    fun ensureInit(context: Context) {
        if (grammarReady) return
        synchronized(this) {
            if (grammarReady) return
            val assets = context.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            // theme(s): load both, switch current later
            ThemeRegistry.getInstance().apply {
                loadTheme(
                    IThemeSource.fromInputStream(
                        assets.open("textmate/theme-dark.json"), "theme-dark.json", null
                    )
                )
                loadTheme(
                    IThemeSource.fromInputStream(
                        assets.open("textmate/theme-light.json"), "theme-light.json", null
                    ),
                    false
                )
            }
            grammarReady = true
        }
    }

    fun createLanguage(): TextMateLanguage {
        val def = DefaultGrammarDefinition.withGrammarSource(
            grammarSourceFor(RustEditorHolder.assets, "textmate/rust.tmLanguage.json"),
            "rust", "source.rust"
        )
        return TextMateLanguage.create(def, true)
    }

    private fun grammarSourceFor(assets: android.content.res.AssetManager, path: String): IGrammarSource =
        IGrammarSource.fromInputStream(assets.open(path), path, null)

    fun createEditor(context: Context, dark: Boolean): CodeEditor {
        ensureInit(context)
        RustEditorHolder.assets = context.assets
        val editor = CodeEditor(context)
        editor.setEditorLanguage(createLanguage())
        editor.setColorScheme(colorScheme(dark))
        editor.typefaceText = android.graphics.Typeface.MONOSPACE
        editor.setTextSize(14f)
        editor.setWordwrap(false)
        editor.getProps().autoIndent = true
        editor.getProps().symbolPairAutoCompletion = true
        return editor
    }

    fun colorScheme(dark: Boolean): EditorColorScheme {
        val registry = ThemeRegistry.getInstance()
        runCatching {
            registry.setTheme(if (dark) "RustDroid Dark" else "RustDroid Light")
        }
        return TextMateColorScheme.create(registry, registry.currentThemeModel)
    }
}

/** Holds the asset manager for language creation (set in createEditor). */
private object RustEditorHolder {
    @Volatile var assets: android.content.res.AssetManager? = null
}

@Composable
fun CodeEditorPane(
    text: String,
    dark: Boolean,
    onTextChange: (String) -> Unit,
    jumpRequest: JumpRequest?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val editor = remember { RustEditor.createEditor(context, dark) }
    val programmaticSet = remember { AtomicBoolean(false) }

    // text sync: only when the incoming text differs (tab switch / external load)
    LaunchedEffect(text) {
        if (editor.text.toString() != text) {
            programmaticSet.set(true)
            editor.setText(text)
            editor.setSelection(0, 0, false)
        }
    }

    // jump to diagnostic location (1-based -> 0-based)
    LaunchedEffect(jumpRequest) {
        if (jumpRequest != null) {
            val line = (jumpRequest.line - 1).coerceAtLeast(0)
            val col = (jumpRequest.col - 1).coerceAtLeast(0)
            editor.setSelection(line, col, true)
        }
    }

    // theme flip: recreate the color scheme in place
    LaunchedEffect(dark) {
        editor.setColorScheme(RustEditor.colorScheme(dark))
    }

    DisposableEffect(Unit) {
        val receipt = editor.subscribeEvent(
            io.github.rosemoe.sora.event.ContentChangeEvent::class.java
        ) { event, _ ->
            if (event.action == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_SET_NEW_TEXT &&
                programmaticSet.getAndSet(false)
            ) {
                return@subscribeEvent
            }
            onTextChange(editor.text.toString())
        }
        onDispose {
            runCatching { receipt.unsubscribe() }
            editor.release()
        }
    }

    AndroidView(
        factory = { editor },
        modifier = modifier,
    )
}
