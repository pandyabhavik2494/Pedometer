package com.pandyabhavik.pedometer.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.pandyabhavik.pedometer.core.Csv
import com.pandyabhavik.pedometer.core.DayTotal
import com.pandyabhavik.pedometer.core.ExportRow
import com.pandyabhavik.pedometer.core.GoalRules
import com.pandyabhavik.pedometer.core.SlotResult
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.core.Streaks
import com.pandyabhavik.pedometer.data.AppSettings
import com.pandyabhavik.pedometer.data.DaySummary
import com.pandyabhavik.pedometer.data.EarnedBadge
import com.pandyabhavik.pedometer.data.KnownSource
import com.pandyabhavik.pedometer.data.MergedSlot
import com.pandyabhavik.pedometer.data.Session
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.data.StepDao
import com.pandyabhavik.pedometer.data.StepRepository
import com.pandyabhavik.pedometer.data.SyncState
import com.pandyabhavik.pedometer.health.HealthConnectGateway
import com.pandyabhavik.pedometer.health.HealthConnectStatus
import com.pandyabhavik.pedometer.health.SyncEngine
import com.pandyabhavik.pedometer.sensor.StepService
import com.pandyabhavik.pedometer.widget.Widgets
import com.pandyabhavik.pedometer.work.Schedules
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Friendly names for Health Connect sources. */
object SourceNames {
    fun label(context: Context, origin: String): String = when {
        origin == SlotResult.PHONE -> "This phone"
        origin.contains("fitbit", ignoreCase = true) -> "Fitbit"
        origin == "com.sec.android.app.shealth" -> "Samsung Health"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(origin, PackageManager.GET_META_DATA)).toString()
        }.getOrElse { origin.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
    }
}

data class HealthState(
    val status: HealthConnectStatus = HealthConnectStatus.AVAILABLE,
    val granted: Set<String> = emptySet(),
) {
    val canRead get() = HealthConnectGateway.READ_STEPS in granted
    val missing get() = HealthConnectGateway.ALL - granted
}

/** App-wide state: settings, sync, Health Connect, and the actions on them. */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val dao: StepDao,
    private val repository: StepRepository,
    private val engine: SyncEngine,
    val gateway: HealthConnectGateway,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sync: StateFlow<SyncState> = store.sync.stateIn(viewModelScope, SharingStarted.Eagerly, SyncState())
    val syncing: StateFlow<Boolean> = engine.running
    val sources: StateFlow<List<KnownSource>> = dao.sourcesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health

    fun refreshHealth() {
        viewModelScope.launch {
            val status = gateway.status()
            val granted = runCatching { gateway.granted() }.getOrDefault(emptySet())
            _health.value = HealthState(status, granted)
        }
    }

    fun syncNow() {
        viewModelScope.launch { engine.sync() }
    }

    /** After the screen opens: make sure counting runs and today is fresh. */
    fun onAppVisible() {
        StepService.start(context)
        refreshHealth()
        viewModelScope.launch { repository.recompute(setOf(repository.today())) }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val before = store.current()
            store.update(change)
            val after = store.current()
            if (before.syncMinutes != after.syncMinutes) Schedules.scheduleSync(context, after.syncMinutes, replace = true)
            if (before.reminderMinuteOfDay != after.reminderMinuteOfDay || before.reminderOn != after.reminderOn) {
                Schedules.scheduleReminder(context, after.reminderMinuteOfDay, ExistingWorkPolicy.REPLACE)
            }
            // Anything that changes the merged numbers: merge today again (history follows on the next sync).
            if (before.preference != after.preference || before.stride != after.stride || before.weight != after.weight ||
                before.goal != after.goal
            ) {
                repository.recompute(setOf(repository.today()))
                Widgets.refresh(context, force = true)
            }
            if (before.theme != after.theme) Widgets.refresh(context, force = true)
        }
    }

    fun setRole(origin: String, role: SourceRole) {
        viewModelScope.launch {
            dao.setRole(origin, role.name)
            val all = dao.allDays().map { LocalDate.parse(it.day) }
            repository.recompute(all)
            Widgets.refresh(context, force = true)
        }
    }

    /** Re-merges every stored day, for when the source preference changes. */
    fun recomputeAll() {
        viewModelScope.launch {
            repository.recompute(dao.allDays().map { LocalDate.parse(it.day) })
            Widgets.refresh(context, force = true)
        }
    }

    fun finishOnboarding() {
        viewModelScope.launch {
            store.update { it.copy(onboarded = true) }
            val s = store.current()
            Schedules.scheduleSync(context, s.syncMinutes)
            Schedules.scheduleReminder(context, s.reminderMinuteOfDay, ExistingWorkPolicy.REPLACE)
            StepService.start(context)
            engine.sync()
        }
    }

    fun export(uri: Uri, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val days = dao.allDays()
                    val rows = days.map {
                        ExportRow(
                            LocalDate.parse(it.day), it.steps, it.phoneSteps, it.wearableSteps, it.distanceMeters,
                            it.floors, it.activeMinutes, it.calories, it.goal,
                        )
                    }
                    context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { Csv.write(rows, it) }
                }.isSuccess
            }
            done(ok)
        }
    }
}

data class DayDetail(
    val day: LocalDate,
    val summary: DaySummary?,
    val hourly: List<Long>,
    /** Steps counted per source after dedupe. */
    val counted: Map<String, Long>,
    val sessions: List<Session>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DaysViewModel @Inject constructor(
    private val dao: StepDao,
    private val repository: StepRepository,
) : ViewModel() {
    val today: StateFlow<LocalDate> = repository.todayFlow().stateIn(viewModelScope, SharingStarted.Eagerly, repository.today())

    val days: StateFlow<Map<LocalDate, DaySummary>> = dao.allDaysFlow()
        .map { list -> list.associateBy { LocalDate.parse(it.day) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val todayDetail: StateFlow<DayDetail?> = today.flatMapLatest { detail(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val streaks: StateFlow<Streaks> = combine(days, today) { d, t ->
        GoalRules.streaks(d.values.map { DayTotal(LocalDate.parse(it.day), it.steps, it.goal) }, t)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Streaks(0, 0))

    val badges: StateFlow<List<EarnedBadge>> = dao.badgesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun detail(day: LocalDate): Flow<DayDetail> {
        val zone: ZoneId = repository.zone()
        val from = Slots.dayStart(day, zone)
        val to = Slots.dayStart(day.plusDays(1), zone)
        return combine(dao.dayFlow(day.toString()), dao.mergedFlow(from, to), dao.sessionsFlow(from, to)) { s, merged, sessions ->
            DayDetail(day, s, hourly(merged, zone), counted(merged), sessions)
        }
    }

    private fun hourly(merged: List<MergedSlot>, zone: ZoneId): List<Long> {
        val out = LongArray(24)
        merged.forEach {
            val h = java.time.Instant.ofEpochMilli(it.slotStart).atZone(zone).hour
            out[h] += it.steps
        }
        return out.toList()
    }

    private fun counted(merged: List<MergedSlot>): Map<String, Long> =
        merged.groupBy { it.source }.mapValues { e -> e.value.sumOf { it.steps } }
}
