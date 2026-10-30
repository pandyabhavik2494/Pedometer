package com.pandyabhavik.pedometer

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.pandyabhavik.pedometer.data.ThemeMode
import com.pandyabhavik.pedometer.widget.Widgets
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Puts every home-screen widget on a test "home screen" (this process hosts them, the way a launcher
 * does), with three months of data, and saves a screenshot in light, dark and grayscale to
 * /sdcard/Download/pedometer-screens next to the screen tests' ones. Besides the usual sizes, one
 * square and one rectangle are shown at their minimum size, where clipping shows first.
 */
@RunWith(AndroidJUnit4::class)
class WidgetsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val app get() = AppEntryPoint.get(context)

    @get:Rule
    val permissions: GrantPermissionRule =
        // POST_NOTIFICATIONS only exists from API 33; granting it on 30-32 makes the rule fail.
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GrantPermissionRule.grant(Manifest.permission.ACTIVITY_RECOGNITION)
        }

    /** Counts the RemoteViews the widget pushes, so the test knows when it has drawn (and redrawn). */
    private class CountingHostView(context: Context) : AppWidgetHostView(context) {
        val renders = AtomicInteger()
        @Volatile var lastLayoutId = 0

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            // null is the host's own "loading" placeholder, not something the widget drew.
            if (remoteViews != null) {
                lastLayoutId = remoteViews.layoutId
                renders.incrementAndGet()
            }
        }
    }

    private class CountingHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            CountingHostView(context)
    }

    /** One widget on the test home screen. Sizes are in dp. */
    private class Placed(val name: String, receiver: String, val widthDp: Int, val heightDp: Int, packageName: String) {
        val provider = ComponentName(packageName, "com.pandyabhavik.pedometer.widget.$receiver")
        var id = AppWidgetManager.INVALID_APPWIDGET_ID
        var view: CountingHostView? = null
        val label get() = "$name ${widthDp}x$heightDp"
        fun renders() = view?.renders?.get() ?: 0

        fun options(): Bundle = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            if (Build.VERSION.SDK_INT >= 31) {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
                )
            }
        }
    }

    private var root: FrameLayout? = null

    @Test
    fun everyWidget() {
        runBlocking { TestData.seed(app, onboarded = true, theme = ThemeMode.LIGHT) }
        val pkg = context.packageName
        val placed = listOf(
            Placed("Ring", "RingWidgetReceiver", SQUARE, SQUARE, pkg),
            Placed("Stats", "StatsWidgetReceiver", SQUARE, SQUARE, pkg),
            Placed("Streak", "StreakWidgetReceiver", SQUARE, SQUARE, pkg),
            Placed("Week", "WeekWidgetReceiver", RECT_W, RECT_H, pkg),
            Placed("Today", "TodayWidgetReceiver", RECT_W, RECT_H, pkg),
            Placed("Hourly", "HourlyWidgetReceiver", RECT_W, RECT_H, pkg),
            // The smallest a launcher lets you make them.
            Placed("Today (min)", "TodayWidgetReceiver", 250, 110, pkg),
            Placed("Stats (min)", "StatsWidgetReceiver", 110, 110, pkg),
        )

        val manager = AppWidgetManager.getInstance(context)
        val installed = manager.getInstalledProvidersForPackage(pkg, null)
        val missing = placed.map { it.provider }.distinct().filter { p -> installed.none { it.provider == p } }
        if (missing.isNotEmpty()) {
            fail("Widget providers not in the manifest: ${missing.joinToString { it.className }}")
        }

        // Instrumentation runs in the app's process, so the app is the widget host and needs the
        // bind grant a launcher has. It is given per install, and Gradle has just (re)installed the app.
        val grant = shell("appwidget grantbind --package $pkg --user ${Process.myUid() / 100_000}").trim()
        Log.i(TAG, "appwidget grantbind: '$grant'")

        val host = CountingHost(context, HOST_ID)
        // A run that died half way may have left widgets bound to this host.
        runCatching { host.deleteHost() }
        host.startListening()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val errors = mutableListOf<String>()
        try {
            scenario.onActivity { activity ->
                val wallpaper = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(WALL_TOP, WALL_BOTTOM))
                activity.window.setBackgroundDrawable(ColorDrawable(WALL_TOP))
                val container = FrameLayout(activity).apply { background = wallpaper }
                root = container
                activity.setContentView(container)
            }
            waitForRootLayout(scenario)

            for (w in placed) {
                w.id = host.allocateAppWidgetId()
                if (!manager.bindAppWidgetIdIfAllowed(w.id, w.provider, w.options())) {
                    fail(
                        "Not allowed to bind the ${w.label} widget (${w.provider.className}): " +
                            "`appwidget grantbind` didn't take effect (it said: '$grant').",
                    )
                }
            }
            scenario.onActivity { activity -> layOut(activity, host, placed, installed) }
            // Binding sends an update already; this makes sure, the way a launcher's first add does.
            for (w in placed) {
                context.sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .setComponent(w.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(w.id)),
                )
            }
            awaitRenders(placed, emptyMap(), 20_000)?.let { fail(it) }
            settle(placed)
            checkNoErrorLayout(placed, "light")?.let { errors += it }
            shot("30-widgets-light")

            retheme(placed, ThemeMode.DARK, "31-widgets-dark", errors)
            retheme(placed, ThemeMode.GRAYSCALE, "32-widgets-grayscale", errors)

            if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
        } finally {
            runCatching { runBlocking { app.settings().update { it.copy(theme = ThemeMode.LIGHT) } } }
            for (w in placed) {
                if (w.id != AppWidgetManager.INVALID_APPWIDGET_ID) runCatching { host.deleteAppWidgetId(w.id) }
            }
            runCatching { host.stopListening() }
            runCatching { host.deleteHost() }
            runCatching { scenario.close() }
            root = null
        }
    }

    private fun retheme(placed: List<Placed>, theme: ThemeMode, name: String, errors: MutableList<String>) {
        val before = placed.associateWith { it.renders() }
        runBlocking { app.settings().update { it.copy(theme = theme) } }
        Widgets.refresh(context, force = true)
        // Still take the screenshot when a widget didn't redraw: the picture shows which one.
        awaitRenders(placed, before, 15_000)?.let { errors += "$theme: $it" }
        settle(placed)
        checkNoErrorLayout(placed, theme.name)?.let { errors += it }
        shot(name)
    }

    private fun waitForRootLayout(scenario: ActivityScenario<ComponentActivity>) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (true) {
            var laidOut = false
            scenario.onActivity { laidOut = (root?.width ?: 0) > 0 && (root?.height ?: 0) > 0 }
            if (laidOut) return
            if (SystemClock.uptimeMillis() > end) fail("The widget test's host activity never laid out")
            Thread.sleep(100)
        }
    }

    /**
     * Wraps the widgets into rows that fit the screen's width, each under a small caption. Widgets keep
     * their real size; if the whole grid is taller than the screen (the cover screen), it is scaled down
     * as a picture so every widget is still in the screenshot.
     */
    private fun layOut(activity: ComponentActivity, host: AppWidgetHost, placed: List<Placed>, installed: List<AppWidgetProviderInfo>) {
        val root = checkNotNull(this.root)
        val density = activity.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val gap = px(GAP)
        val pad = px(PAD)
        val caption = px(CAPTION)

        val maxRowWidth = root.width - 2 * pad
        val rows = mutableListOf<MutableList<Placed>>()
        for (w in placed) {
            val row = rows.lastOrNull()
            val used = row?.sumOf { px(it.widthDp) + gap } ?: 0
            if (row != null && used + px(w.widthDp) <= maxRowWidth) row += w else rows += mutableListOf(w)
        }

        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        var total = 2 * pad
        rows.forEachIndexed { r, row ->
            val rowView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            row.forEachIndexed { i, w ->
                val info = installed.first { it.provider == w.provider }
                val view = host.createView(activity, w.id, info) as? CountingHostView
                    ?: fail("Couldn't create a view for the ${w.label} widget")
                view.setPadding(0, 0, 0, 0)
                w.view = view
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(activity).apply {
                            text = w.label.replace("x", " × ") + " dp"
                            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
                            setTextColor(Color.WHITE)
                            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
                            gravity = Gravity.CENTER_VERTICAL
                            maxLines = 1
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, caption),
                    )
                    addView(view, LinearLayout.LayoutParams(px(w.widthDp), px(w.heightDp)))
                }
                rowView.addView(
                    cell,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (i > 0) leftMargin = gap
                    },
                )
            }
            grid.addView(
                rowView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (r > 0) topMargin = gap
                },
            )
            total += caption + row.maxOf { px(it.heightDp) } + if (r > 0) gap else 0
        }

        // An exact height lets the grid be taller than the screen; the scale then fits it back in.
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, total))
        if (total > root.height) {
            val scale = root.height.toFloat() / total
            grid.pivotX = root.width / 2f
            grid.pivotY = 0f
            grid.scaleX = scale
            grid.scaleY = scale
            Log.i(TAG, "Widget grid is ${total}px tall on a ${root.height}px screen; drawn at ${"%.2f".format(scale)}x")
        }
    }

    /** Null once every widget has pushed a new RemoteViews since [before], else what's still missing. */
    private fun awaitRenders(placed: List<Placed>, before: Map<Placed, Int>, timeoutMs: Long): String? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val waiting = placed.filter { it.renders() <= (before[it] ?: 0) }
            if (waiting.isEmpty()) return null
            if (SystemClock.uptimeMillis() > end) {
                return "No render within ${timeoutMs / 1000} s from: ${waiting.joinToString { it.label }}"
            }
            Thread.sleep(200)
        }
    }

    /** Glance can push more than once (the data flow and the refresh); wait until it's quiet for 1.5 s. */
    private fun settle(placed: List<Placed>) {
        val end = SystemClock.uptimeMillis() + 10_000
        var last = placed.sumOf { it.renders() }
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < end && SystemClock.uptimeMillis() - quietSince < 1_500) {
            Thread.sleep(100)
            val now = placed.sumOf { it.renders() }
            if (now != last) {
                last = now
                quietSince = SystemClock.uptimeMillis()
            }
        }
    }

    /** Glance shows its error layout when a widget's composition throws. */
    private fun checkNoErrorLayout(placed: List<Placed>, theme: String): String? {
        @Suppress("DiscouragedApi")
        val errorLayout = context.resources.getIdentifier("glance_error_layout", "layout", context.packageName)
        if (errorLayout == 0) return null
        val broken = placed.filter { it.view?.lastLayoutId == errorLayout }
        return if (broken.isEmpty()) null else "$theme: Glance's error layout is showing for ${broken.joinToString { it.label }} (see logcat)"
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun shell(command: String): String {
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
    }

    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(800)
        shell("mkdir -p $SCREENS")
        shell("screencap -p $SCREENS/${size()}-$name.png")
    }

    private fun size(): String {
        val dm = context.resources.displayMetrics
        return if (dm.widthPixels / dm.density >= 600) "inner" else "cover"
    }

    companion object {
        const val SCREENS = ScreensTest.SCREENS
        private const val TAG = "WidgetsTest"
        private const val HOST_ID = 0x5EED
        private const val SQUARE = 150
        private const val RECT_W = 320
        private const val RECT_H = 150
        private const val GAP = 12
        private const val PAD = 16
        private const val CAPTION = 20
        private val WALL_TOP = 0xFF4E6A7A.toInt()
        private val WALL_BOTTOM = 0xFF9FB0B8.toInt()
    }
}
