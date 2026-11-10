package com.codebyramiz.blelab.data

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(entities = [SessionEntity::class, TelemetryEntity::class, MotionEntity::class], version = 1, exportSchema = false)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}
