package dev.rustdroid.ide.di

import android.content.Context
import dev.rustdroid.ide.projects.CratesIoClient
import dev.rustdroid.ide.projects.FolderLink
import dev.rustdroid.ide.projects.ProjectRepository
import dev.rustdroid.ide.projects.RsImport
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
            // API-sized budget only (crates.io search): a whole-call
            // timeout on a client used for bulk transfers silently kills
            // big bodies at exactly 5:00 — bulk consumers (ArtifactDownloader)
            // must opt OUT explicitly with callTimeout(0).
            .callTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Android signal sender: lets build cancellation SIGKILL cargo's
     *  whole process tree (rustc, ld.lld, build scripts) — see ProcTree. */
    val cargoRunner: CargoRunner by lazy {
        CargoRunner(signal = { pid, sig ->
            android.os.Process.sendSignal(pid.toInt(), sig)
        })
    }

    val toolchainPaths: ToolchainPaths by lazy { ToolchainPaths(context.filesDir) }

    val toolchainManager: ToolchainManager by lazy {
        ToolchainManager(context, toolchainPaths, cargoRunner, http)
    }

    val projectsRoot: File by lazy { File(context.filesDir, "projects") }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            projectsRoot = projectsRoot,
            // external = folders opened in place, remembered across restarts
            externalRegistry = File(context.filesDir, "external-projects.txt"),
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

    /** ACTION_VIEW .rs intake: reads the source; placement is the user's call. */
    val rsImport: RsImport by lazy { RsImport(context) }

    /** SAF folder-pick → real path (open-folder-as-project glue). */
    val folderLink: FolderLink by lazy { FolderLink() }
}
