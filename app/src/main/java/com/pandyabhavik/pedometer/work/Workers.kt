package com.pandyabhavik.pedometer.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pandyabhavik.pedometer.MainActivity
import com.pandyabhavik.pedometer.Notifications
import com.pandyabhavik.pedometer.R
import com.pandyabhavik.pedometer.core.Badge
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.data.StepDao
import com.pandyabhavik.pedometer.data.StepRepository
import com.pandyabhavik.pedometer.health.SyncEngine
import com.pandyabhavik.pedometer.sensor.StepReader
import com.pandyabhavik.pedometer.widget.Widgets
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** The periodic background sync. Reads the phone's counter first so write-back has the latest steps. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StepReader.readNow(applicationContext)
        engine.sync()
        return Result.success()
    }
}

/** Every 15 minutes: picks up the steps the phone's step chip counted since the last read. */
@HiltWorker
class CountWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StepReader.readNow(applicationContext)
        // Also catches a light/dark switch while the app was closed: widgets redraw in the new colours.
        Widgets.refresh(applicationContext, force = true)
        return Result.success()
    }
}

/** Runs once each evening at the reminder time, then books the next evening. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsStore,
    private val repository: StepRepository,
    private val dao: StepDao,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = settings.current()
        // Doze can hold the job for hours. Past midnight it would nag about a day that has only just begun.
        val lateBy = LocalTime.now().let { it.hour * 60 + it.minute } - s.reminderMinuteOfDay
        try {
            if (s.reminderOn && lateBy in 0..MAX_LATE_MINUTES) {
                StepReader.readNow(applicationContext)
                repository.recompute(setOf(repository.today()))
                val steps = dao.day(repository.today().toString())?.steps ?: 0
                val threshold = s.goal * s.reminderBelowPercent / 100
                if (steps < threshold && steps < s.goal) {
                    val left = s.goal - steps
                    // About 100 steps a minute at an easy walk.
                    val minutes = (left / 100.0).roundToLong().coerceAtLeast(1)
                    Reminders.show(
                        applicationContext,
                        "${Format.steps(left)} steps to go",
                        "About ${Format.minutes(minutes.toInt())} of walking gets you to ${Format.steps(s.goal)}.",
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A failed run would book no next evening, and reminders would stop until the app is opened.
            Log.w("ReminderWorker", "Reminder failed", e)
        }
        Schedules.scheduleReminder(applicationContext, s.reminderMinuteOfDay, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    private companion object {
        const val MAX_LATE_MINUTES = 120
    }
}

object Reminders {
    fun show(context: Context, title: String, text: String) {
        if (!allowed(context)) return
        NotificationManagerCompat.from(context).notify(
            Notifications.REMINDER_ID,
            NotificationCompat.Builder(context, Notifications.REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_steps)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

object BadgeNotifier {
    fun notify(context: Context, badges: List<Badge>) {
        if (badges.isEmpty() || !Reminders.allowed(context)) return
        val first = badges.first()
        val title = if (badges.size == 1) "New badge: ${first.title}" else "${badges.size} new badges"
        NotificationManagerCompat.from(context).notify(
            Notifications.BADGE_ID,
            NotificationCompat.Builder(context, Notifications.BADGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_steps)
                .setContentTitle(title)
                .setContentText(badges.joinToString(", ") { it.title })
                .setAutoCancel(true)
                .setContentIntent(Reminders.openApp(context))
                .build(),
        )
    }
}

object Schedules {
    private const val SYNC = "sync"
    private const val REMINDER = "reminder"
    private const val COUNT = "count"

    /** The 15-minute step read, the shortest period Android allows for background work. */
    fun scheduleCounting(context: Context) {
        val request = PeriodicWorkRequestBuilder<CountWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(COUNT, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleSync(context: Context, minutes: Int, replace: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNowInBackground(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }

    /**
     * Books the reminder for the next time the clock reads [minuteOfDay]. KEEP leaves a booked
     * reminder alone (app start), REPLACE moves it (time changed), APPEND_OR_REPLACE queues the
     * next one behind the reminder that is running.
     */
    fun scheduleReminder(context: Context, minuteOfDay: Int, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var next = now.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val delay = next.atZone(zone).toInstant().toEpochMilli() - System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            REMINDER,
            policy,
            request,
        )
    }
}
