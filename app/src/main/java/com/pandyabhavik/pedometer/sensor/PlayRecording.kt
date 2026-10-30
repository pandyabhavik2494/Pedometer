package com.pandyabhavik.pedometer.sensor

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.pandyabhavik.pedometer.AppEntryPoint
import com.pandyabhavik.pedometer.core.Slots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Google Play's on-device step recording, the Android counterpart of the iPhone's motion chip that
 * Pedometer++ reads. Play services keeps counting in the background for every app that subscribed, with no
 * service or notification of ours, and keeps the last few days. Each read puts those 5-minute counts in
 * place of the phone slots it covers, so reading again never adds anything twice.
 */
object PlayRecording {
    private const val TAG = "PlayRecording"
    private const val PREFS = "play_recording"
    private const val SINCE = "since"
    /** Play keeps about three days; reading a little less stays clear of the edge. */
    private const val WINDOW_MILLIS = 2L * 24 * 60 * 60 * 1000

    fun available(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
            context, LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE,
        ) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** When recording started: steps before then aren't in Play's record, so slots before it are left alone. */
    private fun since(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SINCE, 0L)

    /**
     * Subscribes (a no-op once subscribed) and copies the recorded steps into the phone slots.
     * Returns false when Play can't record here, so the caller falls back to the step chip.
     */
    suspend fun sync(context: Context): Boolean {
        val app = context.applicationContext
        if (!StepReader.canRead(app) || !available(app)) return false
        return try {
            val client = FitnessLocal.getLocalRecordingClient(app)
            client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
            val now = System.currentTimeMillis()
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (since(app) == 0L) prefs.edit().putLong(SINCE, now).apply()
            // Whole slots only, from the first slot that started after recording did.
            val from = maxOf(Slots.startOf(now - WINDOW_MILLIS), Slots.startOf(since(app)) + Slots.MILLIS)
            val to = Slots.startOf(now) + Slots.MILLIS
            if (to <= from) return true
            val request = LocalDataReadRequest.Builder()
                .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .bucketByTime(5, TimeUnit.MINUTES)
                .setTimeRange(from / 1000, to / 1000, TimeUnit.SECONDS)
                .build()
            val response = client.readData(request).await()
            val slots = HashMap<Long, Long>()
            for (bucket in response.buckets) {
                val start = Slots.startOf(bucket.getStartTime(TimeUnit.MILLISECONDS))
                val steps = bucket.dataSets.sumOf { set ->
                    set.dataPoints.sumOf { point -> point.getValue(LocalField.FIELD_STEPS).asInt().toLong() }
                }
                if (steps > 0) slots[start] = (slots[start] ?: 0L) + steps
            }
            val entry = AppEntryPoint.get(app)
            val before = entry.dao().phoneSlots(from, to).associate { it.slotStart to it.steps }
            if (before != slots) {
                entry.dao().replacePhoneSteps(from, to, slots)
                val repository = entry.repository()
                repository.recompute(repository.daysOf(before.keys + slots.keys + setOf(from, to - 1)))
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Google Play step recording unavailable", e)
            false
        }
    }
}
