package com.pandyabhavik.pedometer.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pandyabhavik.pedometer.PrivacyPolicyActivity
import com.pandyabhavik.pedometer.core.Format
import com.pandyabhavik.pedometer.core.SourcePreference
import com.pandyabhavik.pedometer.core.SourceRole
import com.pandyabhavik.pedometer.core.Units
import com.pandyabhavik.pedometer.data.AppSettings
import com.pandyabhavik.pedometer.data.ThemeMode
import com.pandyabhavik.pedometer.health.HealthConnectGateway
import com.pandyabhavik.pedometer.health.HealthConnectStatus
import com.pandyabhavik.pedometer.ui.theme.LocalPalette
import java.time.LocalDate
import kotlin.math.roundToInt

private enum class Edit { GOAL, UNITS, STRIDE, HEIGHT, WEIGHT, THEME, PREFERENCE, REMINDER_TIME, REMINDER_BELOW, SETTLE, SYNC }

private fun preferenceLabel(p: SourcePreference) = when (p) {
    SourcePreference.PREFER_WEARABLE -> "Prefer Fitbit, fall back to phone"
    SourcePreference.PREFER_PHONE -> "Prefer phone, fall back to Fitbit"
    SourcePreference.HIGHEST -> "Whichever counted more"
}

private fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.SYSTEM -> "Match system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.GRAYSCALE -> "Grayscale (black and white)"
}

private fun roleLabel(r: SourceRole) = when (r) {
    SourceRole.WEARABLE -> "Wearable"
    SourceRole.OTHER -> "Fill gaps only"
    SourceRole.IGNORED -> "Ignore"
}

