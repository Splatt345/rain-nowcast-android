package nowcast.app.background

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
import androidx.work.*
import nowcast.app.*
import nowcast.app.R
import nowcast.engine.forecast.*
import java.time.Instant
import java.util.concurrent.TimeUnit

/** WorkManager every 15 min: a point forecast around home for the widgets, then maybe "rain in N min". */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as App).repo
        if (!repo.canRun) return Result.success()
        // the full ensemble runs only while the app is visible; here, the cheap point path
        val (p, issued) = repo.refreshPoint() ?: return Result.success()
        val s = repo.prefs.value
        if (s.notify)
            Notifier.maybeAlert(applicationContext, p.summary,
                Alerts.minutesToRain(p, issued, Instant.now(), s.notifyLeadMin, s.notifyThreshold))
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh"

        /** A widget's "refresh now": one run as soon as there is a network. */
        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        }
        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        }
    }
}

object Notifier {
    private const val CHANNEL = "rain"
    private const val QUIET_MIN = 90L          // one alert per shower, not one per refresh

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Rain alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Rain expected at home soon"
            })
    }

    fun maybeAlert(ctx: Context, summary: String, minutes: Int?) {
        if (minutes == null) return
        val sp = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val last = sp.getLong("last", 0L)
        if (System.currentTimeMillis() - last < QUIET_MIN * 60_000) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(if (minutes <= 1) "Rain any minute" else "Rain in $minutes min")
            .setContentText(summary)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1, n)
        sp.edit().putLong("last", System.currentTimeMillis()).apply()
    }
}
