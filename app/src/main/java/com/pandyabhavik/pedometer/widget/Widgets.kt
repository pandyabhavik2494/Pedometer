package com.pandyabhavik.pedometer.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pandyabhavik.pedometer.AppEntryPoint
import com.pandyabhavik.pedometer.MainActivity
import com.pandyabhavik.pedometer.core.DayTotal
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.core.GoalRules
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.Units
import com.pandyabhavik.pedometer.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** Colours for the widgets, following the app's theme setting. */
private data class WidgetColors(val bg: Color, val ink: Color, val quiet: Color, val accent: Color, val track: Color)

private fun widgetColors(context: Context, theme: ThemeMode): WidgetColors {
    // The application context's resources follow a light/dark switch; a stale context may not.
    val night = (context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = when (theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, ThemeMode.GRAYSCALE -> night
    }
    return when {
        theme == ThemeMode.GRAYSCALE && dark -> WidgetColors(Color.Black, Color.White, Color(0xFF9A9A9A), Color.White, Color(0xFF2A2A2A))
        theme == ThemeMode.GRAYSCALE -> WidgetColors(Color.White, Color.Black, Color(0xFF666666), Color.Black, Color(0xFFE2E2E2))
        dark -> WidgetColors(Color(0xFF141817), Color(0xFFEDEAE3), Color(0xFF9BA6A3), Color(0xFF5ED1C1), Color(0xFF26302E))
        else -> WidgetColors(Color(0xFFFBFAF7), Color(0xFF15201E), Color(0xFF5E6B68), Color(0xFF0E7C72), Color(0xFFE4E8E5))
    }
}

private data class WidgetData(
    val steps: Long,
    val goal: Long,
    val week: List<Pair<String, Long>>,
    val colors: WidgetColors,
    val distanceMeters: Double = 0.0,
    val calories: Long = 0,
    val activeMinutes: Int = 0,
    val units: Units = Units.METRIC,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Today's steps per hour of the day, 24 values. */
    val hourly: List<Long> = List(24) { 0L },
) {
    val progress: Float get() = if (goal > 0) steps.toFloat() / goal else 0f
}

private suspend fun load(context: Context): WidgetData {
    val app = AppEntryPoint.get(context)
    val s = app.settings().current()
    val today = app.repository().today()
    val zone = app.repository().zone()
    val from = today.minusDays(6)
    val days = app.dao().days(from.toString(), today.toString()).associateBy { it.day }
    val week = (0L..6L).map { from.plusDays(it) }.map { d ->
        d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()) to (days[d.toString()]?.steps ?: 0)
    }
    val summary = days[today.toString()]
    val steps = summary?.steps ?: 0
    val streaks = GoalRules.streaks(
        app.dao().allDays().map { DayTotal(LocalDate.parse(it.day), it.steps, it.goal) },
        today,
    )
    val hourly = LongArray(24)
    val dayFrom = Slots.dayStart(today, zone)
    val dayTo = Slots.dayStart(today.plusDays(1), zone)
    app.dao().mergedFlow(dayFrom, dayTo).first().forEach { slot ->
        val hour = Instant.ofEpochMilli(slot.slotStart).atZone(zone).hour
        hourly[hour] += slot.steps
    }
    return WidgetData(
        steps = steps,
        goal = s.goal,
        week = week,
        colors = widgetColors(context, s.theme),
        distanceMeters = summary?.distanceMeters ?: 0.0,
        calories = summary?.calories ?: 0,
        activeMinutes = summary?.activeMinutes ?: 0,
        units = s.units,
        streak = streaks.current,
        bestStreak = streaks.best,
        hourly = hourly.toList(),
    )
}

/**
 * Fresh data whenever the day totals, the settings or the date change. Glance keeps a widget's session
 * alive for a while after it draws, and an update() during that time only recomposes: data loaded once
 * in provideGlance would be drawn again unchanged, so a refresh inside that window showed stale steps.
 * [Widgets.redraws] covers what no flow here sees: the merged hours and a light/dark switch.
 */
