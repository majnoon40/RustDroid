package dev.rustdroid.ide

import android.app.Application
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.runtime.CrashRecorder

class RustDroidApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Flight recorder FIRST: an uncaught exception anywhere (the
        // terminal bring-up path included) is persisted to
        // files/last-crash.txt and surfaced in-app on the next launch,
        // instead of vanishing with the process. Chained to the platform
        // handler so the system crash flow is unchanged.
        CrashRecorder.install(filesDir)
        container = AppContainer(this)
    }
}
