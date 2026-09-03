package dev.rustdroid.ide.di

import android.content.Context
import dev.rustdroid.ide.projects.CratesIoClient
import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.runtime.CaBundle
import dev.rustdroid.ide.runtime.CargoRunner
import dev.rustdroid.ide.runtime.ProcEnv
import dev.rustdroid.ide.toolchain.ToolchainManager
import dev.rustdroid.ide.toolchain.ToolchainPaths
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container — no DI framework (F-Droid-simple, fewer
 * moving parts). One instance per process, owned by RustDroidApp.
 */
class AppContainer(val context: Context) {

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES) // big downloads
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val cargoRunner: CargoRunner by lazy { CargoRunner() }

    val toolchainPaths: ToolchainPaths by lazy { ToolchainPaths(context.filesDir) }

    val toolchainManager: ToolchainManager by lazy {
        ToolchainManager(context, toolchainPaths, cargoRunner, http)
    }

    val projectsRoot: File by lazy { File(context.filesDir, "projects") }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            projectsRoot = projectsRoot,
            runner = cargoRunner,
            envProvider = {
                ProcEnv.env(
                    toolchainPaths.prefix,
                    context.filesDir,
                    assetProvider = { CaBundle.readAssetPem(context.assets) },
                )
            },
            cargoPath = { ProcEnv.toolchainCommand(toolchainPaths.prefix, "cargo") },
        )
    }

    val cratesIoClient: CratesIoClient by lazy { CratesIoClient(http) }
}
