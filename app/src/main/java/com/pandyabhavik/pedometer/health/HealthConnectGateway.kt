package com.pandyabhavik.pedometer.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.data.FloorSlot
import com.pandyabhavik.pedometer.data.HeartSlot
import com.pandyabhavik.pedometer.data.Session
import com.pandyabhavik.pedometer.data.SourceSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

enum class HealthConnectStatus { AVAILABLE, NEEDS_UPDATE, NOT_INSTALLED }

/** What Health Connect holds for a time range, cut into 5-minute slots per source. */
class HealthData(
    val steps: List<SourceSlot>,
    val heart: List<HeartSlot>,
    val floors: List<FloorSlot>,
    val sessions: List<Session>,
) {
    val origins: Set<String> get() = (steps.map { it.origin } + heart.map { it.origin } + floors.map { it.origin } +
        sessions.map { it.origin }).toSet()
}

@Singleton
class HealthConnectGateway @Inject constructor(@ApplicationContext private val context: Context) {
    val ownPackage: String = context.packageName

    fun status(): HealthConnectStatus = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_UPDATE
        else -> HealthConnectStatus.NOT_INSTALLED
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> =
        if (status() == HealthConnectStatus.AVAILABLE) client.permissionController.getGrantedPermissions() else emptySet()

    /** Opens the Play Store page for Health Connect (Android 13 and older, where it is an app). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Health Connect's own screen, to manage this app's permissions and data. */
    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The time of the oldest steps record, so a first import starts there instead of years before. */
    suspend fun oldestSteps(after: Instant): Instant? {
        val response = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(after), ascendingOrder = true, pageSize = 1),
        )
        return response.records.firstOrNull()?.startTime
    }

    /** Reads every type for [from, to) and slots it. This app's own records are left out. */
    suspend fun read(from: Long, to: Long, granted: Set<String>): HealthData {
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))

        val stepCounts = HashMap<Pair<String, Long>, Long>()
        val distances = HashMap<Pair<String, Long>, Double>()
        if (READ_STEPS in granted) {
            readAll(StepsRecord::class, range).forEach { r ->
                val origin = r.metadata.dataOrigin.packageName
                Slots.distribute(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.count).forEach { (slot, n) ->
                    stepCounts.merge(origin to slot, n, Long::plus)
                }
            }
        }
        if (READ_DISTANCE in granted) {
            readAll(DistanceRecord::class, range).forEach { r ->
                val origin = r.metadata.dataOrigin.packageName
                Slots.distributeAmount(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.distance.inMeters)
                    .forEach { (slot, m) -> distances.merge(origin to slot, m, Double::plus) }
            }
        }
        val steps = (stepCounts.keys + distances.keys)
            .filter { it.first != ownPackage && it.second in from until to }
            .map { key -> SourceSlot(key.first, key.second, stepCounts[key] ?: 0, distances[key] ?: 0.0) }

        val heart = HashMap<Pair<String, Long>, LongArray>()
        if (READ_HEART in granted) {
            readAll(HeartRateRecord::class, range).forEach { r ->
                val origin = r.metadata.dataOrigin.packageName
                r.samples.forEach { sample ->
                    val key = origin to Slots.startOf(sample.time.toEpochMilli())
                    val bpm = sample.beatsPerMinute
                    val mm = heart.getOrPut(key) { longArrayOf(bpm, bpm) }
                    mm[0] = minOf(mm[0], bpm)
                    mm[1] = maxOf(mm[1], bpm)
                }
            }
        }
        val heartSlots = heart.filter { it.key.first != ownPackage && it.key.second in from until to }
            .map { (k, v) -> HeartSlot(k.first, k.second, v[0], v[1]) }

        val floors = HashMap<Pair<String, Long>, Double>()
        if (READ_FLOORS in granted) {
            readAll(FloorsClimbedRecord::class, range).forEach { r ->
                val origin = r.metadata.dataOrigin.packageName
                Slots.distributeAmount(r.startTime.toEpochMilli(), r.endTime.toEpochMilli(), r.floors)
                    .forEach { (slot, f) -> floors.merge(origin to slot, f, Double::plus) }
            }
        }
        val floorSlots = floors.filter { it.key.first != ownPackage && it.key.second in from until to }
            .map { (k, v) -> FloorSlot(k.first, k.second, v) }

        val sessions = if (READ_EXERCISE in granted) {
            readAll(ExerciseSessionRecord::class, range)
                .filter { it.metadata.dataOrigin.packageName != ownPackage }
                .filter { it.startTime.toEpochMilli() in from until to }
                .map {
                    Session(
                        id = it.metadata.id,
                        origin = it.metadata.dataOrigin.packageName,
                        start = it.startTime.toEpochMilli(),
                        end = it.endTime.toEpochMilli(),
                        type = it.exerciseType,
                        title = it.title,
                    )
                }
        } else emptyList()

        return HealthData(steps, heartSlots, floorSlots, sessions)
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageSize = 5000, pageToken = token))
            out += response.records
            token = response.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** Writes this phone's steps, one record per 5-minute slot, replacing any earlier version of each slot. */
    suspend fun writePhoneSteps(slots: List<Pair<Long, Long>>, version: Long) {
        if (slots.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val records = slots.map { (slot, steps) ->
            val start = Instant.ofEpochMilli(slot)
            val end = Instant.ofEpochMilli(slot + Slots.MILLIS)
            StepsRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                count = steps,
                metadata = Metadata(
                    clientRecordId = clientId(slot),
                    clientRecordVersion = version,
                    device = Device(type = Device.TYPE_PHONE),
                ),
            )
        }
        records.chunked(500).forEach { client.insertRecords(it) }
    }

    suspend fun deletePhoneSteps(slots: List<Long>) {
        if (slots.isEmpty()) return
        slots.chunked(500).forEach { chunk ->
            client.deleteRecords(StepsRecord::class, recordIdsList = emptyList(), clientRecordIdsList = chunk.map(::clientId))
        }
    }

    private fun clientId(slot: Long) = "phone-steps-$slot"

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"

        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val WRITE_STEPS = HealthPermission.getWritePermission(StepsRecord::class)
        val READ_DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        val READ_FLOORS = HealthPermission.getReadPermission(FloorsClimbedRecord::class)
        val READ_EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val READ_HEART = HealthPermission.getReadPermission(HeartRateRecord::class)
        const val READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        val ALL = setOf(
            READ_STEPS, WRITE_STEPS, READ_DISTANCE, READ_FLOORS, READ_EXERCISE, READ_HEART,
            READ_IN_BACKGROUND, READ_HISTORY,
        )
    }
}
