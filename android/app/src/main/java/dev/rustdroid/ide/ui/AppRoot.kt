package dev.rustdroid.ide.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

object Routes {
    const val GATE = "gate"
    const val HOME = "home"

    /**
     * The project argument is a REF, not just a name: internal projects are
     * named (their dir under files/projects), external ones — folders opened
     * in place anywhere on storage — travel as their absolute path
     * (URL-encoded so it stays one path segment). Both resolve in
     * ProjectRepository.resolve.
     */
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
            HomeScreen(
                container,
                onOpenProject = { ref -> nav.navigate(Routes.editor(ref)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
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
            // Verifying states: the re-verify smoke test takes minutes, users
            // background the app mid-run, and StateFlow conflates all
            // intermediate Verifying emissions away while Compose
            // recomposition is paused — the old "did I see Verifying?"
            // watcher missed exactly that case. A monotonic tick survives
            // conflation: snapshot at entry, react to any increase. The run
            // itself lives in the foreground service (ToolchainInstallService
            // ACTION_REVERIFY), so the process — and this tick — survive the
            // backgrounding that used to kill the re-verify in the first
            // place.
            val passTickEntry = remember { container.toolchainManager.verifyPassTick.value }
            val passTick by container.toolchainManager.verifyPassTick.collectAsState()
            LaunchedEffect(passTick) {
                if (passTick > passTickEntry && nav.currentDestination?.route == Routes.SETTINGS) {
                    nav.popBackStack()
                }
            }
            SettingsScreen(container)
        }
    }

    // "Open with RustDroid" for .rs sources (ACTION_VIEW): ask where the
    // file should go (new project the user names, or an existing project)
    // instead of dumping it into a fixed scratch project. Pure file work —
    // no toolchain needed — so it also works before install; Run needs one,
    // editing does not.
    pendingOpenRs.value?.let { uri ->
        OpenRsDialog(
            container = container,
            uri = uri,
            onOpened = { ref, rel ->
                onPendingRsConsumed()
                nav.navigate(Routes.editor(ref, rel)) {
                    launchSingleTop = true
                }
            },
            onDismiss = { onPendingRsConsumed() },
        )
    }
}
