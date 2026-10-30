package com.pandyabhavik.pedometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteFilterTest {
    private val m = Slots.MILLIS
    private val hour = 3_600_000L
    private val now = 100 * m

    private fun phone(slot: Int, steps: Long) = StepBucket(slot * m, steps, StepOrigin.PHONE_SENSOR)

    @Test fun `steps read from Health Connect are never written back`() {
        val fromFitbit = StepBucket(1 * m, 800, StepOrigin.HEALTH_CONNECT)
        val plan = HealthConnectWriteFilter.plan(listOf(fromFitbit, phone(2, 300)), emptySet(), emptyList(), now, hour)
        assertEquals(listOf(phone(2, 300)), plan.write)
    }

    @Test fun `phone steps are not written while the wearable covered the slot`() {
        val plan = HealthConnectWriteFilter.plan(listOf(phone(1, 300), phone(2, 200)), setOf(1 * m), emptyList(), now, hour)
        assertEquals(listOf(phone(2, 200)), plan.write)
    }

    @Test fun `recent slots wait for the settle period`() {
        val recent = phone(95, 300) // ends 20 minutes before now
        val plan = HealthConnectWriteFilter.plan(listOf(recent, phone(80, 100)), emptySet(), emptyList(), now, hour)
        assertEquals(listOf(phone(80, 100)), plan.write)
    }

    @Test fun `already written slots are skipped unless the count changed`() {
        val written = listOf(WrittenSlot(1 * m, 300), WrittenSlot(2 * m, 150))
        val plan = HealthConnectWriteFilter.plan(listOf(phone(1, 300), phone(2, 180)), emptySet(), written, now, hour)
        assertEquals(listOf(phone(2, 180)), plan.write)
        assertTrue(plan.delete.isEmpty())
    }

    @Test fun `a late fitbit sync retracts what the phone wrote`() {
        val written = listOf(WrittenSlot(1 * m, 300), WrittenSlot(2 * m, 150))
        val plan = HealthConnectWriteFilter.plan(listOf(phone(1, 300), phone(2, 150)), setOf(2 * m), written, now, hour)
        assertEquals(listOf(2 * m), plan.delete)
        assertTrue(plan.write.isEmpty())
    }

    @Test fun `zero-step slots are not written`() {
        val plan = HealthConnectWriteFilter.plan(listOf(phone(1, 0)), emptySet(), emptyList(), now, hour)
        assertTrue(plan.write.isEmpty())
    }

    @Test fun `wearable coverage comes from steps or heart rate, never from ignored sources`() {
        val roles = mapOf("fitbit" to SourceRole.WEARABLE, "samsung" to SourceRole.IGNORED)
        val slots = mapOf(
            1 * m to SlotInput(sourceSteps = mapOf("fitbit" to 10)),
            2 * m to SlotInput(heartRateFrom = setOf("fitbit")),
            3 * m to SlotInput(sourceSteps = mapOf("samsung" to 400)),
            4 * m to SlotInput(phoneSteps = 100),
        )
        assertEquals(setOf(1 * m, 2 * m), HealthConnectWriteFilter.wearableCovered(slots, roles))
    }
}
