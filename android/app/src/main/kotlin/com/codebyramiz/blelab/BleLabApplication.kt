package com.codebyramiz.blelab

import android.app.Application
import com.codebyramiz.blelab.recording.RecordingRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BleLabApplication : Application() {
    @Inject lateinit var recordings: RecordingRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // A recording cut short by a process kill is still open; close it before the UI shows "Not closed".
        appScope.launch { recordings.closeOrphans() }
    }
}
