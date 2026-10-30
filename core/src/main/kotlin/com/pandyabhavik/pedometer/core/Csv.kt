package com.pandyabhavik.pedometer.core

import java.time.LocalDate

data class ExportRow(
    val day: LocalDate,
    val steps: Long,
    val phoneSteps: Long,
    val wearableSteps: Long,
    val distanceMeters: Double,
    val floors: Double,
    val activeMinutes: Int,
    val calories: Long,
    val goal: Long,
)

object Csv {
    const val HEADER = "date,steps,phone_steps,wearable_steps,distance_m,floors,active_minutes,calories_kcal,goal,goal_hit"

    fun row(r: ExportRow): String = listOf(
        r.day.toString(),
        r.steps.toString(),
        r.phoneSteps.toString(),
        r.wearableSteps.toString(),
        "%.1f".format(java.util.Locale.ROOT, r.distanceMeters),
        "%.1f".format(java.util.Locale.ROOT, r.floors),
        r.activeMinutes.toString(),
        r.calories.toString(),
        r.goal.toString(),
        if (r.goal > 0 && r.steps >= r.goal) "yes" else "no",
    ).joinToString(",")

    fun write(rows: List<ExportRow>, out: Appendable) {
        out.append(HEADER).append('\n')
        rows.sortedBy { it.day }.forEach { out.append(row(it)).append('\n') }
    }
}
