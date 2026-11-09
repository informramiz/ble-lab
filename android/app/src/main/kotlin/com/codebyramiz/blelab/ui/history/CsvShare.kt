package com.codebyramiz.blelab.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes a CSV into the app cache and hands it to the system share sheet through a FileProvider. */
object CsvShare {
    const val DIRECTORY = "exports"

    suspend fun share(context: Context, fileName: String, csv: String) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            val file = File(dir, fileName).apply { writeText(csv) }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }
}
