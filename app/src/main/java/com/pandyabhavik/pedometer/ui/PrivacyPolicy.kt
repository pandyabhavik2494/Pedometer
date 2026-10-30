package com.pandyabhavik.pedometer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val sections = listOf(
    "What the app reads" to "Steps from your phone's step sensor. From Health Connect, with your permission: steps, distance, floors climbed, workouts and heart rate, from any app that writes them (such as Fitbit through Google Health).",
    "What the app writes" to "Only steps counted by this phone's own sensor, and only for times your wearable has no data. They are written to Health Connect so other apps, such as Google Health, can show them. Steps that came from another app are never written back.",
    "Where your data lives" to "Everything stays on this phone, in the app's own storage and in Health Connect. The app has no account, no servers and no internet access, and it shares nothing with anyone.",
    "Why it reads in the background" to "So totals, widgets and the evening reminder stay current without opening the app.",
    "Your control" to "You can turn off any permission in Health Connect at any time, delete the steps this app wrote from Health Connect's settings, export your history to CSV, or uninstall the app to remove its own copy.",
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Text("Privacy policy", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.height(12.dp))
                sections.forEach { (title, body) ->
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
