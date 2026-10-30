package com.pandyabhavik.pedometer.core

import java.time.LocalDate

data class DayTotal(val day: LocalDate, val steps: Long, val goal: Long) {
    val hit: Boolean get() = goal > 0 && steps >= goal
}

data class Streaks(val current: Int, val best: Int)

object GoalRules {
    /**
     * The current streak counts goal days in a row ending today, or ending yesterday while today
     * is still in progress. Days with no record count as misses.
     */
    fun streaks(days: List<DayTotal>, today: LocalDate): Streaks {
        val byDay = days.associateBy { it.day }
        var current = 0
        var d = if (byDay[today]?.hit == true) today else today.minusDays(1)
        while (byDay[d]?.hit == true) {
            current++
            d = d.minusDays(1)
        }
        var best = 0
        var run = 0
        var lastHit: LocalDate? = null
        days.filter { it.day <= today }.sortedBy { it.day }.forEach {
            if (it.hit) {
                run = if (lastHit?.plusDays(1) == it.day) run + 1 else 1
                lastHit = it.day
            } else {
                run = 0
                lastHit = null
            }
            best = maxOf(best, run)
        }
        return Streaks(current, maxOf(best, current))
    }
}

enum class Badge(val title: String, val detail: String) {
    FIRST_GOAL("First goal", "Reach your daily goal once"),
    FIRST_10K("Five figures", "Walk 10,000 steps in a day"),
    DAY_20K("Long haul", "Walk 20,000 steps in a day"),
    DAY_30K("Marathon day", "Walk 30,000 steps in a day"),
    STREAK_7("One week", "Reach your goal 7 days in a row"),
    STREAK_30("One month", "Reach your goal 30 days in a row"),
    STREAK_100("Hundred days", "Reach your goal 100 days in a row"),
    LIFETIME_100K("100k club", "100,000 steps in total"),
    LIFETIME_1M("Millionaire", "1,000,000 steps in total"),
    LIFETIME_5M("Five million", "5,000,000 steps in total"),
}

object BadgeRules {
    fun earned(days: List<DayTotal>, today: LocalDate): Set<Badge> {
        val out = HashSet<Badge>()
        val best = days.maxOfOrNull { it.steps } ?: 0
        if (days.any { it.hit }) out += Badge.FIRST_GOAL
        if (best >= 10_000) out += Badge.FIRST_10K
        if (best >= 20_000) out += Badge.DAY_20K
        if (best >= 30_000) out += Badge.DAY_30K
        val longest = GoalRules.streaks(days, today).best
        if (longest >= 7) out += Badge.STREAK_7
        if (longest >= 30) out += Badge.STREAK_30
        if (longest >= 100) out += Badge.STREAK_100
        val lifetime = days.sumOf { it.steps }
        if (lifetime >= 100_000) out += Badge.LIFETIME_100K
        if (lifetime >= 1_000_000) out += Badge.LIFETIME_1M
        if (lifetime >= 5_000_000) out += Badge.LIFETIME_5M
        return out
    }
}
