package com.pandyabhavik.pedometer.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SlotsTest {
    private val m = Slots.MILLIS

    @Test fun `a record inside one slot stays in that slot`() {
        assertEquals(mapOf(0L to 120L), Slots.distribute(60_000, 180_000, 120))
    }

    @Test fun `a record across slots is split by overlap and keeps its total`() {
        val out = Slots.distribute(0, 3 * m, 100)
        assertEquals(100L, out.values.sum())
        assertEquals(setOf(0L, m, 2 * m), out.keys)
        out.values.forEach { assert(it in 33L..34L) }
    }

    @Test fun `uneven overlap splits in proportion`() {
        // Half of the first slot, all of the second: one third and two thirds.
        val out = Slots.distribute(m / 2, 2 * m, 90)
        assertEquals(30L, out[0])
        assertEquals(60L, out[m])
    }

    @Test fun `a 25-hour day has 300 slots`() {
        val zone = ZoneId.of("Europe/London")
        assertEquals(300, Slots.slotsOfDay(LocalDate.of(2026, 10, 25), zone).count())
        assertEquals(288, Slots.slotsOfDay(LocalDate.of(2026, 10, 26), zone).count())
    }
}
