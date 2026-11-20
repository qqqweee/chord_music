package com.example.chord.data.download

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.chord.MainActivity
import com.example.chord.playback.DownloadStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps downloads alive in the background. Watches [DownloadStore].
 * Stops itself when the line is empty.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Download service error", e) }
    )
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }

        // Must call startForeground fast. If the system or manifest refuses, stop cleanly (no crash).
        try {
            val first = build(DownloadStore.active.value.size.coerceAtLeast(1), -1f)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, first)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        watcher?.cancel()
        watcher = scope.launch {
            DownloadStore.active.combine(DownloadStore.progress) { list, progress -> list to progress }
                .collect { (list, progress) ->
                    if (list.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val running = list.firstNotNullOfOrNull { progress[it.videoId]?.takeIf { p -> p > 0f } } ?: -1f
                        nm.notify(ID, build(list.size, running))
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun build(count: Int, progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (count == 1) "Downloading 1 song" else "Downloading $count songs")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (progress >= 0f) setProgress(100, (progress * 100).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChordDownloadSvc"
        private const val CHANNEL = "downloads"
        private const val ID = 4207

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, DownloadService::class.java)
                )
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }
    }
}
