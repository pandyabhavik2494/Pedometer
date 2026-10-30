package com.pandyabhavik.pedometer.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.sensor.StepService
import com.pandyabhavik.pedometer.ui.theme.LocalPalette

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    TODAY("Today", Icons.Filled.DonutLarge, Icons.Outlined.DonutLarge),
    HISTORY("History", Icons.Filled.Insights, Icons.Outlined.Insights),
    AWARDS("Awards", Icons.Filled.EmojiEvents, Icons.Outlined.EmojiEvents),
    SETTINGS("Settings", Icons.Filled.Tune, Icons.Outlined.Tune),
}

/** Below this width (folded, or a narrow split) the app uses one column and a bottom bar. */
val WIDE_FROM = 600.dp

@Composable
fun AppRoot(main: MainViewModel, days: DaysViewModel) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!s.onboarded) {
            Onboarding(main)
            return@Surface
        }
        var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_FROM
            val content: @Composable () -> Unit = {
                when (tab) {
                    Tab.TODAY -> TodayScreen(main, days, wide, onFixHealth = { tab = Tab.SETTINGS })
                    Tab.HISTORY -> HistoryScreen(main, days, wide)
                    Tab.AWARDS -> AwardsScreen(days, wide)
                    Tab.SETTINGS -> SettingsScreen(main, wide)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().systemBarsPadding()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(if (tab == t) t.on else t.off, null) },
                                label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier.padding(vertical = 4.dp).testTag("nav-${t.name.lowercase()}"),
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) { content() }
                }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(if (tab == t) t.on else t.off, null) },
                                    label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                                    modifier = Modifier.testTag("nav-${t.name.lowercase()}"),
                                )
                            }
                        }
                    },
                ) { padding ->
                    // Scaffold's padding already includes the status bar (no top bar here); adding
                    // statusBarsPadding on top of it doubled the gap above every screen.
                    Box(Modifier.fillMaxSize().padding(padding)) { content() }
                }
            }
        }
    }
}

/** Problems worth fixing, shown on Today: no motion permission, or no step sensor. */
@Composable
fun StatusBanners(main: MainViewModel) {
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
    // Held as state so the banner goes away as soon as the permission is granted, here or in system settings.
    var permitted by remember { mutableStateOf(check()) }
    LifecycleResumeEffect(Unit) {
        permitted = check()
        onPauseOrDispose { }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permitted = granted || check()
        main.onAppVisible()
    }
    val sensor = remember { StepService.hasSensor(context) }
    val message = when {
        !sensor -> "This device has no step sensor, so only Health Connect steps are shown."
        !permitted -> "Motion permission is off, so this phone isn't counting steps."
        else -> null
    } ?: return
    Panel(Modifier.fillMaxWidth().testTag("status-banner"), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(36.dp).padding(end = 0.dp)) {
                Surface(Modifier.fillMaxSize(), color = LocalPalette.current.highlight) {}
            }
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (sensor && !permitted) {
                TextButton(onClick = { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow") }
            }
        }
    }
}
