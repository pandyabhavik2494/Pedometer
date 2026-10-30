package com.pandyabhavik.pedometer

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.pandyabhavik.pedometer.core.Slots
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.data.HeartSlot
import com.pandyabhavik.pedometer.data.KnownSource
import com.pandyabhavik.pedometer.data.PhoneSlot
import com.pandyabhavik.pedometer.data.SourceSlot
import com.pandyabhavik.pedometer.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Walks every screen with three months of realistic data and saves a screenshot of each to
 * /sdcard/Download/pedometer-screens (pulled into the CI results). CI runs it at the Fold's cover
 * screen size and at its inner screen size.
 */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    private val app get() = AppEntryPoint.get(InstrumentationRegistry.getInstrumentation().targetContext)

    private val seed = object : ExternalResource() {
        override fun before() = runBlocking { seedData(onboarded = true, theme = ThemeMode.LIGHT) }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS))
        .around(seed)
        .around(compose)

    private suspend fun seedData(onboarded: Boolean, theme: ThemeMode) {
        app.database().clearAllTables()
        app.settings().update { it.copy(onboarded = onboarded, theme = theme, goal = 10_000) }
        val dao = app.dao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val fitbit = "com.fitbit.FitbitMobile"
        dao.addSources(listOf(KnownSource(fitbit, SourceRole.WEARABLE.name, 0)))
        val phone = ArrayList<PhoneSlot>()
        val band = ArrayList<SourceSlot>()
        val heart = ArrayList<HeartSlot>()
        val days = (0L..95L).map { today.minusDays(it) }
        val now = System.currentTimeMillis()
        days.forEachIndexed { i, day ->
            // A different shape each day: some big days, some lazy ones, the band off on weekends.
            val scale = listOf(1.25, 0.6, 1.05, 0.95, 1.4, 0.45, 1.1, 0.8, 1.6, 1.0)[i % 10]
            val bandOn = day.dayOfWeek.value <= 5
            Slots.slotsOfDay(day, zone).forEach { slot ->
                if (slot > now) return@forEach
                val hour = java.time.Instant.ofEpochMilli(slot).atZone(zone).hour
                val base = when (hour) {
                    in 7..8 -> 260; 12 -> 330; in 17..18 -> 420; in 9..21 -> 70; else -> 0
                }
                val n = (base * scale * (0.6 + ((slot / Slots.MILLIS) % 7) / 10.0)).toLong()
                if (n <= 0) return@forEach
                phone += PhoneSlot(slot, (n * 0.92).toLong())
                if (bandOn && hour >= 7) {
                    band += SourceSlot(fitbit, slot, n, n * 0.78)
                    heart += HeartSlot(fitbit, slot, 62, 62 + n / 6)
                }
            }
        }
        dao.upsertPhone(phone)
        dao.insertSourceSlots(band)
        dao.insertHeartSlots(heart)
        app.repository().recompute(days)
        app.repository().updateBadges()
    }

    private fun shell(command: String): String {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_200)
        shell("mkdir -p $SCREENS")
        shell("screencap -p $SCREENS/${size()}-$name.png")
    }

    private fun size(): String {
        val dm = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        return if (dm.widthPixels / dm.density >= 600) "inner" else "cover"
    }

    private fun tab(name: String) {
        compose.onNodeWithTag("nav-$name").performClick()
        compose.waitForIdle()
    }

    @Test
    fun everyScreen() {
        compose.onNodeWithTag("today-ring").assertExists()
        shot("01-today")
        if (size() == "cover") {
            compose.onNodeWithTag("sync-panel").assertExists()
            compose.onRoot().performTouchInput { swipeUp() }
            shot("02-today-scrolled")
        }
        tab("history")
        shot("03-history-week")
        compose.onNodeWithText("Month").performClick()
        shot("04-history-month")
        compose.onNodeWithText("Year").performClick()
        shot("05-history-year")
        compose.onNodeWithText("Day").performClick()
        shot("06-history-day")
        compose.onNodeWithText("Week").performClick()
        compose.onNodeWithTag("history-pager").performTouchInput { swipeRight() }
        shot("07-history-last-week")
        compose.onAllNodesWithText("Days".uppercase())[0].assertExists()
        // Open one day from the list.
        val yesterday = LocalDate.now().minusDays(7)
        compose.onAllNodes(hasText(yesterday.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))))[0].performScrollTo().performClick()
        compose.onNodeWithTag("day-detail").assertExists()
        shot("08-day-detail")
        tab("awards")
        shot("09-awards")
        tab("settings")
        shot("10-settings")
        compose.onNodeWithTag("setting-goal").performClick()
        shot("11-goal-dialog")
        compose.onNodeWithText("Cancel").performClick()
        runBlocking { app.settings().update { it.copy(theme = ThemeMode.DARK) } }
        tab("today")
        shot("12-today-dark")
        runBlocking { app.settings().update { it.copy(theme = ThemeMode.GRAYSCALE) } }
        shot("13-today-grayscale")
        tab("history")
        shot("14-history-grayscale")
        runBlocking { app.settings().update { it.copy(theme = ThemeMode.LIGHT) } }
    }

    @Test
    fun firstRun() {
        runBlocking { app.settings().update { it.copy(onboarded = false) } }
        compose.onNodeWithText("Every step, counted once").assertExists()
        shot("20-welcome")
        compose.onNodeWithTag("onboarding-action").performClick()
        shot("21-motion")
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT >= 33) compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        shot("22-health-connect")
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding-skip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("goal-value").assertExists()
        shot("23-goal")
        compose.onNodeWithTag("onboarding-action").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("today-ring")).fetchSemanticsNodes().isNotEmpty() }
        shot("24-after-onboarding")
        assertTrue(runBlocking { app.settings().current().onboarded })
    }

    companion object {
        const val SCREENS = "/sdcard/Download/pedometer-screens"
    }
}
