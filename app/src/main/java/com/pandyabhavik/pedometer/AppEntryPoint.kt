package com.pandyabhavik.pedometer

import android.content.Context
import com.pandyabhavik.pedometer.data.SettingsStore
import com.pandyabhavik.pedometer.data.StepDao
import com.pandyabhavik.pedometer.data.StepRepository
import com.pandyabhavik.pedometer.health.SyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** For places Hilt can't inject into directly: widgets and device tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun dao(): StepDao
    fun database(): com.pandyabhavik.pedometer.data.PedometerDatabase
    fun repository(): StepRepository
    fun settings(): SettingsStore
    fun syncEngine(): SyncEngine

    companion object {
        fun get(context: Context): AppEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
    }
}
