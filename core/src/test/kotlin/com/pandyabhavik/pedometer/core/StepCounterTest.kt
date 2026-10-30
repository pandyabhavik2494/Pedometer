package com.pandyabhavik.pedometer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterTest {
    private val minute = 60_000L

    @Test fun `first reading is only a baseline`() {
        val r = StepCounterRules.advance(null, CounterReading(5_000, 10 * minute, 7, 0))
        assertEquals(0L, r.steps)
        assertEquals(CounterCheckpoint(5_000, 10 * minute, 7), r.checkpoint)
    }

    @Test fun `normal readings give the difference`() {
        val prev = CounterCheckpoint(5_000, 0, 7)
        val r = StepCounterRules.advance(prev, CounterReading(5_600, 10 * minute, 7, 0))
        assertEquals(600L, r.steps)
        assertEquals(0L, r.fromMillis)
        assertEquals(10 * minute, r.toMillis)
    }

    @Test fun `after a reboot the new counter value is all new steps`() {
        val prev = CounterCheckpoint(12_000, 0, 7)
        val bootedAt = 30 * minute
        val r = StepCounterRules.advance(prev, CounterReading(250, 40 * minute, 8, bootedAt))
        assertEquals(250L, r.steps)
        // Placed between boot and the reading, not across the time the phone was off.
        assertEquals(bootedAt, r.fromMillis)
        assertEquals(CounterCheckpoint(250, 40 * minute, 8), r.checkpoint)
    }

    @Test fun `a lower counter in the same boot is a late reading, not a reboot`() {
        val prev = CounterCheckpoint(12_000, 0, 7)
        val r = StepCounterRules.advance(prev, CounterReading(40, 5 * minute, 7, 2 * minute))
        assertEquals(0L, r.steps)
        assertEquals(prev, r.checkpoint)
    }

    @Test fun `an older reading that arrives late is ignored, not taken for a reboot`() {
        val prev = CounterCheckpoint(5_600, 20 * minute, 7)
        val r = StepCounterRules.advance(prev, CounterReading(5_400, 15 * minute, 7, 0))
        assertEquals(0L, r.steps)
        assertEquals(prev, r.checkpoint)
    }

    @Test fun `a reboot right after boot with no steps adds nothing`() {
        val prev = CounterCheckpoint(12_000, 0, 7)
        val r = StepCounterRules.advance(prev, CounterReading(0, 5 * minute, 8, 4 * minute))
        assertEquals(0L, r.steps)
    }

    @Test fun `impossible jumps are dropped but become the new baseline`() {
        val prev = CounterCheckpoint(1_000, 0, 7)
        val r = StepCounterRules.advance(prev, CounterReading(900_000, minute, 7, 0))
        assertEquals(0L, r.steps)
        assertEquals(900_000L, r.checkpoint.counter)
    }

    @Test fun `two readings in a row never double count`() {
        var cp: CounterCheckpoint? = null
        var total = 0L
        listOf(100L, 150L, 150L, 400L).forEachIndexed { i, c ->
            val r = StepCounterRules.advance(cp, CounterReading(c, i * minute * 5, 1, 0))
            total += r.steps
            cp = r.checkpoint
        }
        assertEquals(300L, total)
    }
}
