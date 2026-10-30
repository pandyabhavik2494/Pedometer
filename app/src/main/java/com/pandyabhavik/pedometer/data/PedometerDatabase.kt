package com.pandyabhavik.pedometer.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PhoneSlot::class, SourceSlot::class, HeartSlot::class, FloorSlot::class, Session::class,
        MergedSlot::class, DaySummary::class, WrittenRecord::class, EarnedBadge::class, KnownSource::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PedometerDatabase : RoomDatabase() {
    abstract fun steps(): StepDao
}
