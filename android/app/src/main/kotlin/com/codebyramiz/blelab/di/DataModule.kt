package com.codebyramiz.blelab.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.codebyramiz.blelab.data.RecordingDatabase
import com.codebyramiz.blelab.data.RoomRecordingRepository
import com.codebyramiz.blelab.recording.Clock
import com.codebyramiz.blelab.recording.RecordingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Local persistence for recorded sessions. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): RecordingDatabase = Room.databaseBuilder<RecordingDatabase>(context, "recordings.db")
        .setDriver(AndroidSQLiteDriver())
        .build()

    @Provides
    @Singleton
    fun repository(database: RecordingDatabase): RecordingRepository = RoomRecordingRepository(database.recordingDao())

    @Provides
    fun clock(): Clock = Clock { System.currentTimeMillis() }
}
