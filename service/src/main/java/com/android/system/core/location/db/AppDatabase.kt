package com.android.system.core.location.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LocationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
}
