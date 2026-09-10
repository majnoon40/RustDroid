package dev.rustdroid.ide.runtime.terminal

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

/**
 * Foreground service keeping the process — and therefore the terminal
 * sessions — alive while at least one session exists (plan §8.3,
 * resolved per review: ships in v0).
 *
 * HONESTY (plan §5.4): this makes sessions survive ROUTINE backgrounding
 * far more reliably; it is NOT process-death survival and no claim of
 * one is made anywhere. App death ends the sessions: the pty master fd
 * closes and the kernel sends SIGHUP to the terminal's foreground
 * process group.
 *
 * MIGRATION DEBT (review P3-10, recorded in DEVIATIONS.md §10):
 * `foregroundServiceType=dataSync` is correct at targetSdk 28 (type
 * enforcement off) and WRONG from API 34 (type-appropriateness enforced)
 * and worse at API 35 (~6 h/day dataSync cap). The terminal's eventual
 * type is `specialUse` with a declared justification — a known
 * targetSdk-28 migration blocker, recorded so it is not discovered late.
 */
class TerminalService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_terminal),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(getString(R.string.notif_terminal_running))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "terminal"
        private const val NOTIF_ID = 42

        /** Starts the service when the live session count is ≥ 1. */
        fun ensureRunning(context: Context, liveSessions: Int) {
            if (liveSessions < 1) return
            val intent = Intent(context, TerminalService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the service when no live sessions remain (the last close). */
        fun ensureStopped(context: Context, liveSessions: Int) {
            if (liveSessions >= 1) return
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
