package com.pandyabhavik.pedometer

import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.data.HeartSlot
import com.pandyabhavik.pedometer.data.KnownSource
import com.pandyabhavik.pedometer.data.PhoneSlot
import com.pandyabhavik.pedometer.data.SourceSlot
import com.pandyabhavik.pedometer.data.ThemeMode
import java.time.LocalDate
import java.time.ZoneId

/** Three months of realistic data (phone, a Fitbit on weekdays, heart rate), shared by the device tests. */
object TestData {
    suspend fun seed(app: AppEntryPoint, onboarded: Boolean, theme: ThemeMode) {
        app.database().clearAllTables()
        app.settings().update { it.copy(onboarded = onboarded, theme = theme, goal = 10_000) }
        val dao = app.dao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fitbit = "com.fitbit.FitbitMobile"
        dao.addSources(listOf(KnownSource(fitbit, SourceRole.WEARABLE.name, 0)))
        val phone = ArrayList<PhoneSlot>()
        val band = ArrayList<SourceSlot>()
        val heart = ArrayList<HeartSlot>()
        val days = (0L..95L).map { today.minusDays(it) }
        val now = System.currentTimeMillis()
        days.forEachIndexed { i, day ->
            // A different shape each day: some big days, some lazy ones, the band off on weekends.
            val scale = listOf(1.25, 0.6, 1.05, 0.95, 1.4, 0.45, 1.1, 0.8, 1.6, 1.0)[i % 10]
            val bandOn = day.dayOfWeek.value <= 5
            Slots.slotsOfDay(day, zone).forEach { slot ->
                if (slot > now) return@forEach
                val hour = java.time.Instant.ofEpochMilli(slot).atZone(zone).hour
                val base = when (hour) {
                    in 7..8 -> 260; 12 -> 330; in 17..18 -> 420; in 9..21 -> 70; else -> 0
                }
                val n = (base * scale * (0.6 + ((slot / Slots.MILLIS) % 7) / 10.0)).toLong()
                if (n <= 0) return@forEach
                phone += PhoneSlot(slot, (n * 0.92).toLong())
                if (bandOn && hour >= 7) {
                    band += SourceSlot(fitbit, slot, n, n * 0.78)
                    heart += HeartSlot(fitbit, slot, 62, 62 + n / 6)
                }
            }
        }
        dao.upsertPhone(phone)
        dao.insertSourceSlots(band)
        dao.insertHeartSlots(heart)
        app.repository().recompute(days)
        app.repository().updateBadges()
    }
}