private fun dataFlow(context: Context): Flow<WidgetData> {
    val app = AppEntryPoint.get(context)
    return combine(
        app.settings().settings, app.dao().allDaysFlow(), app.repository().todayFlow(), Widgets.redraws,
    ) { _, _, _, _ -> load(context) }
}

// Bitmaps travel inside the RemoteViews in a binder transaction (1 MB for everything in flight). At
// 360x360 plus 560x200 ARGB the week widget sent ~1 MB of pixels and could fail with
// TransactionTooLargeException; these sizes are still sharp at the sizes they're shown.
private fun ringBitmap(progress: Float, colors: WidgetColors, px: Int = 240): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val stroke = px * 0.09f
    val rect = RectF(stroke / 2 + 2, stroke / 2 + 2, px - stroke / 2 - 2, px - stroke / 2 - 2)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = colors.track.toArgb()
    c.drawArc(rect, 0f, 360f, false, paint)
    val sweep = 360f * progress.coerceIn(0f, 1f)
    if (sweep > 0f) {
        paint.color = colors.accent.toArgb()
        c.drawArc(rect, -90f, sweep.coerceAtLeast(1f), false, paint)
    }
    return bmp
}

/**
 * Bars for [values]. The gap between bars is [gapRatio] times a bar's width, so any number of bars fits
 * (0.5 gives the week widget its look). The week style draws a track pill behind each bar, colours days
 * that reached the goal in the accent and draws a goal line; the [hourly] style draws accent bars with
 * flat feet on a faint baseline, scaled to the busiest value.
 */
private fun barsBitmap(
    values: List<Long>,
    goal: Long,
    colors: WidgetColors,
    w: Int = 420,
    h: Int = 150,
    gapRatio: Float = 0.5f,
    hourly: Boolean = false,
): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val n = values.size.coerceAtLeast(1)
    val max = maxOf(goal, values.maxOrNull() ?: 0, 1).toFloat()
    val bar = w / (n + (n - 1) * gapRatio)
    val gap = bar * gapRatio
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (hourly) {
        paint.color = colors.ink.copy(alpha = 0.14f).toArgb()
        c.drawRect(0f, h - 2f, w.toFloat(), h.toFloat(), paint)
    }
    values.forEachIndexed { i, v ->
        val x = i * (bar + gap)
        if (hourly) {
            if (v > 0) {
                val r = minOf(bar / 2, 6f)
                val top = h - (h * (v / max)).coerceAtLeast(r * 2)
                paint.color = colors.accent.toArgb()
                // Extends below the bitmap so only the top corners are rounded.
                c.drawRoundRect(RectF(x, top, x + bar, h + r), r, r, paint)
            }
        } else {
            paint.color = colors.track.toArgb()
            c.drawRoundRect(RectF(x, 0f, x + bar, h.toFloat()), bar / 2, bar / 2, paint)
            if (v > 0) {
                val top = h - (h * (v / max)).coerceAtLeast(bar)
                paint.color = (if (goal > 0 && v >= goal) colors.accent else colors.quiet).toArgb()
                c.drawRoundRect(RectF(x, top, x + bar, h.toFloat()), bar / 2, bar / 2, paint)
            }
        }
    }
    if (!hourly && goal > 0) {
        val y = h - h * (goal / max)
        paint.color = colors.ink.copy(alpha = 0.35f).toArgb()
        paint.strokeWidth = 3f
        c.drawLine(0f, y, w.toFloat(), y, paint)
    }
    return bmp
}

/** A slim rounded bar, [w] x [h] px, filled to [progress]. */
private fun progressBitmap(progress: Float, colors: WidgetColors, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = h / 2f
    paint.color = colors.track.toArgb()
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
    val p = progress.coerceIn(0f, 1f)
    if (p > 0f) {
        paint.color = colors.accent.toArgb()
        c.drawRoundRect(RectF(0f, 0f, maxOf(w * p, h.toFloat()), h.toFloat()), r, r, paint)
    }
    return bmp
}

