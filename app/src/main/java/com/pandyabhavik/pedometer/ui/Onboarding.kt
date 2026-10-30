package com.pandyabhavik.pedometer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.health.HealthConnectGateway
import com.pandyabhavik.pedometer.health.HealthConnectStatus

/** First run: what the app does, the permissions it needs and why, and the goal. */
@Composable
fun Onboarding(main: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var goal by rememberSaveable { mutableLongStateOf(10_000L) }
    val context = LocalContext.current
    val health by main.health.collectAsStateWithLifecycle()

    // Each result moves to the page after the one that asked, rather than step++: a double tap or a
    // late result must not skip a page or run past the last one.
    val last = 5
    fun after(page: Int) {
        step = maxOf(step, page + 1).coerceAtMost(last)
    }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { after(1) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { after(2) }
    val hc = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        main.refreshHealth()
        after(3)
    }

    val pages = listOf<@Composable () -> Unit>(
        {
            Page(
                "Every step, counted once",
                "Pedometer counts steps with your phone and adds your Fitbit Air through Health Connect. Where both counted the same walk, it only counts it once.",
                "Get started", { after(0) },
            )
        },
        {
            Page(
                "Motion",
                "To count steps, the app needs permission to use your phone's step sensor. It only counts; it never tracks where you go.",
                "Allow motion", { activity.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                skip = { after(1) },
            )
        },
        {
            if (Build.VERSION.SDK_INT >= 33) {
                Page(
                    "Notifications",
                    "A quiet notification shows today's steps while the app counts, and an evening nudge comes when you're behind. You can turn the nudge off in Settings.",
                    "Allow notifications", { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    skip = { after(2) },
                )
            } else {
                LaunchedEffect(Unit) { after(2) }
            }
        },
        {
            when (health.status) {
                HealthConnectStatus.AVAILABLE -> Page(
                    "Fitbit, through Health Connect",
                    "Allow reading steps, distance, floors, workouts and heart rate so your Fitbit Air's data counts, and writing steps so the phone's count reaches Google Health when the band isn't on. Turn on background access and past data too.",
                    "Connect Health Connect", {
                        // health.status starts as AVAILABLE until the first check finishes; launching the
                        // permission contract without Health Connect throws ActivityNotFoundException.
                        if (main.gateway.status() == HealthConnectStatus.AVAILABLE) {
                            runCatching { hc.launch(HealthConnectGateway.ALL) }.onFailure { main.refreshHealth() }
                        } else {
                            main.refreshHealth()
                        }
                    },
                    skip = { after(3) },
                )
                else -> Page(
                    "Health Connect isn't ready",
                    "Health Connect is how your Fitbit Air's steps reach this app. Install or update it, then come back. You can also skip this and use the phone's count only.",
                    "Get Health Connect", { runCatching { context.startActivity(main.gateway.installIntent()) } },
                    skip = { after(3) },
                )
            }
        },
        {
            Page(
                "Keep counting all day",
                "Samsung phones pause apps to save battery. Let Pedometer run unrestricted so steps are counted even when you haven't opened it. It uses the low-power step chip, not GPS.",
                "Allow", {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                    after(4)
                },
                skip = { after(4) },
            )
        },
        {
            Page(
                "Your daily goal",
                "10,000 is a good start. You can change it any time.",
                "Start counting", {
                    main.update { it.copy(goal = goal) }
                    main.finishOnboarding()
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    FilledTonalIconButton(onClick = { goal = (goal - 500).coerceAtLeast(1_000) }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.Remove, "Less")
                    }
                    Text(
                        Format.steps(goal),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.width(200.dp).testTag("goal-value"),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    FilledTonalIconButton(onClick = { goal = (goal + 500).coerceAtMost(50_000) }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.Add, "More")
                    }
                }
            }
        },
    )

    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DayRing(
                steps = (goal * (step.coerceIn(0, pages.size - 1) + 1) / pages.size), goal = goal,
                hourly = List(24) { h -> if (h in 7..20) ((h * 37) % 9 + 2).toLong() else 0L },
                currentHour = null,
                modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth(0.6f).aspectRatio(1f),
                numberStyle = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(28.dp))
            pages[step.coerceIn(0, pages.size - 1)]()
            Spacer(Modifier.height(20.dp))
            Text(
                "${step.coerceAtMost(pages.size - 1) + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Page(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    skip: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (extra != null) {
            Spacer(Modifier.height(24.dp))
            extra()
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onboarding-action")) { Text(action) }
        if (skip != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = skip, modifier = Modifier.testTag("onboarding-skip")) { Text("Not now") }
        }
    }
}
