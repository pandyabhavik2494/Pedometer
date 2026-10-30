package com.pandyabhavik.pedometer.core

import kotlin.math.roundToInt
import kotlin.math.roundToLong

object Estimates {
    /** A typical stride is about 41.4% of height. */
    fun strideFromHeightCm(heightCm: Double): Double = heightCm * 0.414 / 100.0

    fun distanceMeters(steps: Long, strideMeters: Double): Double = steps * strideMeters

    /**
     * Walking burns roughly 0.57 kcal per kilogram of body weight per kilometre (ACSM walking
     * equation, simplified). An estimate, labelled as one in the app.
     */
    fun calories(distanceMeters: Double, weightKg: Double): Long =
        (0.57 * weightKg * distanceMeters / 1000.0).roundToLong()

    /**
     * Minutes of activity in one 5-minute slot: about 100 steps a minute is a brisk walk, so a slot
     * with 500 steps is 5 active minutes. Slots under 60 steps (a few steps around the house) don't count.
     */
    fun activeMinutes(slotSteps: Long): Int =
        if (slotSteps < 60) 0 else minOf(Slots.MINUTES, (slotSteps / 100.0).roundToInt().coerceAtLeast(1))
}

enum class Units { METRIC, IMPERIAL }

object Format {
    fun steps(n: Long): String = "%,d".format(n)

    fun distance(meters: Double, units: Units): String = when (units) {
        Units.METRIC -> if (meters < 1000) "${meters.roundToInt()} m" else "%.2f km".format(meters / 1000)
        Units.IMPERIAL -> "%.2f mi".format(meters / 1609.344)
    }

    fun distanceValue(meters: Double, units: Units): Pair<String, String> = when (units) {
        Units.METRIC -> "%.2f".format(meters / 1000) to "km"
        Units.IMPERIAL -> "%.2f".format(meters / 1609.344) to "mi"
    }

    fun minutes(total: Int): String = if (total < 60) "${total}m" else "${total / 60}h ${total % 60}m"

    fun stride(meters: Double, units: Units): String = when (units) {
        Units.METRIC -> "${(meters * 100).roundToInt()} cm"
        Units.IMPERIAL -> "${(meters / 0.0254).roundToInt()} in"
    }
}
