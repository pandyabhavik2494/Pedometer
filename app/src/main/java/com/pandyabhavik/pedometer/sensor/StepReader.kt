package com.pandyabhavik.pedometer.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pandyabhavik.pedometer.AppEntryPoint
import com.pandyabhavik.pedometer.core.CounterReading
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.StepCounterRules
import com.pandyabhavik.pedometer.widget.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the phone's hardware step counter. The chip keeps its own running total all day while the phone
 * sleeps, so nothing has to stay running: a read every 15 minutes (and live while the app is open) picks
 * up every step since the last one. No always-on service, so no notification.
 */
object StepReader {
    private const val TAG = "StepReader"
    private val lock = Mutex()

    fun hasSensor(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun canRead(context: Context): Boolean =
        hasSensor(context) && ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /** Takes one reading and records it. Returns false when there is no sensor, no permission or no answer. */
    suspend fun readNow(context: Context): Boolean {
        val app = context.applicationContext
        if (!canRead(app)) return false
        val manager = app.getSystemService(SensorManager::class.java) ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return false
        val reading = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<CounterReading> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(reading(app, event))
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                // Ask for the current total now rather than at the next step.
                manager.flush(listener)
            }
        } ?: return false
        return try {
            record(app, reading)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not record steps", e)
            false
        }
    }

    private fun reading(context: Context, event: SensorEvent): CounterReading {
        // Sensor timestamps are nanoseconds since boot; convert to wall-clock time.
        val now = System.currentTimeMillis()
        val at = now - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        return CounterReading(
            counter = event.values.firstOrNull()?.toLong() ?: 0,
            atMillis = at.coerceAtMost(now),
            bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0),
            bootedAtMillis = now - SystemClock.elapsedRealtime(),
        )
    }

    // One reading at a time, and not cancelled half way. The checkpoint is saved before the steps are
    // added: if the app dies between the two writes a batch is lost, rather than counted again.
    private suspend fun record(context: Context, reading: CounterReading) = withContext(NonCancellable) {
        val app = AppEntryPoint.get(context)
        val slots = lock.withLock {
            val step = StepCounterRules.advance(app.settings().checkpoint(), reading)
            app.settings().saveCheckpoint(step.checkpoint)
            if (step.steps > 0) Slots.distribute(step.fromMillis, step.toMillis, step.steps).also { app.dao().addPhoneSteps(it) } else null
        }
        if (slots != null) {
            val repository = app.repository()
            repository.recompute(repository.daysOf(slots.keys))
            Widgets.refresh(context)
        }
    }

    /** Counts live while the app is on screen, so Today moves as you walk. Stops when the app is closed. */
    class Live(context: Context) : DefaultLifecycleObserver, SensorEventListener {
        private val app = context.applicationContext
        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Counting error", e) },
        )
        private var readings: Channel<CounterReading>? = null

        override fun onStart(owner: LifecycleOwner) {
            if (!canRead(app)) return
            val manager = app.getSystemService(SensorManager::class.java) ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            // In the order the sensor sent them: out of order, a newer total could be recorded before an older one.
            val channel = Channel<CounterReading>(Channel.UNLIMITED)
            readings = channel
            scope.launch { for (r in channel) runCatching { record(app, r) }.onFailure { Log.w(TAG, "Could not record steps", it) } }
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        override fun onStop(owner: LifecycleOwner) {
            app.getSystemService(SensorManager::class.java)?.unregisterListener(this)
            readings?.close()
            readings = null
        }

        override fun onSensorChanged(event: SensorEvent) {
            readings?.trySend(reading(app, event))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}
