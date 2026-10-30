package com.pandyabhavik.pedometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Steps this phone's sensor counted in one 5-minute slot. */
@Entity(tableName = "phone_slots")
data class PhoneSlot(@PrimaryKey val slotStart: Long, val steps: Long)

/** Steps and distance one Health Connect source recorded in one slot. */
@Entity(tableName = "source_slots", primaryKeys = ["origin", "slotStart"])
data class SourceSlot(val origin: String, val slotStart: Long, val steps: Long, val distanceMeters: Double)

/** A slot in which a source recorded heart rate: its band was being worn. */
@Entity(tableName = "heart_slots", primaryKeys = ["origin", "slotStart"])
data class HeartSlot(val origin: String, val slotStart: Long, val minBpm: Long, val maxBpm: Long)

@Entity(tableName = "floors", primaryKeys = ["origin", "slotStart"])
data class FloorSlot(val origin: String, val slotStart: Long, val floors: Double)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val origin: String,
    val start: Long,
    val end: Long,
    val type: Int,
    val title: String?,
)

/** The counted (deduplicated) steps for one slot, and which source they came from. */
@Entity(tableName = "merged_slots")
data class MergedSlot(@PrimaryKey val slotStart: Long, val steps: Long, val source: String)

/** One day, merged. [day] is the ISO date (yyyy-MM-dd) in the phone's time zone. */
@Entity(tableName = "days")
data class DaySummary(
    @PrimaryKey val day: String,
    val steps: Long,
    val phoneSteps: Long,
    val wearableSteps: Long,
    val otherSteps: Long,
    val distanceMeters: Double,
    val floors: Double,
    val activeMinutes: Int,
    val calories: Long,
    val goal: Long,
    val minBpm: Long?,
    val maxBpm: Long?,
)

/** A steps record this app wrote to Health Connect, one per slot. */
@Entity(tableName = "written")
data class WrittenRecord(@PrimaryKey val slotStart: Long, val steps: Long, val writtenAt: Long)

@Entity(tableName = "badges")
data class EarnedBadge(@PrimaryKey val id: String, val earnedAt: Long)

/** A Health Connect data source seen in the data, and how it's treated. */
@Entity(tableName = "sources")
data class KnownSource(@PrimaryKey val origin: String, val role: String, val firstSeen: Long)
