package com.pandyabhavik.pedometer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.work.Schedules
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PedometerApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val s = settings.current()
            if (s.onboarded) {
                Schedules.scheduleSync(this@PedometerApp, s.syncMinutes)
                Schedules.scheduleReminder(this@PedometerApp, s.reminderMinuteOfDay)
            }
        }
    }
}
