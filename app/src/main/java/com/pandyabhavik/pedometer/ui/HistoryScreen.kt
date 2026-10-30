package com.pandyabhavik.pedometer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.core.SlotResult
import com.pandyabhavik.pedometer.data.DaySummary
import com.pandyabhavik.pedometer.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Period(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

/** One page of history: a period, its bars, and the days in it. */
private class PageData(
    val title: String,
    val values: List<Long>,
    val labels: List<String>,
    val labelEvery: Int,
    val goal: Long?,
    val days: List<LocalDate>,
    val average: Long,
    val total: Long,
    val averageLabel: String,
)

private fun startOf(period: Period, d: LocalDate): LocalDate = when (period) {
    Period.DAY -> d
    Period.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Period.MONTH -> d.withDayOfMonth(1)
    Period.YEAR -> d.withDayOfYear(1)
}

private fun shift(period: Period, d: LocalDate, back: Int): LocalDate = when (period) {
    Period.DAY -> d.minusDays(back.toLong())
    Period.WEEK -> d.minusWeeks(back.toLong())
    Period.MONTH -> d.minusMonths(back.toLong())
    Period.YEAR -> d.minusYears(back.toLong())
}

private fun pagesBack(period: Period, earliest: LocalDate, today: LocalDate): Int {
    val a = startOf(period, earliest)
    val b = startOf(period, today)
    return when (period) {
        Period.DAY -> ChronoUnit.DAYS.between(a, b)
        Period.WEEK -> ChronoUnit.WEEKS.between(a, b)
        Period.MONTH -> ChronoUnit.MONTHS.between(a, b)
        Period.YEAR -> ChronoUnit.YEARS.between(a, b)
    }.toInt().coerceAtLeast(0)
}

private fun page(period: Period, start: LocalDate, today: LocalDate, days: Map<LocalDate, DaySummary>, goal: Long): PageData {
    val short = DateTimeFormatter.ofPattern("d MMM")
    fun steps(d: LocalDate) = days[d]?.steps ?: 0
    return when (period) {
        Period.WEEK -> {
            val list = (0L..6L).map { start.plusDays(it) }
            val counted = list.filter { !it.isAfter(today) }
            val total = counted.sumOf(::steps)
            PageData(
                title = if (start == startOf(Period.WEEK, today)) "This week" else "${start.format(short)} – ${start.plusDays(6).format(short)}",
                values = list.map(::steps),
                labels = list.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2) },
                labelEvery = 1, goal = goal, days = list.filter { !it.isAfter(today) }.reversed(),
                average = if (counted.isEmpty()) 0 else total / counted.size, total = total, averageLabel = "a day",
            )
        }
        Period.MONTH -> {
            val ym = YearMonth.from(start)
            val list = (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            val counted = list.filter { !it.isAfter(today) }
            val total = counted.sumOf(::steps)
            PageData(
                title = start.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                values = list.map(::steps), labels = list.map { it.dayOfMonth.toString() }, labelEvery = 7,
                goal = goal, days = counted.reversed(),
                average = if (counted.isEmpty()) 0 else total / counted.size, total = total, averageLabel = "a day",
            )
        }
        Period.YEAR -> {
            val months = (0L..11L).map { YearMonth.from(start).plusMonths(it) }
            val perMonth = months.map { ym ->
                val ds = (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { !it.isAfter(today) }
                if (ds.isEmpty()) 0L else ds.sumOf(::steps) / ds.size
            }
            val ds = (0 until start.lengthOfYear()).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(today) }
            val total = ds.sumOf(::steps)
            PageData(
                title = start.year.toString(),
                values = perMonth,
                labels = months.map { it.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
                labelEvery = 1, goal = goal, days = emptyList(),
                average = if (ds.isEmpty()) 0 else total / ds.size, total = total, averageLabel = "a day",
            )
        }
        Period.DAY -> PageData(
            title = when (start) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> start.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
            },
            values = emptyList(), labels = emptyList(), labelEvery = 1, goal = null, days = listOf(start),
            average = steps(start), total = steps(start), averageLabel = "",
        )
    }
}

