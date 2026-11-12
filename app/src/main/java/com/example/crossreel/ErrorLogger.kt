package com.example.crossreel

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.media3.transformer.ExportException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva i dettagli degli errori di generazione in un file di testo sul telefono, per poterli studiare dopo.
 * File: Android/data/<package>/files/Documents/orangebeat_errors.txt  (accessibile anche via `adb pull`)
 */
object ErrorLogger {
    private const val TAG = "OrangeBeatError"
    private const val FILE_NAME = "orangebeat_errors.txt"
    private const val MAX_FILE_BYTES = 512 * 1024L

    fun log(
        context: Context,
        error: Throwable,
        settings: String,
        editPlanJson: String? = null
    ) {
        Log.e(TAG, "Errore di generazione", error)
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, FILE_NAME)

            // Evita che il file cresca all'infinito: se troppo grande lo azzeriamo
            if (file.exists() && file.length() > MAX_FILE_BYTES) file.delete()

            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.appendLine("==================== $time ====================")
            sb.appendLine("App: ${BuildConfig.VERSION_NAME}")
            sb.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            sb.appendLine("Impostazioni: $settings")
            sb.appendLine("Spazio libero: ${dir.usableSpace / (1024 * 1024)} MB")
            sb.appendLine()
            if (error is ExportException) {
                sb.appendLine("ExportException codice: ${error.errorCode} (${error.errorCodeName})")
            }
            sb.appendLine("Errore: ${error::class.java.name}: ${error.message}")
            sb.appendLine(Log.getStackTraceString(error))
            sb.appendLine("--- Piano di montaggio (EDL) ---")
            sb.appendLine(editPlanJson ?: "(non ancora generato)")
            sb.appendLine()
            file.appendText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile scrivere il log errori", e)
        }
    }
}
