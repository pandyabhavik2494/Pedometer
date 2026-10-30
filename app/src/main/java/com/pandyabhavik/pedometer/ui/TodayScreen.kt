package com.pandyabhavik.pedometer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.core.SlotResult
import com.pandyabhavik.pedometer.core.Units
import com.pandyabhavik.pedometer.data.AppSettings
import com.pandyabhavik.pedometer.data.SyncState
import com.pandyabhavik.pedometer.health.HealthConnectStatus
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TodayScreen(main: MainViewModel, days: DaysViewModel, wide: Boolean, onFixHealth: () -> Unit) {
    val detail by days.todayDetail.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    val streaks by days.streaks.collectAsStateWithLifecycle()
    val sync by main.sync.collectAsStateWithLifecycle()
    val syncing by main.syncing.collectAsStateWithLifecycle()
    val health by main.health.collectAsStateWithLifecycle()
    val s = settings ?: return
    val today = detail?.day ?: LocalDate.now()
    val summary = detail?.summary
    val steps = summary?.steps ?: 0
    val goal = s.goal
    val hourly = detail?.hourly ?: List(24) { 0L }
    val hourNow = LocalTime.now().hour

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.headlineMedium)
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (streaks.current > 0) {
                Chip("${streaks.current}-day streak", Icons.Outlined.LocalFireDepartment, accent = true)
            }
        }
    }
    val ring: @Composable (Modifier) -> Unit = { m ->
        DayRing(
            steps = steps, goal = goal, hourly = hourly, currentHour = hourNow,
            modifier = m.testTag("today-ring"),
        )
    }
    val tiles: @Composable () -> Unit = { Tiles(s, summary?.distanceMeters ?: 0.0, summary?.floors ?: 0.0, summary?.activeMinutes ?: 0, summary?.calories ?: 0) }
    val hours: @Composable () -> Unit = {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("By hour")
            Spacer(Modifier.height(12.dp))
            BarChart(
                values = hourly,
                labels = (0 until 24).map { if (it % 6 == 0) "%02d".format(it) else "" },
                goal = null,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                highlightGoal = false,
            )
        }
    }
    val syncCard: @Composable () -> Unit = {
        SyncPanel(sync, syncing, health.status, health.canRead, detail?.counted.orEmpty(), summary?.phoneSteps ?: 0,
            summary?.wearableSteps ?: 0, onSync = main::syncNow, onFix = onFixHealth)
    }
    val extras: @Composable () -> Unit = {
        val sessions = detail?.sessions.orEmpty()
        if (summary?.minBpm != null || sessions.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                if (summary?.minBpm != null && summary.maxBpm != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Favorite, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Heart rate ${summary.minBpm}–${summary.maxBpm} bpm", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (sessions.isNotEmpty()) {
                    if (summary?.minBpm != null) Spacer(Modifier.height(14.dp))
                    SectionLabel("Workouts")
                    sessions.forEach { w ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.DirectionsRun, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                            val zone = java.time.ZoneId.systemDefault()
                            val start = java.time.Instant.ofEpochMilli(w.start).atZone(zone)
                            val minutes = ((w.end - w.start) / 60_000).toInt()
                            Text(
                                "${w.title ?: "Workout"} · ${start.format(fmt)} · ${Format.minutes(minutes)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
    val warnings: @Composable () -> Unit = { StatusBanners(main) }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                header()
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ring(Modifier.widthIn(max = 340.dp).fillMaxWidth(0.9f).aspectRatio(1f))
                }
                tiles()
            }
            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                warnings()
                hours()
                syncCard()
                extras()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            header()
            warnings()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ring(Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f).aspectRatio(1f))
            }
            tiles()
            hours()
            syncCard()
            extras()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Tiles(s: AppSettings, distance: Double, floors: Double, active: Int, calories: Long) {
    val (dValue, dUnit) = Format.distanceValue(distance, s.units)
    val list = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, Pair<String, String?>>> {
        add(Triple(Icons.Outlined.Route, "Distance", dValue to dUnit))
        if (floors > 0) add(Triple(Icons.Outlined.Stairs, "Floors", "%.0f".format(floors) to null))
        add(Triple(Icons.Outlined.Timer, "Active", Format.minutes(active) to null))
        add(Triple(Icons.Outlined.Bolt, "Calories", Format.steps(calories) to "kcal"))
    }
    val rows = if (list.size == 4) list.chunked(2) else listOf(list)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (icon, label, v) ->
                    StatTile(icon, label, v.first, v.second, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SyncPanel(
    sync: SyncState,
    syncing: Boolean,
    status: HealthConnectStatus,
    canRead: Boolean,
    counted: Map<String, Long>,
    phoneRaw: Long,
    wearableRaw: Long,
    onSync: () -> Unit,
    onFix: () -> Unit,
) {
    val context = LocalContext.current
    // Re-render "5 min ago" every 30 seconds.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    Panel(Modifier.fillMaxWidth().testTag("sync-panel")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Sources")
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        syncing -> "Syncing…"
                        sync.lastSync != null -> "Last synced ${ago(sync.lastSync, now.longValue)}"
                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onSync, enabled = !syncing, modifier = Modifier.testTag("sync-now")) {
                if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
        }
        Spacer(Modifier.height(14.dp))
        val countedPhone = counted[SlotResult.PHONE] ?: 0
        val countedWearable = counted.filterKeys { it != SlotResult.PHONE }.values.sum()
        SourceRow("This phone", phoneRaw, countedPhone)
        Spacer(Modifier.height(8.dp))
        val wearName = counted.keys.firstOrNull { it != SlotResult.PHONE }?.let { SourceNames.label(context, it) } ?: "Fitbit"
        SourceRow(wearName, wearableRaw, countedWearable)
        Spacer(Modifier.height(10.dp))
        Text(
            "Counted is what adds up to today's total. Where both recorded the same minutes, only one counts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val problem = when {
            status == HealthConnectStatus.NOT_INSTALLED -> "Health Connect isn't installed, so only this phone's steps count."
            status == HealthConnectStatus.NEEDS_UPDATE -> "Health Connect needs an update."
            !canRead -> "Health Connect access is off, so Fitbit steps can't be read."
            sync.lastError != null -> sync.lastError
            else -> null
        }
        if (problem != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                TextButton(onClick = onFix) { Text("Fix") }
            }
        }
    }
}

@Composable
private fun SourceRow(name: String, recorded: Long, counted: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("${Format.steps(counted)} counted", style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
            Text(
                "${Format.steps(recorded)} recorded",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun ago(then: Long, now: Long): String {
    val minutes = ((now - then) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

fun unitsLabel(u: Units) = if (u == Units.METRIC) "Metric (km)" else "Imperial (mi)"
