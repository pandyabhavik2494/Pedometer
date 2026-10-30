package com.pandyabhavik.pedometer.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every count in the app lives in 5-minute slots, keyed by the slot's start in epoch millis.
 * Slots are where sources are compared, so the same walk is never counted twice.
 */
object Slots {
    const val MINUTES = 5
    const val MILLIS = MINUTES * 60_000L

    fun startOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, MILLIS) * MILLIS

    fun dayOf(slotStart: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(slotStart).atZone(zone).toLocalDate()

    fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Slots covering [day], in order. Handles 23- and 25-hour days around clock changes. */
    fun slotsOfDay(day: LocalDate, zone: ZoneId): LongRange {
        val start = dayStart(day, zone)
        val end = dayStart(day.plusDays(1), zone)
        return start until end step MILLIS
    }

    /**
     * Spreads [count] over the slots between [start] and [end] in proportion to how much of each
     * slot the interval covers. Whole numbers that always add up to [count] (largest remainder).
     */
    fun distribute(start: Long, end: Long, count: Long): Map<Long, Long> {
        if (count <= 0) return emptyMap()
        if (end <= start) return mapOf(startOf(start) to count)
        val shares = LinkedHashMap<Long, Double>()
        var slot = startOf(start)
        val length = (end - start).toDouble()
        while (slot < end) {
            val overlap = minOf(end, slot + MILLIS) - maxOf(start, slot)
            if (overlap > 0) shares[slot] = count * overlap / length
            slot += MILLIS
        }
        val whole = shares.mapValues { kotlin.math.floor(it.value).toLong() }.toMutableMap()
        var left = count - whole.values.sum()
        shares.entries.sortedByDescending { it.value - kotlin.math.floor(it.value) }.forEach {
            if (left > 0) {
                whole[it.key] = whole.getValue(it.key) + 1
                left--
            }
        }
        return whole.filterValues { it > 0 }
    }

    /** Like [distribute] for a fractional amount such as metres. */
    fun distributeAmount(start: Long, end: Long, amount: Double): Map<Long, Double> {
        if (amount <= 0) return emptyMap()
        if (end <= start) return mapOf(startOf(start) to amount)
        val out = LinkedHashMap<Long, Double>()
        var slot = startOf(start)
        val length = (end - start).toDouble()
        while (slot < end) {
            val overlap = minOf(end, slot + MILLIS) - maxOf(start, slot)
            if (overlap > 0) out[slot] = amount * overlap / length
            slot += MILLIS
        }
        return out
    }

    /** Every slot an interval touches (for heart-rate "worn" marks and workouts). */
    fun touched(start: Long, end: Long): List<Long> {
        val out = ArrayList<Long>()
        var slot = startOf(start)
        val last = if (end > start) end - 1 else start
        while (slot <= last) {
            out += slot
            slot += MILLIS
        }
        return out
    }
}
