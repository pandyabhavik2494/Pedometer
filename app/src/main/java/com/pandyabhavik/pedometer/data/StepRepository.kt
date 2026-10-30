package com.pandyabhavik.pedometer.data

import com.pandyabhavik.pedometer.core.Badge
import com.pandyabhavik.pedometer.core.BadgeRules
import com.pandyabhavik.pedometer.core.DayTotal
import com.pandyabhavik.pedometer.core.Estimates
import com.pandyabhavik.pedometer.core.SlotInput
import com.pandyabhavik.pedometer.core.SlotResult
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.core.StepMerge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns what the phone and Health Connect recorded into merged days. Every screen, widget and
 * reminder reads the result from Room.
 */
@Singleton
class StepRepository @Inject constructor(
    private val dao: StepDao,
    private val settings: SettingsStore,
) {
    private val recomputeLock = Mutex()

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone())

    /** Today's date, emitting again just after each midnight. */
    fun todayFlow(): Flow<LocalDate> = flow {
        while (true) {
            val day = today()
            emit(day)
            val next = Slots.dayStart(day.plusDays(1), zone())
            delay((next - System.currentTimeMillis()).coerceAtLeast(1_000) + 1_000)
        }
    }.distinctUntilChanged()

    /** Source roles by package name; sources never seen default to [SourceRole.OTHER]. */
    suspend fun roles(): Map<String, SourceRole> =
        dao.sources().associate { it.origin to (runCatching { SourceRole.valueOf(it.role) }.getOrNull() ?: SourceRole.OTHER) }

    /** Slot inputs for [from, to): what the phone and every source recorded. */
    suspend fun slotInputs(from: Long, to: Long): Map<Long, SlotInput> {
        val phone = dao.phoneSlots(from, to).associate { it.slotStart to it.steps }
        val sources = dao.sourceSlots(from, to).groupBy { it.slotStart }
        val heart = dao.heartSlots(from, to).groupBy { it.slotStart }
        val keys = phone.keys + sources.keys + heart.keys
        return keys.associateWith { slot ->
            SlotInput(
                phoneSteps = phone[slot] ?: 0,
                sourceSteps = sources[slot].orEmpty().associate { it.origin to it.steps },
                heartRateFrom = heart[slot].orEmpty().map { it.origin }.toSet(),
            )
        }
    }

    suspend fun recompute(days: Collection<LocalDate>) {
        if (days.isEmpty()) return
        recomputeLock.withLock {
            val s = settings.current()
            val roles = roles()
            val zone = zone()
            days.toSortedSet().forEach { day -> recomputeDay(day, s, roles, zone) }
        }
    }

    private suspend fun recomputeDay(day: LocalDate, s: AppSettings, roles: Map<String, SourceRole>, zone: ZoneId) {
        val from = Slots.dayStart(day, zone)
        val to = Slots.dayStart(day.plusDays(1), zone)
        val inputs = slotInputs(from, to)
        val merged = StepMerge.merge(inputs, roles, s.preference)
        val sourceDistance = dao.sourceSlots(from, to).associate { (it.origin to it.slotStart) to it.distanceMeters }
        fun role(origin: String) = roles[origin] ?: SourceRole.OTHER

        var distance = 0.0
        val activeBySlot = HashMap<Long, Int>()
        merged.forEach { (slot, r) ->
            if (r.steps <= 0) return@forEach
            val fromSource = sourceDistance[r.source to slot]
            distance += if (r.source != SlotResult.PHONE && fromSource != null && fromSource > 0) fromSource
            else Estimates.distanceMeters(r.steps, s.stride)
            activeBySlot[slot] = Estimates.activeMinutes(r.steps)
        }
        // A workout counts as active time for as long as it ran, steps or not (cycling, rowing).
        dao.sessions(from, to).filter { role(it.origin) != SourceRole.IGNORED }.forEach { session ->
            Slots.touched(maxOf(session.start, from), minOf(session.end, to)).forEach { slot ->
                val overlap = (minOf(session.end, slot + Slots.MILLIS) - maxOf(session.start, slot)) / 60_000
                activeBySlot[slot] = maxOf(activeBySlot[slot] ?: 0, overlap.toInt().coerceIn(0, Slots.MINUTES))
            }
        }
        val floors = dao.floorSlots(from, to)
            .filter { role(it.origin) != SourceRole.IGNORED }
            .groupBy { it.origin }
            .maxOfOrNull { e -> e.value.sumOf { it.floors } } ?: 0.0
        val heart = dao.heartSlots(from, to).filter { role(it.origin) != SourceRole.IGNORED }

        var wearable = 0L
        var other = 0L
        inputs.values.forEach { input ->
            wearable += input.sourceSteps.filterKeys { role(it) == SourceRole.WEARABLE }.values.maxOrNull() ?: 0
            other += input.sourceSteps.filterKeys { role(it) == SourceRole.OTHER }.values.maxOrNull() ?: 0
        }
        val steps = merged.values.sumOf { it.steps }
        val summary = DaySummary(
            day = day.toString(),
            steps = steps,
            phoneSteps = inputs.values.sumOf { it.phoneSteps },
            wearableSteps = wearable,
            otherSteps = other,
            distanceMeters = distance,
            floors = floors,
            activeMinutes = activeBySlot.values.sum(),
            calories = Estimates.calories(distance, s.weight),
            // Past days keep the goal they had; today follows the current goal.
            goal = if (day == today()) s.goal else (dao.day(day.toString())?.goal ?: s.goal),
            minBpm = heart.minOfOrNull { it.minBpm },
            maxBpm = heart.maxOfOrNull { it.maxBpm },
        )
        val slots = merged.filter { it.value.steps > 0 }.map { MergedSlot(it.key, it.value.steps, it.value.source) }
        dao.replaceDay(from, to, slots, summary)
    }

    /** Adds badges earned so far; returns the ones that are new. */
    suspend fun updateBadges(): List<Badge> {
        val days = dao.allDays().map { DayTotal(LocalDate.parse(it.day), it.steps, it.goal) }
        val earned = BadgeRules.earned(days, today())
        val had = dao.badges().map { it.id }.toSet()
        val new = earned.filter { it.name !in had }
        if (new.isNotEmpty()) dao.insertBadges(new.map { EarnedBadge(it.name, System.currentTimeMillis()) })
        return new
    }

    /** Days touched by the slots in [slots]. */
    fun daysOf(slots: Collection<Long>): Set<LocalDate> = slots.map { Slots.dayOf(it, zone()) }.toSet()
}
