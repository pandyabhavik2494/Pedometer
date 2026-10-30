package com.pandyabhavik.pedometer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.core.Badge
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.ui.theme.LocalPalette
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun AwardsScreen(days: DaysViewModel, wide: Boolean) {
    val streaks by days.streaks.collectAsStateWithLifecycle()
    val earned by days.badges.collectAsStateWithLifecycle()
    val all by days.days.collectAsStateWithLifecycle()
    val today by days.today.collectAsStateWithLifecycle()
    val earnedAt = earned.associate { it.id to it.earnedAt }
    val lifetime = all.values.sumOf { it.steps }
    val hits = all.values.count { it.goal > 0 && it.steps >= it.goal }

    val summary: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Big("Current streak", "${streaks.current}", if (streaks.current == 1) "day" else "days", Modifier.weight(1f).fillMaxHeight())
            Big("Best streak", "${streaks.best}", if (streaks.best == 1) "day" else "days", Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Big("Goal days", "$hits", null, Modifier.weight(1f).fillMaxHeight())
            Big("Lifetime", compact(lifetime), "steps", Modifier.weight(1f).fillMaxHeight())
        }
    }
    val calendar: @Composable () -> Unit = {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Last 20 weeks")
            Spacer(Modifier.height(12.dp))
            val palette = LocalPalette.current
            val primary = MaterialTheme.colorScheme.primary
            val weeks = 20
            val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val firstMonday = lastMonday.minusWeeks((weeks - 1).toLong())
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = maxWidth / weeks
                Canvas(Modifier.fillMaxWidth().height(cell * 7)) {
                    val c = size.width / weeks
                    val dot = c * 0.72f
                    for (w in 0 until weeks) for (d in 0 until 7) {
                        val day = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                        if (day.isAfter(today)) continue
                        val s = all[day]
                        val color = when {
                            s == null || s.steps == 0L -> palette.dialIdle.copy(alpha = 0.6f)
                            s.goal > 0 && s.steps >= s.goal -> primary
                            else -> primary.copy(alpha = 0.12f + 0.5f * (s.steps.toFloat() / s.goal.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        drawRoundRect(
                            color,
                            Offset(w * c + (c - dot) / 2, d * c + (c - dot) / 2),
                            Size(dot, dot), CornerRadius(dot * 0.3f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Each square is a day, Monday at the top. Solid squares met the goal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val badges: @Composable (Int) -> Unit = { columns ->
        SectionLabel("Badges · ${earned.size} of ${Badge.entries.size}")
        Spacer(Modifier.height(10.dp))
        Badge.entries.sortedBy { if (it.name in earnedAt) 0 else 1 }.chunked(columns).forEach { row ->
            // Same height for both cards in a row, whatever their text wraps to.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp).height(IntrinsicSize.Min)) {
                row.forEach { BadgeCard(it, earnedAt[it.name], Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                Text("Awards", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                summary()
                Spacer(Modifier.height(16.dp))
                calendar()
            }
            Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                Spacer(Modifier.height(48.dp))
                badges(2)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Awards", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            summary()
            Spacer(Modifier.height(16.dp))
            calendar()
            Spacer(Modifier.height(20.dp))
            badges(2)
        }
    }
}

private fun compact(n: Long): String = when {
    // From 999,950 up, "%.0fk" would print "1000k".
    n >= 999_950 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.0fk".format(n / 1_000.0)
    else -> Format.steps(n)
}

@Composable
private fun Big(label: String, value: String, unit: String?, modifier: Modifier) {
    Panel(modifier, padding = 16.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun BadgeCard(badge: Badge, earnedAt: Long?, modifier: Modifier) {
    val on = earnedAt != null
    Panel(modifier, padding = 14.dp) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Outlined.EmojiEvents else Icons.Outlined.Lock, null,
                tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(badge.title, style = MaterialTheme.typography.titleSmall, color = if (on) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(badge.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (earnedAt != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                Instant.ofEpochMilli(earnedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
