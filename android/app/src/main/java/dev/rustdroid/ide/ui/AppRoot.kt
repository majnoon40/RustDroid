package dev.rustdroid.ide.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.ui.deps.DepsScreen
import dev.rustdroid.ide.ui.editor.EditorScreen
import dev.rustdroid.ide.ui.gate.GateScreen
import dev.rustdroid.ide.ui.home.HomeScreen
import dev.rustdroid.ide.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Routes {
    const val GATE = "gate"
    const val HOME = "home"
    const val EDITOR = "editor/{project}?file={file}"
    const val DEPS = "deps/{project}"
    const val SETTINGS = "settings"

    fun editor(project: String, file: String = ""): String =
        "editor/${android.net.Uri.encode(project)}?file=${android.net.Uri.encode(file)}"
    fun deps(project: String) = "deps/${android.net.Uri.encode(project)}"
}

/** Tiny VM holding toolchain state for navigation decisions. */
class AppViewModel(val container: AppContainer) : ViewModel() {
    val toolchainState = container.toolchainManager.state
}

@Composable
fun AppRoot(
    container: AppContainer,
    pendingOpenRs: androidx.compose.runtime.MutableState<Uri?>,
    onPendingRsConsumed: () -> Unit,
) {
    val vm: AppViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
        }
    )
    val state by vm.toolchainState.collectAsState()
    val nav = rememberNavController()
    val context = LocalContext.current

    val startDest = if (state is ToolchainState.Ready) Routes.HOME else Routes.GATE

    // If the toolchain is removed or fails health mid-session (e.g. a
    // re-verify fails in Settings), take the user back to the Gate where
    // the failure is explained and retry is offered. Guarded by the
    // current destination so a normal start never double-pushes.
    LaunchedEffect(state) {
        if (state is ToolchainState.NotInstalled || state is ToolchainState.Failed) {
            val route = nav.currentDestination?.route
            if (route != null && route != Routes.GATE) {
                nav.navigate(Routes.GATE) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    // "Open with RustDroid" for .rs sources (ACTION_VIEW). The import is
    // pure file work — no toolchain needed — so it also works before the
    // toolchain is installed; the editor is reachable right away (Run
    // needs an installed toolchain, editing does not). If the app cold-
    // starts on the Gate, the import waits here until the toolchain is
    // Ready, then lands the user on their file.
    LaunchedEffect(pendingOpenRs.value, state) {
        val uri = pendingOpenRs.value ?: return@LaunchedEffect
        if (state is ToolchainState.Ready) {
            val target = withContext(Dispatchers.IO) {
                runCatching { container.rsImport.import(uri) }
            }
            onPendingRsConsumed()
            target.onSuccess {
                if (nav.currentDestination?.route?.startsWith("editor/") != true) {
                    nav.navigate(Routes.editor(it.projectName, it.relativePath)) {
                        launchSingleTop = true
                    }
                } else {
                    // already in an editor: swap to the imported project
                    nav.navigate(Routes.editor(it.projectName, it.relativePath)) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                }
            }
            target.onFailure {
                android.widget.Toast.makeText(
                    context, "could not open file: ${it.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.GATE) {
            GateScreen(
                container,
                onReady = {
                    // Guarded: when the app already started at HOME (toolchain
                    // ready), this screen isn't reachable; when arriving here
                    // from an in-session install, exactly one navigate runs.
                    if (nav.currentDestination?.route == Routes.GATE) {
                        nav.navigate(Routes.HOME) {
                            popUpTo(Routes.GATE) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(container, onOpenProject = { name ->
                nav.navigate(Routes.editor(name))
            }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("project") { type = NavType.StringType },
                navArgument("file") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val project = entry.arguments?.getString("project") ?: return@composable
            val file = entry.arguments?.getString("file").orEmpty()
            EditorScreen(
                container, project,
                initialFile = file.ifBlank { null },
                onOpenDeps = { nav.navigate(Routes.deps(it)) },
            )
        }
        composable(Routes.DEPS) { entry ->
            val project = entry.arguments?.getString("project") ?: return@composable
            DepsScreen(container, project, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            // "Re-verify health" ends here: once verification completes
            // successfully, return the user to their projects instead of
            // leaving them staring at the Settings screen. Failures stay
            // on Settings (the toolchain card shows what broke) — and a
            // Failed state re-routes to the Gate via the effect above.
            //
            // This watches the verifyPassTick COUNTER, not the intermediate
            // Verifying states: the re-verify smoke test takes minutes,
            // users background the app mid-run, and StateFlow conflates all
            // intermediate Verifying emissions away while Compose
            // recomposition is paused — the old "did I see Verifying?"
            // watcher missed exactly that case. A monotonic tick survives
            // conflation: snapshot at entry, react to any increase.
            val passTickEntry = remember { container.toolchainManager.verifyPassTick.value }
            val passTick by container.toolchainManager.verifyPassTick.collectAsState()
            LaunchedEffect(passTick) {
                if (passTick > passTickEntry) {
                    nav.popBackStack()
                }
            }
            SettingsScreen(container)
        }
    }
}
