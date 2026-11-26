package com.example.chord.data.stats

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.chord.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Weekly / monthly / yearly "your story is ready" notification.
 * Call [schedule] once in MainActivity.onCreate. A worker wakes every 3 hours,
 * asks [RecapSchedule] if a drop is due, and posts the notification once per drop.
 * Tap opens the story (see MainActivity.handleRecapIntent).
 */
object RecapNotifier {
    const val EXTRA_PERIOD = "chord_recap_period"
    private const val CHANNEL = "recaps"
    private const val WORK = "chord_recap_check"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RecapWorker>(3, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private val copy = mapOf(
        RecapPeriod.Week to ("Your week in sound is ready" to "See who you had on repeat. Tap to press play."),
        RecapPeriod.Month to ("Your month in sound is here" to "Top artists, top tracks, your listening DNA. Tap to open."),
        RecapPeriod.Year to ("Your year in sound is here" to "A whole year of music. Wait for it. Tap to open."),
    )

    /** Safe to call when the user said no to notifications: it just returns. */
    fun show(ctx: Context, p: RecapPeriod) {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Your recaps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Weekly, monthly and yearly listening stories"
                }
            )
        }
        val open = PendingIntent.getActivity(
            app, 5000 + p.ordinal,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PERIOD, p.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = copy.getValue(p)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play) // swap for your mono logo drawable
            .setColor(0xFFC4472E.toInt())                   // Chord coral
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(5100 + p.ordinal, n) }
    }
}

class RecapWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RecapSchedule.dueForNotification(applicationContext).forEach { RecapNotifier.show(applicationContext, it) }
        return Result.success()
    }
}
