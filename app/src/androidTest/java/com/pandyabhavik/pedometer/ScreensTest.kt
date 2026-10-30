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
import com.pandyabhavik.pedometer.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate

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
        .outerRule(
            // POST_NOTIFICATIONS only exists from API 33; granting it on 30-32 makes the rule fail.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION)
            },
        )
        .around(seed)
        .around(compose)

    private suspend fun seedData(onboarded: Boolean, theme: ThemeMode) = TestData.seed(app, onboarded, theme)

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

    /** Settings arrive from DataStore asynchronously (the app shows nothing until they do), which Compose doesn't wait for. */
    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tab(name: String) {
        compose.onNodeWithTag("nav-$name").performClick()
        compose.waitForIdle()
    }

    @Test
    fun everyScreen() {
        waitFor(hasTestTag("today-ring"))
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
        waitFor(hasText("Every step, counted once"))
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
        waitFor(hasTestTag("today-ring"))
        shot("24-after-onboarding")
        assertTrue(runBlocking { app.settings().current().onboarded })
    }

    companion object {
        const val SCREENS = "/sdcard/Download/pedometer-screens"
    }
}
