package dev.rustdroid.ide

import android.app.Application
import dev.rustdroid.ide.di.AppContainer

class RustDroidApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
