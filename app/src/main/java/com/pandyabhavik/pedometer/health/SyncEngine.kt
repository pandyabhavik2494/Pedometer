package com.pandyabhavik.pedometer.health

import android.content.Context
import android.util.Log
import com.pandyabhavik.pedometer.core.HealthConnectWriteFilter
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.core.StepBucket
import com.pandyabhavik.pedometer.core.StepOrigin
import com.pandyabhavik.pedometer.core.WrittenSlot
import com.pandyabhavik.pedometer.data.KnownSource
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.data.StepDao
import com.pandyabhavik.pedometer.data.StepRepository
import com.pandyabhavik.pedometer.data.WrittenRecord
import com.pandyabhavik.pedometer.widget.Widgets
import com.pandyabhavik.pedometer.work.BadgeNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One sync: read Health Connect, cache it, re-merge the affected days, then write phone-only
 * steps back. Used by the background job and by "Sync now".
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: HealthConnectGateway,
    private val dao: StepDao,
    private val repository: StepRepository,
    private val settings: SettingsStore,
) {
    private val lock = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    suspend fun sync(): Result<Unit> = lock.withLock {
        _running.value = true
        try {
            val result = runCatching { syncOnce() }
            result.exceptionOrNull()?.let { Log.w(TAG, "Sync failed", it) }
            val error = result.exceptionOrNull()?.let { describe(it) }
            settings.recordSync(if (result.isSuccess) System.currentTimeMillis() else null, error)
            // The phone's own count is always merged, even when Health Connect can't be read.
            repository.recompute(setOf(repository.today()))
            val badges = repository.updateBadges()
            BadgeNotifier.notify(context, badges)
            Widgets.refresh(context)
            result
        } finally {
            _running.value = false
        }
    }

    private suspend fun syncOnce() {
        when (gateway.status()) {
            HealthConnectStatus.NOT_INSTALLED -> throw SyncProblem("Health Connect isn't installed")
            HealthConnectStatus.NEEDS_UPDATE -> throw SyncProblem("Health Connect needs an update")
            HealthConnectStatus.AVAILABLE -> Unit
        }
        val granted = gateway.granted()
        if (HealthConnectGateway.READ_STEPS !in granted) throw SyncProblem("Allow Health Connect access to read steps")

        val zone = repository.zone()
        val now = System.currentTimeMillis()
        val today = repository.today()
        val last = settings.sync.first().lastSync
        // First sync imports everything Health Connect has; later syncs look back two days, because
        // wearables can sync hours late.
        val firstDay: LocalDate = if (last == null) {
            val oldest = gateway.oldestSteps(Instant.ofEpochMilli(now).minusSeconds(10L * 365 * 86_400))
            oldest?.atZone(zone)?.toLocalDate()?.let { if (it.isAfter(today)) today else it } ?: today.minusDays(30)
        } else {
            Instant.ofEpochMilli(last).atZone(zone).toLocalDate().minusDays(2).let { if (it.isAfter(today.minusDays(2))) today.minusDays(2) else it }
        }

        // Read in 30-day pieces so a long history never has to fit in memory at once.
        var chunkStart = firstDay
        while (!chunkStart.isAfter(today)) {
            val chunkEnd = chunkStart.plusDays(30).let { if (it.isAfter(today.plusDays(1))) today.plusDays(1) else it }
            val from = chunkStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = chunkEnd.atStartOfDay(zone).toInstant().toEpochMilli()
            val data = gateway.read(from, to, granted)
            registerSources(data.origins)
            dao.replaceHealthData(from, to, data.steps, data.heart, data.floors, data.sessions)
            val days = generateSequence(chunkStart) { it.plusDays(1) }.takeWhile { it.isBefore(chunkEnd) }.toList()
            repository.recompute(days)
            chunkStart = chunkEnd
        }

        writeBack(granted, now)
    }

    /** Writes phone steps for the last week where the wearable wasn't worn; retracts what it later covered. */
    private suspend fun writeBack(granted: Set<String>, now: Long) {
        val s = settings.current()
        if (!s.writeToHealthConnect || HealthConnectGateway.WRITE_STEPS !in granted) return
        val zone = repository.zone()
        val from = repository.today().minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val roles = repository.roles()
        val inputs = repository.slotInputs(from, now)
        val buckets = dao.phoneSlots(from, now).map { StepBucket(it.slotStart, it.steps, StepOrigin.PHONE_SENSOR) }
        val written = dao.written(from, now)
        val plan = HealthConnectWriteFilter.plan(
            buckets = buckets,
            wearableCovered = HealthConnectWriteFilter.wearableCovered(inputs, roles),
            alreadyWritten = written.map { WrittenSlot(it.slotStart, it.steps) },
            nowMillis = now,
            settleMillis = s.settleMinutes * 60_000L,
        )
        if (plan.delete.isNotEmpty()) {
            gateway.deletePhoneSteps(plan.delete)
            dao.deleteWritten(plan.delete)
        }
        if (plan.write.isNotEmpty()) {
            gateway.writePhoneSteps(plan.write.map { it.slotStart to it.steps }, version = now)
            dao.upsertWritten(plan.write.map { WrittenRecord(it.slotStart, it.steps, now) })
        }
    }

    private suspend fun registerSources(origins: Set<String>) {
        if (origins.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.addSources(origins.map { KnownSource(it, defaultRole(it).name, now) })
    }

    private fun describe(e: Throwable): String = when (e) {
        is SyncProblem -> e.message.orEmpty()
        is SecurityException -> "Health Connect blocked a background read. Allow \"Access data in the background\" in Settings."
        else -> "Couldn't sync: ${e.message ?: e.javaClass.simpleName}"
    }

    class SyncProblem(message: String) : Exception(message)

    companion object {
        private const val TAG = "SyncEngine"
        const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

        /** Samsung Health counts the same phone steps this app does, so it is ignored unless you change it. */
        fun defaultRole(origin: String): SourceRole = when (origin) {
            SAMSUNG_HEALTH -> SourceRole.IGNORED
            else -> SourceRole.WEARABLE
        }
    }
}