/** A small flame in the accent colour. */
private fun flameBitmap(colors: WidgetColors, px: Int = 48): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = px / 48f
    val path = Path().apply {
        moveTo(24f * s, 3f * s)
        cubicTo(31f * s, 12f * s, 40f * s, 20f * s, 40f * s, 31f * s)
        cubicTo(40f * s, 40f * s, 33f * s, 46f * s, 24f * s, 46f * s)
        cubicTo(15f * s, 46f * s, 8f * s, 40f * s, 8f * s, 31f * s)
        cubicTo(8f * s, 24f * s, 12f * s, 19f * s, 16f * s, 14f * s)
        cubicTo(17f * s, 19f * s, 19f * s, 22f * s, 22f * s, 23f * s)
        cubicTo(21f * s, 16f * s, 21f * s, 9f * s, 24f * s, 3f * s)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.accent.toArgb() }
    c.drawPath(path, paint)
    return bmp
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

private fun style(color: Color, size: Number, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start) =
    TextStyle(color = ColorProvider(color), fontSize = size.toFloat().sp, fontWeight = weight, textAlign = align)

/**
 * How much larger than its design size ([w] x [h] dp) the widget is on the home screen. Text, rings and
 * bars are multiplied by it, so a widget stretched to twice the size shows everything twice as big
 * instead of small contents floating in a big box. Needs [SizeMode.Exact] for the real size.
 */
@Composable
private fun scale(w: Float, h: Float): Float {
    val size = LocalSize.current
    if (size.width.value <= 0f || size.height.value <= 0f) return 1f
    return minOf(size.width.value / w, size.height.value / h).coerceIn(0.75f, 2.4f)
}

/** Ring pixels for a ring [dp] across: sharp, and capped so the widget stays under the binder size limit. */
private fun ringPx(dp: Float): Int = (dp * 2.6f).toInt().coerceIn(160, 280)

// ------------------------------------------------------------------------------------------ ring (square)

class RingWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            RingContent(data)
        }
    }
}

@Composable
private fun RingContent(d: WidgetData) {
    val c = d.colors
    val k = scale(150f, 150f)
    Box(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(ringBitmap(d.progress, c, ringPx(130 * k))),
            contentDescription = "${Format.steps(d.steps)} of ${Format.steps(d.goal)} steps",
            // Fill the widget (keeping it round) instead of a fixed 120dp, which was clipped at the
            // 110dp minimum size once the 10dp padding is taken off.
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Format.steps(d.steps),
                style = style(c.ink, 20 * k, FontWeight.Bold),
                maxLines = 1,
            )
            Text("steps", style = style(c.quiet, 11 * k), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------------------------------ stats (square)

/** Exact size, so the layout can drop the "Today" label and the calories when the widget is small. */
class StatsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            StatsContent(data)
        }
    }
}

@Composable
private fun StatsContent(d: WidgetData) {
    val c = d.colors
    val size = LocalSize.current
    val tall = size.height >= 140.dp
    val wide = size.width >= 150.dp
    val k = scale(150f, 150f)
    val innerWidth = (size.width.value - 24f).coerceAtLeast(40f)
    // About 3 px per dp, capped: at most 720 x 18 px (~52 KB).
    val barBitmap = progressBitmap(d.progress, c, (innerWidth * 3).toInt().coerceIn(120, 720), 18)
    val distance = Format.distance(d.distanceMeters, d.units)
    val calories = "${Format.steps(d.calories)} kcal"
    Column(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tall) {
            Text("Today", style = style(c.quiet, 11 * k), maxLines = 1)
        }
        Text(
            Format.steps(d.steps),
            style = style(c.ink, (if (wide && tall) 26 else 22) * k, FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            // At the 110dp minimum only 86dp is left for "of 10,000 steps", so the narrow form drops "steps".
            when {
                d.goal <= 0 -> "steps"
                wide -> "of ${Format.steps(d.goal)} steps"
                else -> "of ${Format.steps(d.goal)}"
            },
            style = style(c.quiet, 11 * k),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height((6 * k).dp))
        Image(
            provider = ImageProvider(barBitmap),
            contentDescription = "${(d.progress * 100).toInt().coerceAtMost(999)}% of goal",
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height((6 * k).dp),
        )
        Spacer(GlanceModifier.height((6 * k).dp))
        Text(
            if (wide) "$distance · $calories" else distance,
            style = style(c.ink, 11 * k, FontWeight.Medium),
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------------------------------ streak (square)

class StreakWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            StreakContent(data)
        }
    }
}

