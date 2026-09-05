package dev.rustdroid.ide.toolchain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.rustdroid.ide.MainActivity
import dev.rustdroid.ide.R
import dev.rustdroid.ide.model.ToolchainState
import dev.rustdroid.ide.util.Fs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the toolchain download+install AND the
 * minutes-long re-verify, so both survive screen-off and backgrounding.
 * The manager owns all logic; this class owns process lifetime + the
 * progress notification.
 *
 * Re-verify used to run in SettingsViewModel's viewModelScope: the smoke
 * test takes minutes, users background the app mid-run, and without a
 * foreground service Android kills the process — the verify coroutine
 * died with it, verifyPassTick never incremented, and the user returned
 * to a Settings screen that sat there doing nothing. Running it here
 * keeps the process (and the tick) alive until the run finishes.
 */
class ToolchainInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // One collector for the service's whole lifetime (started in
        // onStartCommand it would pile up one per start).
        val manager = (application as dev.rustdroid.ide.RustDroidApp).container.toolchainManager
        scope.launch {
            manager.state.collect { st -> updateNotification(st) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as dev.rustdroid.ide.RustDroidApp).container.toolchainManager
        val reverify = intent?.action == ACTION_REVERIFY
        startInForeground(reverify)

        // drive the run; terminal state stops the service
        scope.launch {
            if (reverify) {
                manager.reverify()
            } else {
                manager.installFromNetwork()
            }
            val success = manager.state.value is ToolchainState.Ready
            finishNotification(success, reverify)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(reverify: Boolean) {
        val notif = buildNotification(
            if (reverify) "re-verifying toolchain health…" else "preparing…",
            ongoing = true, indeterminate = true,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(state: ToolchainState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (state) {
            is ToolchainState.Downloading -> {
                val text = if (state.total != null && state.total > 0) {
                    "${Fs.humanBytes(state.bytes)} / ${Fs.humanBytes(state.total)} (${state.bytes * 100 / state.total}%)"
                } else {
                    "${Fs.humanBytes(state.bytes)} downloaded"
                }
                nm.notify(
                    NOTIF_ID,
                    buildNotification(
                        text,
                        ongoing = true,
                        indeterminate = state.total == null,
                        progress = if (state.total != null && state.total > 0)
                            (state.bytes * 100 / state.total).toInt() else null,
                    )
                )
            }
            is ToolchainState.Extracting -> nm.notify(
                NOTIF_ID, buildNotification("extracting toolchain…", ongoing = true, indeterminate = true)
            )
            is ToolchainState.Verifying -> nm.notify(
                NOTIF_ID, buildNotification("verifying install health…", ongoing = true, indeterminate = true)
            )
            else -> {}
        }
    }

    private fun finishNotification(success: Boolean, reverify: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            success -> getString(R.string.notif_toolchain_done)
            reverify -> "re-verification failed — open RustDroid for details"
            else -> "installation failed — open RustDroid for details"
        }
        nm.notify(NOTIF_ID, buildNotification(text, ongoing = false, indeterminate = false))
    }

    private fun buildNotification(
        text: String, ongoing: Boolean, indeterminate: Boolean, progress: Int? = null,
    ): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rustdroid)
            .setContentTitle(getString(R.string.notif_toolchain_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, indeterminate)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_toolchain),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "toolchain-install"
        const val NOTIF_ID = 42
        const val ACTION_REVERIFY = "dev.rustdroid.ide.action.REVERIFY"

        fun start(context: Context) = startWithAction(context, null)

        /** Runs the full health check in the foreground (Settings → Re-verify). */
        fun startReverify(context: Context) = startWithAction(context, ACTION_REVERIFY)

        private fun startWithAction(context: Context, action: String?) {
            val intent = Intent(context, ToolchainInstallService::class.java)
            if (action != null) intent.action = action
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
