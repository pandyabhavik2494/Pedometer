package com.pandyabhavik.pedometer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    /** The channel of the old always-on counting notification, removed now that counting needs none. */
    private const val OLD_COUNTING_CHANNEL = "counting"
    const val REMINDER_CHANNEL = "reminder"
    const val BADGE_CHANNEL = "badges"
    const val REMINDER_ID = 2
    const val BADGE_ID = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_COUNTING_CHANNEL)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(REMINDER_CHANNEL, "Evening reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A nudge in the evening when you're behind your goal"
                },
                NotificationChannel(BADGE_CHANNEL, "Badges", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "When you earn a new badge"
                },
            ),
        )
    }
}
