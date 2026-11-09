package com.codebyramiz.blelab.ui.history

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.recording.RecordingSession

/** "Nov 9, 22:44 · 12.0 s" for a closed session, "Not closed" in place of the duration for an open one. */
@Composable
fun sessionSummary(session: RecordingSession): String {
    val context = LocalContext.current
    val started = DateUtils.formatDateTime(
        context,
        session.startedAtMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val duration = session.durationMillis?.let { stringResource(R.string.session_duration_seconds, it / MILLIS_PER_SECOND) }
        ?: stringResource(R.string.history_open_session)
    return stringResource(R.string.session_summary, started, duration)
}

private const val MILLIS_PER_SECOND = 1000f
