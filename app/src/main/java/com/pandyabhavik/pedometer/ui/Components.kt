package com.pandyabhavik.pedometer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.ui.theme.LocalPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The day ring: the outer arc fills toward the goal (a second, brighter lap shows steps past it),
 * and the inner dial has one tick per hour that grows with how much you walked in that hour.
 */
@Composable
fun DayRing(
    steps: Long,
    goal: Long,
    hourly: List<Long>,
    currentHour: Int?,
    modifier: Modifier = Modifier,
    numberStyle: TextStyle = MaterialTheme.typography.displayLarge,
) {
    val palette = LocalPalette.current
    val primary = MaterialTheme.colorScheme.primary
    val progress = if (goal > 0) steps.toFloat() / goal else 0f
    val animated by animateFloatAsState(progress, tween(900, easing = FastOutSlowInEasing), label = "ring")
    val count = remember { Animatable(0f) }
    LaunchedEffect(steps) { count.animateTo(steps.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    val maxHour = (hourly.maxOrNull() ?: 0).coerceAtLeast(1)

    BoxWithConstraints(
        modifier = modifier.semantics {
            contentDescription = "${Format.steps(steps)} steps of ${Format.steps(goal)}, ${(progress * 100).toInt()} percent"
        },
        contentAlignment = Alignment.Center,
    ) {
        // The number has to fit inside the hour dial (about 55% of the ring across), whatever the ring's
        // size and the font scale: shrink the style when the full-size number would run into the ticks.
        val density = LocalDensity.current
        val clear = minOf(maxWidth, maxHeight) * 0.55f
        val chars = Format.steps(maxOf(steps, goal)).length.coerceAtLeast(1)
        val fitted = with(density) { (clear / (chars * 0.5f)).toSp() }
        val roomy = minOf(maxWidth, maxHeight) >= 190.dp
        val number = if (numberStyle.fontSize.isSp && fitted < numberStyle.fontSize) {
            numberStyle.copy(fontSize = fitted, lineHeight = fitted * 1.15f)
        } else numberStyle
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.06f
            val inset = stroke / 2
            val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            drawArc(palette.track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val first = animated.coerceIn(0f, 1f)
            if (first > 0f) {
                drawArc(primary, -90f, (360f * first).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val second = (animated - 1f).coerceIn(0f, 1f)
            if (second > 0f) {
                drawArc(palette.highlight, -90f, 360f * second, false, topLeft, arcSize, style = Stroke(stroke * 0.55f, cap = StrokeCap.Round))
            }
            // Hour dial: 24 ticks, midnight at the top, like a 24-hour clock face.
            val center = Offset(size.width / 2, size.height / 2)
            val outer = size.minDimension / 2 - stroke - inset - size.minDimension * 0.035f
            val maxLen = size.minDimension * 0.07f
            for (h in 0 until 24) {
                val angle = (h / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                val v = hourly.getOrElse(h) { 0 }
                val len = if (v > 0) (maxLen * 0.25f + maxLen * 0.75f * (v.toFloat() / maxHour)) else maxLen * 0.12f
                val color = when {
                    currentHour != null && h > currentHour -> palette.dialIdle.copy(alpha = 0.5f)
                    v > 0 -> palette.dialActive.copy(alpha = 0.35f + 0.65f * (v.toFloat() / maxHour))
                    else -> palette.dialIdle
                }
                val start = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer)
                val end = Offset(center.x + cos(angle) * (outer - len), center.y + sin(angle) * (outer - len))
                drawLine(color, start, end, strokeWidth = size.minDimension * 0.012f, cap = StrokeCap.Round)
                if (h == currentHour) {
                    drawCircle(palette.highlight, radius = size.minDimension * 0.012f, center = Offset(center.x + cos(angle) * (outer + size.minDimension * 0.022f), center.y + sin(angle) * (outer + size.minDimension * 0.022f)))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Format.steps(count.value.toLong()),
                style = number,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            // A small ring (onboarding) has no room inside the dial for the goal and percent lines.
            if (!roomy) return@Column
            Text(
                if (goal > 0) "of ${Format.steps(goal)} steps" else "steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (goal > 0) {
                Spacer(Modifier.height(6.dp))
                val pct = (progress * 100).toInt()
                Text(
                    if (pct >= 100) "Goal reached · $pct%" else "$pct%",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (pct >= 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A card: quiet surface, hairline border, no shadow. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, LocalPalette.current.hairline, RoundedCornerShape(22.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatTile(icon: ImageVector, label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Panel(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/**
 * Bars with an optional dashed goal line. Bars at or over the goal use the accent; tapping a bar
 * selects it.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    goal: Long?,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    highlightGoal: Boolean = true,
    labelEvery: Int = 1,
    onSelect: ((Int) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val primary = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val max = maxOf(values.maxOrNull() ?: 0, goal ?: 0, 1).toFloat()
    // Bars grow in when the values change (a new period or page).
    val growth = remember { Animatable(0f) }
    LaunchedEffect(values) {
        growth.snapTo(0f)
        growth.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    val grown = growth.value
    // pointerInput below is keyed on the bar count only, so read the latest callback through state.
    val currentOnSelect by rememberUpdatedState(onSelect)
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .then(
                        if (onSelect != null && values.isNotEmpty()) Modifier.pointerInput(values.size) {
                            detectTapGestures { p ->
                                val i = (p.x / size.width * values.size).toInt().coerceIn(0, values.size - 1)
                                currentOnSelect?.invoke(i)
                            }
                        } else Modifier,
                    ),
            ) {
                if (values.isEmpty()) return@Canvas
                val n = values.size
                val slot = size.width / n
                val bar = (slot * if (n > 14) 0.62f else 0.56f).coerceAtMost(28.dp.toPx())
                val radius = CornerRadius(bar / 2, bar / 2)
                values.forEachIndexed { i, v ->
                    val x = i * slot + (slot - bar) / 2
                    val h = if (v > 0) (size.height * (v / max) * grown).coerceAtLeast(bar.coerceAtMost(6.dp.toPx())) else 0f
                    val hit = highlightGoal && goal != null && goal > 0 && v >= goal
                    val color = when {
                        selected != null && i != selected -> (if (hit) primary else quiet).copy(alpha = 0.28f)
                        hit -> primary
                        selected == i -> primary
                        else -> quiet.copy(alpha = if (palette.grayscale) 0.55f else 0.45f)
                    }
                    if (v <= 0) {
                        drawCircle(palette.dialIdle, radius = 2.dp.toPx(), center = Offset(x + bar / 2, size.height - 2.dp.toPx()))
                    } else {
                        drawRoundRect(color, Offset(x, size.height - h), Size(bar, h), radius)
                    }
                }
                if (goal != null && goal > 0) {
                    val y = size.height - size.height * (goal / max)
                    drawLine(
                        palette.highlight.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y),
                        strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                // A month has ~11dp per bar on the cover screen, narrower than "22": let the label run
                // over its (empty) neighbours, centred on its bar, instead of being clipped.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (i % labelEvery == 0) label else "",
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (selected == i) MaterialTheme.colorScheme.onBackground else quiet,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** A pill toggle row (Day / Week / Month / Year and similar). */
@Composable
fun <T> PillToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, LocalPalette.current.hairline, RoundedCornerShape(50)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small rounded label, such as the streak. */
@Composable
fun Chip(text: String, icon: ImageVector?, modifier: Modifier = Modifier, accent: Boolean = false) {
    val bg = if (accent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = fg)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