@Composable
private fun StreakContent(d: WidgetData) {
    val c = d.colors
    val size = LocalSize.current
    val tall = size.height >= 140.dp
    val k = scale(150f, 150f)
    val status = when {
        d.goal <= 0 -> "No goal set"
        d.steps >= d.goal -> "Goal reached"
        else -> "${Format.steps(d.goal - d.steps)} to go"
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(flameBitmap(c)),
                contentDescription = null,
                modifier = GlanceModifier.size(((if (tall) 20 else 16) * k).dp),
            )
            Spacer(GlanceModifier.width((6 * k).dp))
            Text(
                d.streak.toString(),
                style = style(c.ink, (if (tall) 34 else 28) * k, FontWeight.Bold),
                maxLines = 1,
            )
        }
        Text("day streak", style = style(c.quiet, 12 * k), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(
            status,
            style = style(if (d.goal > 0 && d.steps >= d.goal) c.accent else c.ink, 11 * k, FontWeight.Medium),
            maxLines = 1,
        )
        if (tall) {
            Spacer(GlanceModifier.height((2 * k).dp))
            Text(
                "Best ${d.bestStreak} ${if (d.bestStreak == 1) "day" else "days"}",
                style = style(c.quiet, 10 * k),
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ week (rectangle)

class WeekWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            WeekContent(data)
        }
    }
}

