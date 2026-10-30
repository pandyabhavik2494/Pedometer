package com.pandyabhavik.pedometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class StepDao {
    // ---------------------------------------------------------------- phone
    @Query("SELECT * FROM phone_slots WHERE slotStart >= :from AND slotStart < :to ORDER BY slotStart")
    abstract suspend fun phoneSlots(from: Long, to: Long): List<PhoneSlot>

    @Query("SELECT * FROM phone_slots WHERE slotStart = :slot")
    abstract suspend fun phoneSlot(slot: Long): PhoneSlot?

    @Upsert
    abstract suspend fun upsertPhone(slots: List<PhoneSlot>)

    @Transaction
    open suspend fun addPhoneSteps(add: Map<Long, Long>) {
        val updated = add.map { (slot, steps) -> PhoneSlot(slot, (phoneSlot(slot)?.steps ?: 0) + steps) }
        upsertPhone(updated)
    }

    @Query("DELETE FROM phone_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun clearPhoneSlots(from: Long, to: Long)

    /** Puts Google Play's recorded steps for [from, to) in place of whatever the phone slots held there. */
    @Transaction
    open suspend fun replacePhoneSteps(from: Long, to: Long, slots: Map<Long, Long>) {
        clearPhoneSlots(from, to)
        upsertPhone(slots.filterValues { it > 0 }.map { (slot, steps) -> PhoneSlot(slot, steps) })
    }

    @Query("SELECT MIN(slotStart) FROM phone_slots")
    abstract suspend fun firstPhoneSlot(): Long?

    // ---------------------------------------------------------------- Health Connect cache
    @Query("SELECT * FROM source_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun sourceSlots(from: Long, to: Long): List<SourceSlot>

    @Query("SELECT * FROM heart_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun heartSlots(from: Long, to: Long): List<HeartSlot>

    @Query("SELECT * FROM floors WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun floorSlots(from: Long, to: Long): List<FloorSlot>

    @Query("SELECT * FROM sessions WHERE `end` > :from AND start < :to ORDER BY start")
    abstract suspend fun sessions(from: Long, to: Long): List<Session>

    @Query("SELECT * FROM sessions WHERE `end` > :from AND start < :to ORDER BY start")
    abstract fun sessionsFlow(from: Long, to: Long): Flow<List<Session>>

    @Query("DELETE FROM source_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun clearSourceSlots(from: Long, to: Long)

    @Query("DELETE FROM heart_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun clearHeartSlots(from: Long, to: Long)

    @Query("DELETE FROM floors WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun clearFloorSlots(from: Long, to: Long)

    @Query("DELETE FROM sessions WHERE start >= :from AND start < :to")
    abstract suspend fun clearSessions(from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSourceSlots(rows: List<SourceSlot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHeartSlots(rows: List<HeartSlot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFloorSlots(rows: List<FloorSlot>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSessions(rows: List<Session>)

    /** Swaps the cached Health Connect data for [from, to) in one go, so screens never see half a sync. */
    @Transaction
    open suspend fun replaceHealthData(
        from: Long,
        to: Long,
        steps: List<SourceSlot>,
        heart: List<HeartSlot>,
        floors: List<FloorSlot>,
        sessions: List<Session>,
    ) {
        clearSourceSlots(from, to)
        clearHeartSlots(from, to)
        clearFloorSlots(from, to)
        clearSessions(from, to)
        insertSourceSlots(steps)
        insertHeartSlots(heart)
        insertFloorSlots(floors)
        insertSessions(sessions)
    }

    @Query("SELECT MIN(slotStart) FROM source_slots")
    abstract suspend fun firstSourceSlot(): Long?

    // ---------------------------------------------------------------- merged
    @Query("DELETE FROM merged_slots WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun clearMerged(from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMerged(rows: List<MergedSlot>)

    @Upsert
    abstract suspend fun upsertDay(day: DaySummary)

    @Transaction
    open suspend fun replaceDay(from: Long, to: Long, slots: List<MergedSlot>, day: DaySummary) {
        clearMerged(from, to)
        insertMerged(slots)
        upsertDay(day)
    }

    @Query("SELECT * FROM merged_slots WHERE slotStart >= :from AND slotStart < :to ORDER BY slotStart")
    abstract fun mergedFlow(from: Long, to: Long): Flow<List<MergedSlot>>

    @Query("SELECT * FROM days WHERE day = :day")
    abstract fun dayFlow(day: String): Flow<DaySummary?>

    @Query("SELECT * FROM days WHERE day = :day")
    abstract suspend fun day(day: String): DaySummary?

    @Query("SELECT * FROM days WHERE day >= :from AND day <= :to ORDER BY day")
    abstract fun daysFlow(from: String, to: String): Flow<List<DaySummary>>

    @Query("SELECT * FROM days ORDER BY day")
    abstract fun allDaysFlow(): Flow<List<DaySummary>>

    @Query("SELECT * FROM days ORDER BY day")
    abstract suspend fun allDays(): List<DaySummary>

    @Query("SELECT * FROM days WHERE day >= :from AND day <= :to ORDER BY day")
    abstract suspend fun days(from: String, to: String): List<DaySummary>

    // ---------------------------------------------------------------- written to Health Connect
    @Query("SELECT * FROM written WHERE slotStart >= :from AND slotStart < :to")
    abstract suspend fun written(from: Long, to: Long): List<WrittenRecord>

    @Upsert
    abstract suspend fun upsertWritten(rows: List<WrittenRecord>)

    @Query("DELETE FROM written WHERE slotStart IN (:slots)")
    abstract suspend fun deleteWritten(slots: List<Long>)

    // ---------------------------------------------------------------- badges
    @Query("SELECT * FROM badges")
    abstract fun badgesFlow(): Flow<List<EarnedBadge>>

    @Query("SELECT * FROM badges")
    abstract suspend fun badges(): List<EarnedBadge>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBadges(rows: List<EarnedBadge>)

    // ---------------------------------------------------------------- sources
    @Query("SELECT * FROM sources ORDER BY firstSeen")
    abstract fun sourcesFlow(): Flow<List<KnownSource>>

    @Query("SELECT * FROM sources")
    abstract suspend fun sources(): List<KnownSource>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addSources(rows: List<KnownSource>)

    @Query("UPDATE sources SET role = :role WHERE origin = :origin")
    abstract suspend fun setRole(origin: String, role: String)
}
