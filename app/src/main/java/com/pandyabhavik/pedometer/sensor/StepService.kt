package com.pandyabhavik.pedometer.sensor

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pandyabhavik.pedometer.MainActivity
import com.pandyabhavik.pedometer.Notifications
import com.pandyabhavik.pedometer.R
import com.pandyabhavik.pedometer.core.CounterReading
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.StepCounterRules
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.data.StepDao
import com.pandyabhavik.pedometer.data.StepRepository
import com.pandyabhavik.pedometer.widget.Widgets
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Keeps the hardware step counter listening while the phone sleeps. The sensor batches steps in
 * hardware and reports about once a minute, so the phone stays asleep in between. The silent
 * notification shows today's steps; Android requires one for any service that runs all day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class StepService : Service(), SensorEventListener {
    @Inject lateinit var dao: StepDao
    @Inject lateinit var repository: StepRepository
    @Inject lateinit var settings: SettingsStore

    // A failed database or settings write is logged, not an app crash.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Counting error", e) },
    )
    private val lock = Mutex()
    private val readings = Channel<CounterReading>(Channel.UNLIMITED)
    private var recomputeScheduled = false
    private var started = false
    /** The night-mode bits last seen, so a light/dark switch redraws the widgets in the new colours. */
    private var nightMode = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.COUNTING_ID, notification(null, null),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
            )
        } catch (e: Exception) {
            // Android refused (for example started from the background). The next app open or sync retries.
            Log.w(TAG, "Could not start counting in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val manager = getSystemService(SensorManager::class.java)
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sensor == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            // One reading at a time, in the order the sensor sent them. Out of order, a lower count after a
            // higher one looks like a reboot, and the steps between them are counted twice.
            scope.launch {
                for (reading in readings) {
                    try {
                        record(reading)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Could not record steps", e)
                    }
                }
            }
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_MICROS)
            watchToday()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val counter = event.values.firstOrNull()?.toLong() ?: return
        // Sensor timestamps are nanoseconds since boot; convert to wall-clock time.
        val now = System.currentTimeMillis()
        val at = now - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val reading = CounterReading(
            counter = counter,
            atMillis = at.coerceAtMost(now),
            bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0),
            bootedAtMillis = now - SystemClock.elapsedRealtime(),
        )
        readings.trySend(reading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Before the first change the old mode is unknown (-1); one extra redraw then is harmless.
        if (night != nightMode) Widgets.refresh(this, force = true)
        nightMode = night
    }

    // Not cancelled half way when the service stops. The checkpoint is saved before the steps are added:
    // if the app dies between the two writes a batch is lost, rather than counted again on restart.
    private suspend fun record(reading: CounterReading) = withContext(NonCancellable) {
        lock.withLock {
            val step = StepCounterRules.advance(settings.checkpoint(), reading)
            settings.saveCheckpoint(step.checkpoint)
            if (step.steps > 0) {
                val slots = Slots.distribute(step.fromMillis, step.toMillis, step.steps)
                dao.addPhoneSteps(slots)
                scheduleRecompute(repository.daysOf(slots.keys))
            }
        }
    }

    private val pendingDays = HashSet<java.time.LocalDate>()

    /** Merging is cheap but not free: batch sensor updates into one recompute every few seconds. */
    private fun scheduleRecompute(days: Set<java.time.LocalDate>) {
        // Days that arrive while a recompute is already running get a recompute of their own.
        synchronized(pendingDays) {
            pendingDays += days
            if (recomputeScheduled) return
            recomputeScheduled = true
        }
        scope.launch {
            delay(3_000)
            val todo = synchronized(pendingDays) {
                recomputeScheduled = false
                pendingDays.toSet().also { pendingDays.clear() }
            }
            repository.recompute(todo)
            Widgets.refresh(this@StepService)
        }
    }

    /** Keeps the notification showing today's merged total. */
    private fun watchToday() {
        scope.launch {
            repository.todayFlow()
                .flatMapLatest { today -> combine(dao.dayFlow(today.toString()), settings.settings) { day, s -> day?.steps to s.goal } }
                .distinctUntilChanged()
                .collectLatest { (steps, goal) ->
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    runCatching { manager.notify(Notifications.COUNTING_ID, notification(steps, goal)) }
                }
        }
    }

    private fun notification(steps: Long?, goal: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (steps == null) "Counting steps" else "${Format.steps(steps)} steps today"
        val text = if (steps != null && goal != null && goal > 0) {
            val left = goal - steps
            if (left > 0) "${Format.steps(left)} to your goal" else "Goal reached"
        } else null
        return NotificationCompat.Builder(this, Notifications.COUNTING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "StepService"
        private const val BATCH_MICROS = 60_000_000

        fun canRun(context: Context): Boolean {
            val sensor = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
            return sensor != null && permitted
        }

        fun hasSensor(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        /** Starts counting if it can; safe to call often. Returns false if Android refused. */
        fun start(context: Context): Boolean {
            if (!canRun(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, StepService::class.java))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start counting", e)
                false
            }
        }
    }
}
