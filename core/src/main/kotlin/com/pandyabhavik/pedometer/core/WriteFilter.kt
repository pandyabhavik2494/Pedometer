package com.pandyabhavik.pedometer.core

/** Where a step count came from. Only [PHONE_SENSOR] counts are ever written to Health Connect. */
enum class StepOrigin { PHONE_SENSOR, HEALTH_CONNECT }

data class StepBucket(val slotStart: Long, val steps: Long, val origin: StepOrigin)

/** A phone-steps record this app already wrote to Health Connect. */
data class WrittenSlot(val slotStart: Long, val steps: Long)

data class WritePlan(
    /** Slots to write (or rewrite with a new count). */
    val write: List<StepBucket>,
    /** Slots whose record this app wrote earlier and must now delete, because the wearable covered them. */
    val delete: List<Long>,
)

/**
 * Decides which phone steps go to Health Connect. Rules:
 * 1. Only steps counted by this phone's sensor are written. Anything read from Health Connect
 *    (Fitbit, Samsung Health, anyone) is never written back.
 * 2. A slot is written only when no wearable has steps or heart rate in it (the band wasn't worn).
 * 3. A slot is written only after it has settled, so a late wearable sync gets the first say.
 * 4. If the wearable later reports a slot this app already wrote, that record is deleted.
 */
object HealthConnectWriteFilter {
    fun plan(
        buckets: List<StepBucket>,
        wearableCovered: Set<Long>,
        alreadyWritten: List<WrittenSlot>,
        nowMillis: Long,
        settleMillis: Long,
    ): WritePlan {
        val written = alreadyWritten.associate { it.slotStart to it.steps }
        val settledBefore = nowMillis - settleMillis
        val write = buckets.asSequence()
            .filter { it.origin == StepOrigin.PHONE_SENSOR }
            .filter { it.steps > 0 }
            .filter { it.slotStart + Slots.MILLIS <= settledBefore }
            .filter { it.slotStart !in wearableCovered }
            .filter { written[it.slotStart] != it.steps }
            .sortedBy { it.slotStart }
            .toList()
        val delete = alreadyWritten.map { it.slotStart }.filter { it in wearableCovered }.sorted()
        return WritePlan(write, delete)
    }

    /** Slots in which any wearable source has steps or heart rate. */
    fun wearableCovered(slots: Map<Long, SlotInput>, roles: Map<String, SourceRole>): Set<Long> =
        slots.filter { (_, s) ->
            s.sourceSteps.any { (k, v) -> v > 0 && roles[k] == SourceRole.WEARABLE } ||
                s.heartRateFrom.any { roles[it] == SourceRole.WEARABLE }
        }.keys
}