@Composable
fun HistoryScreen(main: MainViewModel, daysVm: DaysViewModel, wide: Boolean) {
    val days by daysVm.days.collectAsStateWithLifecycle()
    val today by daysVm.today.collectAsStateWithLifecycle()
    val settings by main.settings.collectAsStateWithLifecycle()
    val goal = settings?.goal ?: 10_000
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf(0) }
    val earliest = days.keys.minOrNull() ?: today

    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 0.dp else 20.dp, vertical = 16.dp)) {
            Text("History", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            PillToggle(Period.entries.map { it to it.label }, period, { jumpTo = 0; period = it }, Modifier.fillMaxWidth().testTag("period-toggle"))
            Spacer(Modifier.height(16.dp))
            val count = pagesBack(period, earliest, today) + 1
            val pager = key(period) { rememberPagerState(initialPage = jumpTo.coerceIn(0, count - 1)) { count } }
            val scope = rememberCoroutineScope()
            // Page 0 is the current period; swiping right goes back in time.
            val pageStart = { i: Int -> startOf(period, shift(period, today, i)) }
            Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < count - 1) {
                        Icon(Icons.Outlined.ChevronLeft, "Earlier")
                    }
                    val p = page(period, pageStart(pager.currentPage), today, days, goal)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (period == Period.DAY) "${Format.steps(p.total)} steps"
                            else "${Format.steps(p.average)} ${p.averageLabel} · ${Format.steps(p.total)} total",
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                        Icon(Icons.Outlined.ChevronRight, "Later")
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalPager(
                    state = pager,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("history-pager"),
                    key = { "${period.name}-$it" },
                ) { i ->
                    val start = pageStart(i)
                    if (period == Period.DAY) {
                        val detail by remember(start) { daysVm.detail(start) }.collectAsState(initial = null)
                        BarChart(
                            values = detail?.hourly ?: List(24) { 0L },
                            labels = (0 until 24).map { if (it % 6 == 0) "%02d".format(it) else "" },
                            goal = null, highlightGoal = false,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        )
                    } else {
                        val p = page(period, start, today, days, goal)
                        BarChart(
                            values = p.values, labels = p.labels, goal = p.goal, labelEvery = p.labelEvery,
                            selected = selectedDay?.let { LocalDate.parse(it) }
                                ?.takeIf { period != Period.YEAR }
                                ?.let { ChronoUnit.DAYS.between(start, it).toInt() }
                                ?.takeIf { it in p.values.indices },
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            onSelect = { idx ->
                                when (period) {
                                    Period.YEAR -> {
                                        // A month bar opens that month.
                                        val month = YearMonth.from(start).plusMonths(idx.toLong()).atDay(1)
                                        if (!month.isAfter(today)) {
                                            jumpTo = pagesBack(Period.MONTH, month, today)
                                            period = Period.MONTH
                                        }
                                    }
                                    else -> {
                                        val d = start.plusDays(idx.toLong())
                                        if (!d.isAfter(today)) selectedDay = d.toString()
                                    }
                                }
                            },
                        )
                    }
                }
            }
            val p = page(period, pageStart(pager.currentPage), today, days, goal)
            if (period == Period.DAY) {
                Spacer(Modifier.height(16.dp))
                val d = p.days.first()
                val detail by remember(d) { daysVm.detail(d) }.collectAsState(initial = null)
                detail?.let { DayFacts(it, settings?.units) }
            } else if (p.days.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Days")
                Spacer(Modifier.height(6.dp))
                p.days.forEach { d ->
                    DayRow(d, days[d], goal, selected = d.toString() == selectedDay) { selectedDay = d.toString() }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val selected = selectedDay?.let { LocalDate.parse(it) }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            list(Modifier.weight(1f).fillMaxSize().padding(top = 8.dp))
            Box(Modifier.weight(1f).fillMaxSize().padding(top = 24.dp)) {
                if (selected != null) {
                    DayDetailPane(daysVm, selected, settings?.units, onClose = { selectedDay = null })
                } else {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Pick a day to see it hour by hour", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else if (selected != null) {
        BackHandler { selectedDay = null }
        DayDetailPane(daysVm, selected, settings?.units, onClose = { selectedDay = null })
    } else {
        list(Modifier.fillMaxSize())
    }
}

@Composable
private fun DayRow(day: LocalDate, s: DaySummary?, goal: Long, selected: Boolean, onClick: () -> Unit) {
    val steps = s?.steps ?: 0
    val dayGoal = s?.goal ?: goal
    val hit = dayGoal > 0 && steps >= dayGoal
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (hit) MaterialTheme.colorScheme.primary else LocalPalette.current.miss),
            )
            Spacer(Modifier.width(12.dp))
            Text(day.format(DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                Format.steps(steps),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = if (hit) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = LocalPalette.current.hairline)
    }
}

@Composable
private fun DayDetailPane(vm: DaysViewModel, day: LocalDate, units: com.pandyabhavik.pedometer.core.Units?, onClose: () -> Unit) {
    val detail by remember(day) { vm.detail(day) }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).testTag("day-detail")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Spacer(Modifier.width(4.dp))
            Text(day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        val d = detail
        if (d != null) {
            Text(Format.steps(d.summary?.steps ?: 0), style = MaterialTheme.typography.displayMedium)
            val goal = d.summary?.goal ?: 0
            Text(
                if (goal > 0) "of ${Format.steps(goal)} steps" else "steps",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("By hour")
                Spacer(Modifier.height(12.dp))
                BarChart(
                    values = d.hourly, labels = (0 until 24).map { if (it % 6 == 0) "%02d".format(it) else "" },
                    goal = null, highlightGoal = false, modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            DayFacts(d, units)
        }
    }
}

@Composable
private fun DayFacts(d: DayDetail, units: com.pandyabhavik.pedometer.core.Units?) {
    val context = LocalContext.current
    val s = d.summary
    Panel(Modifier.fillMaxWidth()) {
        val u = units ?: com.pandyabhavik.pedometer.core.Units.METRIC
        Fact("Distance", Format.distance(s?.distanceMeters ?: 0.0, u))
        if ((s?.floors ?: 0.0) > 0) Fact("Floors", "%.0f".format(s?.floors ?: 0.0))
        Fact("Active time", Format.minutes(s?.activeMinutes ?: 0))
        Fact("Calories (estimate)", "${Format.steps(s?.calories ?: 0)} kcal")
        if (s?.minBpm != null) Fact("Heart rate", "${s.minBpm}–${s.maxBpm} bpm")
        if (d.counted.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Counted from")
            d.counted.entries.sortedByDescending { it.value }.forEach { (origin, n) ->
                Fact(if (origin == SlotResult.PHONE) "This phone" else SourceNames.label(context, origin), Format.steps(n))
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
}
