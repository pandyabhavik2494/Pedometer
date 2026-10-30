package com.pandyabhavik.pedometer.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pandyabhavik.pedometer.AppEntryPoint
import com.pandyabhavik.pedometer.MainActivity
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Colours for the widgets, following the app's theme setting. */
private data class WidgetColors(val bg: Color, val ink: Color, val quiet: Color, val accent: Color, val track: Color)

private fun widgetColors(context: Context, theme: ThemeMode): WidgetColors {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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

private data class WidgetData(val steps: Long, val goal: Long, val week: List<Pair<String, Long>>, val colors: WidgetColors)

private suspend fun load(context: Context): WidgetData {
    val app = AppEntryPoint.get(context)
    val s = app.settings().current()
    val today = app.repository().today()
    val from = today.minusDays(6)
    val days = app.dao().days(from.toString(), today.toString()).associateBy { it.day }
    val week = (0L..6L).map { from.plusDays(it) }.map { d ->
        d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()) to (days[d.toString()]?.steps ?: 0)
    }
    val steps = days[today.toString()]?.steps ?: 0
    return WidgetData(steps, s.goal, week, widgetColors(context, s.theme))
}

/**
 * Fresh data whenever the day totals, the settings or the date change. Glance keeps a widget's session
 * alive for a while after it draws, and an update() during that time only recomposes: data loaded once
 * in provideGlance would be drawn again unchanged, so a refresh inside that window showed stale steps.
 */
private fun dataFlow(context: Context): Flow<WidgetData> {
    val app = AppEntryPoint.get(context)
    return combine(app.settings().settings, app.dao().allDaysFlow(), app.repository().todayFlow()) { _, _, _ -> load(context) }
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

private fun barsBitmap(values: List<Long>, goal: Long, colors: WidgetColors, w: Int = 420, h: Int = 150): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val max = maxOf(goal, values.maxOrNull() ?: 0, 1).toFloat()
    val gap = w * 0.05f
    val bar = (w - gap * (values.size - 1)) / values.size
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    values.forEachIndexed { i, v ->
        val x = i * (bar + gap)
        paint.color = colors.track.toArgb()
        c.drawRoundRect(RectF(x, 0f, x + bar, h.toFloat()), bar / 2, bar / 2, paint)
        if (v > 0) {
            val top = h - (h * (v / max)).coerceAtLeast(bar)
            paint.color = (if (goal > 0 && v >= goal) colors.accent else colors.quiet).toArgb()
            c.drawRoundRect(RectF(x, top, x + bar, h.toFloat()), bar / 2, bar / 2, paint)
        }
    }
    if (goal > 0) {
        val y = h - h * (goal / max)
        paint.color = colors.ink.copy(alpha = 0.35f).toArgb()
        paint.strokeWidth = 3f
        c.drawLine(0f, y, w.toFloat(), y, paint)
    }
    return bmp
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

class RingWidget : GlanceAppWidget() {
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
    Box(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(10.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(ringBitmap(if (d.goal > 0) d.steps.toFloat() / d.goal else 0f, c)),
            contentDescription = "${Format.steps(d.steps)} of ${Format.steps(d.goal)} steps",
            // Fill the widget (keeping it round) instead of a fixed 120dp, which was clipped at the
            // 110dp minimum size once the 10dp padding is taken off.
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Format.steps(d.steps),
                style = TextStyle(color = ColorProvider(c.ink), fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Text("steps", style = TextStyle(color = ColorProvider(c.quiet), fontSize = 11.sp))
        }
    }
}

class WeekWidget : GlanceAppWidget() {
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
    Row(
        modifier = GlanceModifier.fillMaxSize().background(c.bg).cornerRadius(24.dp).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ringBitmap(if (d.goal > 0) d.steps.toFloat() / d.goal else 0f, c)),
                contentDescription = null,
                modifier = GlanceModifier.size(92.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Format.steps(d.steps),
                    style = TextStyle(color = ColorProvider(c.ink), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                Text("today", style = TextStyle(color = ColorProvider(c.quiet), fontSize = 10.sp))
            }
        }
        Spacer(GlanceModifier.width(14.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text("Last 7 days", style = TextStyle(color = ColorProvider(c.quiet), fontSize = 11.sp))
            Spacer(GlanceModifier.height(6.dp))
            Image(
                provider = ImageProvider(barsBitmap(d.week.map { it.second }, d.goal, c)),
                contentDescription = "Steps for the last 7 days",
                modifier = GlanceModifier.fillMaxWidth().height(64.dp),
            )
            Spacer(GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                d.week.forEach { (label, _) ->
                    Text(
                        label,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(
                            color = ColorProvider(c.quiet), fontSize = 10.sp,
                            textAlign = androidx.glance.text.TextAlign.Center,
                        ),
                    )
                }
            }
        }
    }
}

class RingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingWidget()
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var last = 0L
    @Volatile private var pending = false

    /** Redraws both widgets, at most every 20 seconds; a skipped redraw happens when the wait ends. */
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
        scope.launch {
            runCatching { RingWidget().updateAll(app) }
            runCatching { WeekWidget().updateAll(app) }
        }
    }
}
