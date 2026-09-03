package dev.rustdroid.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    const val EDITOR = "editor/{project}"
    const val DEPS = "deps/{project}"
    const val SETTINGS = "settings"

    fun editor(project: String) = "editor/${android.net.Uri.encode(project)}"
    fun deps(project: String) = "deps/${android.net.Uri.encode(project)}"
}

/** Tiny VM holding toolchain state for navigation decisions. */
class AppViewModel(val container: AppContainer) : ViewModel() {
    val toolchainState = container.toolchainManager.state
}

@Composable
fun AppRoot(container: AppContainer) {
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
            HomeScreen(container, onOpenProject = { name ->
                nav.navigate(Routes.editor(name))
            }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.EDITOR) { entry ->
            val project = entry.arguments?.getString("project") ?: return@composable
            EditorScreen(container, project, onOpenDeps = { nav.navigate(Routes.deps(it)) })
        }
        composable(Routes.DEPS) { entry ->
            val project = entry.arguments?.getString("project") ?: return@composable
            DepsScreen(container, project, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            // "Re-verify health" ends here: once verification completes
            // successfully, return the user to their projects instead of
            // leaving them staring at the Settings screen. Failures stay
            // on Settings (the toolchain card shows what broke).
            var sawVerifying by remember { mutableStateOf(false) }
            val st by container.toolchainManager.state.collectAsState()
            LaunchedEffect(st) {
                if (st is ToolchainState.Verifying) {
                    sawVerifying = true
                } else if (st is ToolchainState.Ready && sawVerifying) {
                    sawVerifying = false
                    nav.popBackStack()
                }
            }
            SettingsScreen(container)
        }
    }
}
