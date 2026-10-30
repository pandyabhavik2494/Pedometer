package com.pandyabhavik.pedometer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pandyabhavik.pedometer.core.CounterCheckpoint
import com.pandyabhavik.pedometer.core.SourcePreference
import com.pandyabhavik.pedometer.core.Units
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, GRAYSCALE }

data class AppSettings(
    val goal: Long = 10_000,
    val units: Units = Units.METRIC,
    /** Set by hand, or null to use the height-based estimate. */
    val strideMeters: Double? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val preference: SourcePreference = SourcePreference.PREFER_WEARABLE,
    val reminderOn: Boolean = true,
    val reminderMinuteOfDay: Int = 19 * 60,
    /** Remind only when under this share of the goal. 100 = whenever behind. */
    val reminderBelowPercent: Int = 100,
    val writeToHealthConnect: Boolean = true,
    val settleMinutes: Int = 60,
    val syncMinutes: Int = 30,
    val onboarded: Boolean = false,
) {
    /** Stride in use: set by hand, else from height, else a typical adult stride. */
    val stride: Double get() = strideMeters ?: heightCm?.let { it * 0.00414 } ?: 0.76
    val weight: Double get() = weightKg ?: 70.0
}

data class SyncState(val lastSync: Long? = null, val lastError: String? = null)

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.store

    private object K {
        val goal = longPreferencesKey("goal")
        val units = stringPreferencesKey("units")
        val stride = doublePreferencesKey("stride")
        val height = doublePreferencesKey("height")
        val weight = doublePreferencesKey("weight")
        val theme = stringPreferencesKey("theme")
        val preference = stringPreferencesKey("preference")
        val reminderOn = booleanPreferencesKey("reminder_on")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val reminderBelow = intPreferencesKey("reminder_below")
        val write = booleanPreferencesKey("write_hc")
        val settle = intPreferencesKey("settle_minutes")
        val sync = intPreferencesKey("sync_minutes")
        val onboarded = booleanPreferencesKey("onboarded")
        val lastSync = longPreferencesKey("last_sync")
        val lastError = stringPreferencesKey("last_error")
        val cpCounter = longPreferencesKey("cp_counter")
        val cpAt = longPreferencesKey("cp_at")
        val cpBoot = intPreferencesKey("cp_boot")
    }

    val settings: Flow<AppSettings> = store.data.map { currentFrom(it) }

    val sync: Flow<SyncState> = store.data.map { SyncState(it[K.lastSync], it[K.lastError]) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val now = change(currentFrom(p))
            p[K.goal] = now.goal
            p[K.units] = now.units.name
            if (now.strideMeters != null) p[K.stride] = now.strideMeters else p.remove(K.stride)
            if (now.heightCm != null) p[K.height] = now.heightCm else p.remove(K.height)
            if (now.weightKg != null) p[K.weight] = now.weightKg else p.remove(K.weight)
            p[K.theme] = now.theme.name
            p[K.preference] = now.preference.name
            p[K.reminderOn] = now.reminderOn
            p[K.reminderMinute] = now.reminderMinuteOfDay
            p[K.reminderBelow] = now.reminderBelowPercent
            p[K.write] = now.writeToHealthConnect
            p[K.settle] = now.settleMinutes
            p[K.sync] = now.syncMinutes
            p[K.onboarded] = now.onboarded
        }
    }

    private fun currentFrom(p: Preferences) = AppSettings(
        goal = p[K.goal] ?: 10_000,
        units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: Units.METRIC,
        strideMeters = p[K.stride],
        heightCm = p[K.height],
        weightKg = p[K.weight],
        theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        preference = p[K.preference]?.let { runCatching { SourcePreference.valueOf(it) }.getOrNull() }
            ?: SourcePreference.PREFER_WEARABLE,
        reminderOn = p[K.reminderOn] ?: true,
        reminderMinuteOfDay = p[K.reminderMinute] ?: (19 * 60),
        reminderBelowPercent = p[K.reminderBelow] ?: 100,
        writeToHealthConnect = p[K.write] ?: true,
        settleMinutes = p[K.settle] ?: 60,
        syncMinutes = p[K.sync] ?: 30,
        onboarded = p[K.onboarded] ?: false,
    )

    suspend fun recordSync(at: Long?, error: String?) {
        store.edit { p ->
            if (at != null) p[K.lastSync] = at
            if (error != null) p[K.lastError] = error else p.remove(K.lastError)
        }
    }

    suspend fun checkpoint(): CounterCheckpoint? {
        val p = store.data.first()
        val counter = p[K.cpCounter] ?: return null
        return CounterCheckpoint(counter, p[K.cpAt] ?: 0, p[K.cpBoot] ?: 0)
    }

    suspend fun saveCheckpoint(cp: CounterCheckpoint) {
        store.edit {
            it[K.cpCounter] = cp.counter
            it[K.cpAt] = cp.atMillis
            it[K.cpBoot] = cp.bootCount
        }
    }
}