private fun time(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(main: MainViewModel, wide: Boolean) {
    val settings by main.settings.collectAsStateWithLifecycle()
    val health by main.health.collectAsStateWithLifecycle()
    val sources by main.sources.collectAsStateWithLifecycle()
    val s = settings ?: return
    val context = LocalContext.current
    var edit by rememberSaveable { mutableStateOf<Edit?>(null) }
    var roleFor by rememberSaveable { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        main.refreshHealth()
        main.syncNow()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) main.export(uri) { ok ->
            Toast.makeText(context, if (ok) "Exported" else "Couldn't export", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            Group("Goal and you") {
                Item("Daily goal", "${Format.steps(s.goal)} steps", Modifier.testTag("setting-goal")) { edit = Edit.GOAL }
                Item("Units", unitsLabel(s.units)) { edit = Edit.UNITS }
                Item("Stride length", Format.stride(s.stride, s.units) + if (s.strideMeters == null) " (estimated)" else "") { edit = Edit.STRIDE }
                Item("Height", s.heightCm?.let { heightText(it, s.units) } ?: "Not set") { edit = Edit.HEIGHT }
                Item("Weight", s.weightKg?.let { weightText(it, s.units) } ?: "Not set, calories assume 70 kg") { edit = Edit.WEIGHT }
            }
            Group("Look") {
                Item("Theme", themeLabel(s.theme), Modifier.testTag("setting-theme")) { edit = Edit.THEME }
            }
            Group("Fitbit and Health Connect") {
                val hcText = when (health.status) {
                    HealthConnectStatus.NOT_INSTALLED -> "Not installed. Tap to install."
                    HealthConnectStatus.NEEDS_UPDATE -> "Needs an update. Tap to update."
                    HealthConnectStatus.AVAILABLE -> if (health.missing.isEmpty()) "Connected, all permissions on"
                    else if (health.canRead) "Connected, ${health.missing.size} permission(s) off. Tap to review."
                    else "Not connected. Tap to allow."
                }
                Item("Health Connect", hcText) {
                    when (health.status) {
                        HealthConnectStatus.AVAILABLE -> permissions.launch(HealthConnectGateway.ALL)
                        else -> runCatching { context.startActivity(main.gateway.installIntent()) }
                    }
                }
                if (health.status == HealthConnectStatus.AVAILABLE) {
                    Item("Open Health Connect", "See or delete the data this app wrote") {
                        runCatching { context.startActivity(main.gateway.settingsIntent()) }
                    }
                }
                Item("Which count wins", preferenceLabel(s.preference)) { edit = Edit.PREFERENCE }
                sources.forEach { src ->
                    val role = runCatching { SourceRole.valueOf(src.role) }.getOrDefault(SourceRole.OTHER)
                    Item(SourceNames.label(context, src.origin), "Treated as: ${roleLabel(role)}") { roleFor = src.origin }
                }
                Toggle("Write phone steps to Health Connect", "Only when the Fitbit wasn't worn, so Google Health shows them", s.writeToHealthConnect) { on ->
                    main.update { it.copy(writeToHealthConnect = on) }
                }
                Item("Wait before writing", "${s.settleMinutes} minutes, so a late Fitbit sync goes first") { edit = Edit.SETTLE }
                Item("Background sync", "Every ${s.syncMinutes} minutes") { edit = Edit.SYNC }
            }
            Group("Evening reminder") {
                Toggle("Remind me when I'm behind", null, s.reminderOn) { on -> main.update { it.copy(reminderOn = on) } }
                Item("Time", time(s.reminderMinuteOfDay)) { edit = Edit.REMINDER_TIME }
                Item("Only when under", "${s.reminderBelowPercent}% of my goal") { edit = Edit.REMINDER_BELOW }
            }
            Group("Counting in the background") {
                val pm = context.getSystemService(PowerManager::class.java)
                val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                Item(
                    "Battery",
                    if (exempt) "Unrestricted. Counting won't be paused." else "Optimised. Tap to let the app count all day.",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            }
            Group("Your data") {
                Item("Export to CSV", "One row per day") { export.launch("steps-${LocalDate.now()}.csv") }
                Item("Privacy policy", "What the app reads, writes and keeps") {
                    context.startActivity(Intent(context, PrivacyPolicyActivity::class.java))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (edit) {
        Edit.GOAL -> NumberDialog("Daily goal", "steps", s.goal.toDouble(), 0, { edit = null }) { v ->
            v.roundToInt().takeIf { it in 500..100_000 }?.let { g -> main.update { it.copy(goal = g.toLong()) } }
        }
        Edit.UNITS -> ChoiceDialog("Units", Units.entries, s.units, ::unitsLabel, { edit = null }) { u -> main.update { it.copy(units = u) } }
        Edit.STRIDE -> {
            val metric = s.units == Units.METRIC
            NumberDialog(
                "Stride length", if (metric) "cm" else "in", if (metric) s.stride * 100 else s.stride / 0.0254, 0, { edit = null },
                note = "Leave empty to estimate it from your height.",
                allowEmpty = true,
            ) { v ->
                val meters = if (v <= 0) null else if (metric) v / 100 else v * 0.0254
                if (meters == null || meters in 0.3..1.5) main.update { it.copy(strideMeters = meters) }
            }
        }
        Edit.HEIGHT -> {
            val metric = s.units == Units.METRIC
            NumberDialog("Height", if (metric) "cm" else "in", s.heightCm?.let { if (metric) it else it / 2.54 } ?: 0.0, 0, { edit = null }, allowEmpty = true) { v ->
                val cm = if (v <= 0) null else if (metric) v else v * 2.54
                if (cm == null || cm in 100.0..250.0) main.update { it.copy(heightCm = cm) }
            }
        }
        Edit.WEIGHT -> {
            val metric = s.units == Units.METRIC
            NumberDialog("Weight", if (metric) "kg" else "lb", s.weightKg?.let { if (metric) it else it / 0.453592 } ?: 0.0, 1, { edit = null }, allowEmpty = true) { v ->
                val kg = if (v <= 0) null else if (metric) v else v * 0.453592
                if (kg == null || kg in 25.0..300.0) main.update { it.copy(weightKg = kg) }
            }
        }
        Edit.THEME -> ChoiceDialog("Theme", ThemeMode.entries, s.theme, ::themeLabel, { edit = null }) { t -> main.update { it.copy(theme = t) } }
        Edit.PREFERENCE -> ChoiceDialog("Which count wins", SourcePreference.entries, s.preference, ::preferenceLabel, { edit = null }) { p ->
            main.update { it.copy(preference = p) }
            main.recomputeAll()
        }
        Edit.REMINDER_BELOW -> ChoiceDialog("Remind only when under", listOf(100, 90, 75, 50), s.reminderBelowPercent, { "$it% of my goal" }, { edit = null }) { p ->
            main.update { it.copy(reminderBelowPercent = p) }
        }
        Edit.SETTLE -> ChoiceDialog("Wait before writing", listOf(30, 60, 120, 240), s.settleMinutes, { "$it minutes" }, { edit = null }) { m ->
            main.update { it.copy(settleMinutes = m) }
        }
        Edit.SYNC -> ChoiceDialog("Background sync", listOf(15, 30, 60, 120), s.syncMinutes, { "Every $it minutes" }, { edit = null }) { m ->
            main.update { it.copy(syncMinutes = m) }
        }
        Edit.REMINDER_TIME -> {
            val state = rememberTimePickerState(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { edit = null },
                confirmButton = {
                    TextButton(onClick = {
                        main.update { it.copy(reminderMinuteOfDay = state.hour * 60 + state.minute) }
                        edit = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } },
                title = { Text("Reminder time") },
                text = { TimePicker(state) },
            )
        }
        null -> Unit
    }
    roleFor?.let { origin ->
        val current = sources.firstOrNull { it.origin == origin }?.role?.let { runCatching { SourceRole.valueOf(it) }.getOrNull() } ?: SourceRole.OTHER
        ChoiceDialog(SourceNames.label(context, origin), SourceRole.entries, current, ::roleLabel, { roleFor = null }) { r ->
            main.setRole(origin, r)
        }
    }
}

private fun heightText(cm: Double, u: Units) = if (u == Units.METRIC) "${cm.roundToInt()} cm" else {
    val inches = (cm / 2.54).roundToInt()
    "${inches / 12} ft ${inches % 12} in"
}

private fun weightText(kg: Double, u: Units) = if (u == Units.METRIC) "%.1f kg".format(kg) else "${(kg / 0.453592).roundToInt()} lb"

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    SectionLabel(title, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
    Panel(Modifier.fillMaxWidth(), padding = 0.dp) { content() }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun Item(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = LocalPalette.current.hairline)
    }
}

@Composable
private fun Toggle(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = onChange)
        }
        HorizontalDivider(color = LocalPalette.current.hairline)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(option == selected, role = Role.RadioButton) { onPick(option); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    unit: String,
    initial: Double,
    decimals: Int,
    onDismiss: () -> Unit,
    note: String? = null,
    allowEmpty: Boolean = false,
    onSave: (Double) -> Unit,
) {
    val start = if (initial <= 0) "" else if (decimals == 0) initial.roundToInt().toString() else "%.${decimals}f".format(initial)
    var text by remember { mutableStateOf(start) }
    val value = text.replace(',', '.').toDoubleOrNull()
    val valid = value != null && value > 0 || (allowEmpty && text.isBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(value ?: 0.0); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                    suffix = { Text(unit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("number-field"),
                )
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
