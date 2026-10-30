package com.pandyabhavik.pedometer.di

import android.content.Context
import androidx.room.Room
import com.pandyabhavik.pedometer.data.PedometerDatabase
import com.pandyabhavik.pedometer.data.StepDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PedometerDatabase =
        Room.databaseBuilder(context, PedometerDatabase::class.java, "pedometer.db").build()

    @Provides
    fun stepDao(db: PedometerDatabase): StepDao = db.steps()
}
