package com.pandyabhavik.pedometer.core

/**
 * The phone's hardware step counter reports a running total since the last boot. These are the
 * rules that turn those totals into steps, including across reboots.
 */
data class CounterCheckpoint(
    /** The counter value at the last reading. */
    val counter: Long,
    /** Wall-clock time of the last reading. */
    val atMillis: Long,
    /** Settings.Global.BOOT_COUNT at the last reading; changes on every reboot. */
    val bootCount: Int,
)

data class CounterReading(val counter: Long, val atMillis: Long, val bootCount: Int, val bootedAtMillis: Long)

data class CounterStep(
    val steps: Long,
    /** When those steps happened, at the earliest; they are spread from here to the reading. */
    val fromMillis: Long,
    val toMillis: Long,
    val checkpoint: CounterCheckpoint,
)

object StepCounterRules {
    /** More than this in one interval is a broken reading, not walking (about 4 steps a second). */
    private const val MAX_STEPS_PER_SECOND = 4.0

    fun advance(previous: CounterCheckpoint?, reading: CounterReading): CounterStep {
        val next = CounterCheckpoint(reading.counter, reading.atMillis, reading.bootCount)
        if (previous == null) {
            // First reading ever: it becomes the baseline. Steps from before the app was installed aren't ours.
            return CounterStep(0, reading.atMillis, reading.atMillis, next)
        }
        // Readings come from more than one place (the 15-minute read, the live count while the app is open).
        // An older reading that arrives after a newer one is lower without a reboot: ignore it.
        if (reading.bootCount == previous.bootCount && reading.counter < previous.counter && reading.atMillis <= previous.atMillis) {
            return CounterStep(0, previous.atMillis, previous.atMillis, previous)
        }
        val rebooted = reading.bootCount != previous.bootCount || reading.counter < previous.counter
        val steps: Long
        val from: Long
        if (rebooted) {
            // The counter restarted from zero at boot, so the whole reading is new steps since then.
            steps = reading.counter
            from = maxOf(previous.atMillis, reading.bootedAtMillis)
        } else {
            steps = reading.counter - previous.counter
            from = previous.atMillis
        }
        val to = maxOf(reading.atMillis, from)
        val seconds = maxOf(1.0, (to - from) / 1000.0)
        if (steps < 0 || steps > seconds * MAX_STEPS_PER_SECOND + 50) {
            return CounterStep(0, to, to, next)
        }
        return CounterStep(steps, from, to, next)
    }
}
