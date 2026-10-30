package com.pandyabhavik.pedometer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {
    private val fitbit = "com.fitbit.FitbitMobile"
    private val samsung = "com.sec.android.app.shealth"
    private val other = "com.example.other"
    private val roles = mapOf(fitbit to SourceRole.WEARABLE, samsung to SourceRole.IGNORED, other to SourceRole.OTHER)

    private fun merge(input: SlotInput, p: SourcePreference = SourcePreference.PREFER_WEARABLE) =
        StepMerge.mergeSlot(input, roles, p)

    @Test fun `overlapping phone and fitbit steps count once, from fitbit`() {
        val r = merge(SlotInput(phoneSteps = 480, sourceSteps = mapOf(fitbit to 510)))
        assertEquals(SlotResult(510, fitbit), r)
    }

    @Test fun `phone fills in when fitbit was not worn`() {
        assertEquals(SlotResult(300, SlotResult.PHONE), merge(SlotInput(phoneSteps = 300)))
    }

    @Test fun `fitbit worn but still beats phone steps`() {
        val r = merge(SlotInput(phoneSteps = 40, heartRateFrom = setOf(fitbit)))
        assertEquals(SlotResult(0, fitbit), r)
    }

    @Test fun `samsung health is ignored so phone steps are not counted twice`() {
        val r = merge(SlotInput(phoneSteps = 300, sourceSteps = mapOf(samsung to 310)))
        assertEquals(SlotResult(300, SlotResult.PHONE), r)
    }

    @Test fun `other sources only fill empty slots`() {
        assertEquals(SlotResult(200, other), merge(SlotInput(sourceSteps = mapOf(other to 200))))
        assertEquals(SlotResult(90, SlotResult.PHONE), merge(SlotInput(phoneSteps = 90, sourceSteps = mapOf(other to 200))))
    }

    @Test fun `prefer phone uses fitbit only when phone has nothing`() {
        val p = SourcePreference.PREFER_PHONE
        assertEquals(SlotResult(480, SlotResult.PHONE), merge(SlotInput(480, mapOf(fitbit to 510)), p))
        assertEquals(SlotResult(510, fitbit), merge(SlotInput(0, mapOf(fitbit to 510)), p))
    }

    @Test fun `highest picks the larger count per slot`() {
        val p = SourcePreference.HIGHEST
        assertEquals(SlotResult(510, fitbit), merge(SlotInput(480, mapOf(fitbit to 510)), p))
        assertEquals(SlotResult(600, SlotResult.PHONE), merge(SlotInput(600, mapOf(fitbit to 510)), p))
        assertEquals(SlotResult(0, SlotResult.NONE), merge(SlotInput(), p))
    }

    @Test fun `a day mixing worn and unworn hours adds up without double counting`() {
        val slots = mapOf(
            0L to SlotInput(phoneSteps = 500, sourceSteps = mapOf(fitbit to 520), heartRateFrom = setOf(fitbit)),
            1L to SlotInput(phoneSteps = 300),
            2L to SlotInput(phoneSteps = 0, sourceSteps = mapOf(fitbit to 700)),
            3L to SlotInput(phoneSteps = 100, sourceSteps = mapOf(samsung to 100)),
        )
        val merged = StepMerge.merge(slots, roles, SourcePreference.PREFER_WEARABLE)
        assertEquals(520L + 300 + 700 + 100, merged.values.sumOf { it.steps })
        assertEquals(mapOf(fitbit to 1220L, SlotResult.PHONE to 400L), StepMerge.countedBySource(merged.values))
        assertEquals(mapOf(SlotResult.PHONE to 900L, fitbit to 1220L, samsung to 100L), StepMerge.rawBySource(slots.values))
    }
}
