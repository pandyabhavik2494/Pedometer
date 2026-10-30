package com.pandyabhavik.pedometer.core

/** How a Health Connect data source is treated. */
enum class SourceRole {
    /** A band or watch, such as the Fitbit Air through the Google Health app. */
    WEARABLE,
    /** Counted only when nothing else has data for that time. */
    OTHER,
    /** Never counted (Samsung Health by default: it counts the same phone steps this app does). */
    IGNORED,
}

enum class SourcePreference {
    /** In each slot the wearable wins whenever it was worn or counted; the phone fills the gaps. */
    PREFER_WEARABLE,
    /** The phone wins whenever it counted; the wearable fills the gaps. */
    PREFER_PHONE,
    /** Whichever counted more in each slot. */
    HIGHEST,
}

/** What the phone and each Health Connect source said about one slot. */
data class SlotInput(
    val phoneSteps: Long = 0,
    /** Steps per Health Connect source (package name), already without this app's own records. */
    val sourceSteps: Map<String, Long> = emptyMap(),
    /** Sources that have heart-rate data in this slot: a band with heart rate was being worn. */
    val heartRateFrom: Set<String> = emptySet(),
)

/** Who the counted steps in a slot came from. [PHONE] is this phone's own sensor. */
data class SlotResult(val steps: Long, val source: String) {
    companion object {
        const val PHONE = "phone"
        const val NONE = ""
    }
}

object StepMerge {
    /**
     * Picks one source per slot so overlapping steps are counted once.
     * [roles] says what each Health Connect source is; unknown sources count as [SourceRole.OTHER].
     */
    fun mergeSlot(input: SlotInput, roles: Map<String, SourceRole>, preference: SourcePreference): SlotResult {
        fun role(p: String) = roles[p] ?: SourceRole.OTHER
        val wearables = input.sourceSteps.filterKeys { role(it) == SourceRole.WEARABLE }
        val others = input.sourceSteps.filterKeys { role(it) == SourceRole.OTHER }
        val wornBy = input.heartRateFrom.filter { role(it) == SourceRole.WEARABLE }

        val bestWearable = wearables.maxByOrNull { it.value }
        val bestOther = others.maxByOrNull { it.value }
        val phone = input.phoneSteps

        return when (preference) {
            SourcePreference.PREFER_WEARABLE -> when {
                bestWearable != null && bestWearable.value > 0 -> SlotResult(bestWearable.value, bestWearable.key)
                // Worn but no steps: you were still. The phone's count is the one in your pocket, not on you.
                wornBy.isNotEmpty() -> SlotResult(0, wornBy.first())
                phone > 0 -> SlotResult(phone, SlotResult.PHONE)
                bestOther != null && bestOther.value > 0 -> SlotResult(bestOther.value, bestOther.key)
                else -> SlotResult(0, SlotResult.NONE)
            }
            SourcePreference.PREFER_PHONE -> when {
                phone > 0 -> SlotResult(phone, SlotResult.PHONE)
                bestWearable != null && bestWearable.value > 0 -> SlotResult(bestWearable.value, bestWearable.key)
                bestOther != null && bestOther.value > 0 -> SlotResult(bestOther.value, bestOther.key)
                else -> SlotResult(0, SlotResult.NONE)
            }
            SourcePreference.HIGHEST -> {
                val all = buildList {
                    add(SlotResult.PHONE to phone)
                    addAll(wearables.toList())
                    addAll(others.toList())
                }
                val top = all.maxByOrNull { it.second }
                if (top == null || top.second <= 0) SlotResult(0, SlotResult.NONE) else SlotResult(top.second, top.first)
            }
        }
    }

    /** Merges every slot; the keys are slot starts. */
    fun merge(
        slots: Map<Long, SlotInput>,
        roles: Map<String, SourceRole>,
        preference: SourcePreference,
    ): Map<Long, SlotResult> = slots.mapValues { mergeSlot(it.value, roles, preference) }

    /** Steps counted from each source, for the "per-source breakdown". */
    fun countedBySource(merged: Collection<SlotResult>): Map<String, Long> =
        merged.filter { it.steps > 0 }.groupBy { it.source }.mapValues { e -> e.value.sumOf { it.steps } }

    /** What each source recorded in total, counted or not. */
    fun rawBySource(slots: Collection<SlotInput>): Map<String, Long> {
        val out = HashMap<String, Long>()
        slots.forEach { s ->
            if (s.phoneSteps > 0) out.merge(SlotResult.PHONE, s.phoneSteps, Long::plus)
            s.sourceSteps.forEach { (k, v) -> if (v > 0) out.merge(k, v, Long::plus) }
        }
        return out
    }
}
