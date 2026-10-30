package com.pandyabhavik.pedometer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val COUNTING_CHANNEL = "counting"
    const val REMINDER_CHANNEL = "reminder"
    const val BADGE_CHANNEL = "badges"
    const val COUNTING_ID = 1
    const val REMINDER_ID = 2
    const val BADGE_ID = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(COUNTING_CHANNEL, "Step counting", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Today's steps while the app counts in the background"
                    setShowBadge(false)
                },
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