@Composable
private fun WeekContent(d: WidgetData) {
    val c = d.colors
    val k = scale(320f, 150f)
    val bar = 12 * k
    // Leaves room for the labels when the home screen uses larger text, as the Fold's inner screen does.
    val barHeight = 54 * k
    Row(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ringBitmap(d.progress, c, ringPx(92 * k))),
                contentDescription = null,
                modifier = GlanceModifier.size((92 * k).dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.steps(d.steps), style = style(c.ink, 16 * k, FontWeight.Bold), maxLines = 1)
                Text("today", style = style(c.quiet, 10 * k), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width((14 * k).dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("Last 7 days", style = style(c.quiet, 11 * k), maxLines = 1)
            Spacer(GlanceModifier.height((6 * k).dp))
            val max = maxOf(d.goal, d.week.maxOfOrNull { it.second } ?: 0, 1).toFloat()
            // A cell per day, its bar above its letter, so they line up at any widget width. The bars are
            // layout boxes rather than pictures, so they stay crisp and round however the widget is sized.
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                d.week.forEachIndexed { i, (label, steps) ->
                    val isToday = i == d.week.lastIndex
                    Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = GlanceModifier.width(bar.dp).height(barHeight.dp).background(c.track).cornerRadius((bar / 2).dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (steps > 0) {
                                val fill = (barHeight * (steps / max)).coerceIn(bar, barHeight)
                                Box(
                                    modifier = GlanceModifier.width(bar.dp).height(fill.dp).cornerRadius((bar / 2).dp)
                                        .background(if (d.goal > 0 && steps >= d.goal) c.accent else c.quiet),
                                ) {}
                            }
                        }
                        Spacer(GlanceModifier.height((4 * k).dp))
                        Text(
                            label,
                            style = style(
                                if (isToday) c.ink else c.quiet, 10 * k,
                                if (isToday) FontWeight.Bold else FontWeight.Normal, TextAlign.Center,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ today (rectangle)

class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            TodayContent(data)
        }
    }
}

@Composable
private fun TodayContent(d: WidgetData) {
    val c = d.colors
    val k = scale(320f, 150f)
    Row(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 82dp: the height left at the 110dp minimum once the padding is taken off.
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ringBitmap(d.progress, c, ringPx(82 * k))),
                contentDescription = "${Format.steps(d.steps)} of ${Format.steps(d.goal)} steps",
                modifier = GlanceModifier.size((82 * k).dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.steps(d.steps), style = style(c.ink, 15 * k, FontWeight.Bold), maxLines = 1)
                Text("steps", style = style(c.quiet, 10 * k), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width((16 * k).dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            StatRow("Distance", Format.distance(d.distanceMeters, d.units), c, k)
            Spacer(GlanceModifier.height((8 * k).dp))
            StatRow("Active", Format.minutes(d.activeMinutes), c, k)
            Spacer(GlanceModifier.height((8 * k).dp))
            StatRow("Calories", "${Format.steps(d.calories)} kcal", c, k)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, c: WidgetColors, k: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = GlanceModifier.defaultWeight(), style = style(c.quiet, 12 * k), maxLines = 1)
        Text(value, style = style(c.ink, 13 * k, FontWeight.Medium, TextAlign.End), maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------ hourly (rectangle)

class HourlyWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = load(context)
        provideContent {
            val data by remember { dataFlow(context) }.collectAsState(initial)
            HourlyContent(data)
        }
    }
}

@Composable
private fun HourlyContent(d: WidgetData) {
    val c = d.colors
    val k = scale(320f, 150f)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(Format.steps(d.steps), style = style(c.ink, 18 * k, FontWeight.Bold), maxLines = 1)
            Spacer(GlanceModifier.width((5 * k).dp))
            Text(
                if (d.goal > 0) "of ${Format.steps(d.goal)}" else "steps",
                style = style(c.quiet, 11 * k),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Text("Today by hour", style = style(c.quiet, 11 * k), maxLines = 1)
        }
        Spacer(GlanceModifier.height((8 * k).dp))
        // 480 x 100 px (~190 KB), stretched to the space left; the small corner radius hides the stretch.
        Image(
            provider = ImageProvider(barsBitmap(d.hourly, 0, c, w = 480, h = 100, gapRatio = 0.35f, hourly = true)),
            contentDescription = "Steps for each hour today",
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
        Spacer(GlanceModifier.height((4 * k).dp))
        // Each label starts a quarter of the way along, under the first bar of 00, 06, 12 and 18.
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18").forEach { label ->
                Text(label, modifier = GlanceModifier.defaultWeight(), style = style(c.quiet, 10 * k), maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ receivers

class RingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingWidget()
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class HourlyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HourlyWidget()
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var last = 0L
    @Volatile private var pending = false
    internal val redraws = MutableStateFlow(0L)

    /** Redraws every widget, at most every 20 seconds; a skipped redraw happens when the wait ends. */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val wait = 20_000 - (System.currentTimeMillis() - last)
        if (!force && wait > 0) {
            if (pending) return
            pending = true
            scope.launch {
                delay(wait)
                pending = false
                refresh(app, force = true)
            }
            return
        }
        last = System.currentTimeMillis()
        // Also reloads in sessions that are still alive, which an update() alone only recomposes with the
        // data they already hold: after a light/dark switch they would keep the old colours.
        redraws.value = redraws.value + 1
        scope.launch {
            runCatching { RingWidget().updateAll(app) }
            runCatching { WeekWidget().updateAll(app) }
            runCatching { StatsWidget().updateAll(app) }
            runCatching { StreakWidget().updateAll(app) }
            runCatching { TodayWidget().updateAll(app) }
            runCatching { HourlyWidget().updateAll(app) }
        }
    }
}
