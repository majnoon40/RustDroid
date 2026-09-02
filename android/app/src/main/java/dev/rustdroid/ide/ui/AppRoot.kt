package dev.rustdroid.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    val started = remember { arrayOf(false) }

    val startDest = if (state is ToolchainState.Ready) Routes.HOME else Routes.GATE

    LaunchedEffect(state) {
        when (state) {
            is ToolchainState.Ready -> {
                if (!started[0]) {
                    started[0] = true
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.GATE) { inclusive = true }
                    }
                } else {
                    // re-verified from settings — stay put
                }
            }
            is ToolchainState.NotInstalled, is ToolchainState.Failed -> {
                if (started[0]) {
                    // toolchain removed/failed after being ready — back to gate
                    nav.navigate(Routes.GATE) {
                        popUpTo(0) { inclusive = true }
                    }
                    started[0] = false
                }
            }
            else -> {}
        }
    }

    NavHost(navController = nav, startDestination = startDest) {
        composable(Routes.GATE) {
            GateScreen(container, onReady = {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.GATE) { inclusive = true }
                }
            })
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
            SettingsScreen(container)
        }
    }
}
