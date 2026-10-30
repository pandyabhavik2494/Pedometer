package com.pandyabhavik.pedometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalsTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun day(daysAgo: Long, steps: Long) = DayTotal(today.minusDays(daysAgo), steps, 10_000)

    @Test fun `streak continues through yesterday while today is in progress`() {
        val days = listOf(day(0, 3_000), day(1, 11_000), day(2, 12_000), day(3, 4_000), day(4, 15_000))
        assertEquals(Streaks(current = 2, best = 2), GoalRules.streaks(days, today))
    }

    @Test fun `today counts once the goal is hit`() {
        val days = listOf(day(0, 10_000), day(1, 11_000))
        assertEquals(2, GoalRules.streaks(days, today).current)
    }

    @Test fun `a missing day breaks the streak`() {
        val days = listOf(day(1, 11_000), day(3, 12_000), day(4, 12_000), day(5, 12_000))
        assertEquals(Streaks(current = 1, best = 3), GoalRules.streaks(days, today))
    }

    @Test fun `badges follow totals and streaks`() {
        val week = (0L..6L).map { day(it, 20_500) }
        val b = BadgeRules.earned(week, today)
        assertTrue(Badge.FIRST_10K in b && Badge.DAY_20K in b && Badge.STREAK_7 in b && Badge.LIFETIME_100K in b)
        assertTrue(Badge.DAY_30K !in b && Badge.LIFETIME_1M !in b)
    }

    @Test fun `csv rows`() {
        val r = ExportRow(today, 12_345, 2_000, 10_345, 9_000.0, 4.0, 95, 360, 10_000)
        assertEquals("2026-10-30,12345,2000,10345,9000.0,4.0,95,360,10000,yes", Csv.row(r))
    }
}
