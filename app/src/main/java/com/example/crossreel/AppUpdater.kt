package com.example.crossreel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestore aggiornamenti OTA tramite GitHub Releases (NewtonH725/OrangeBeat):
 * - Controlla automaticamente all'avvio se esiste una release più recente di BuildConfig.VERSION_NAME.
 * - Scarica automaticamente in background l'APK versionato (es. OrangeBeat-v2.4.0.apk).
 * - Espone i metadati per il popup di conferma e lancia l'installazione tramite FileProvider.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/NewtonH725/OrangeBeat/releases/latest"
    private const val MIN_VALID_APK_BYTES = 5L * 1024L * 1024L // 5 MB

    data class UpdateInfo(
        val versionName: String,
        val apkFileName: String,
        val apkFile: File,
        val releaseNotes: String
    )

    suspend fun checkAndDownloadUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        cleanupOldUpdates(context)

        try {
            val releaseJson = fetchLatestReleaseJson() ?: return@withContext null
            val tagName = releaseJson.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return@withContext null

            val normalizedLatest = normalizeVersion(tagName)
            val normalizedCurrent = normalizeVersion(BuildConfig.VERSION_NAME)

            if (!isNewerVersion(normalizedLatest, normalizedCurrent)) {
                Log.i(TAG, "App già aggiornata ($normalizedCurrent >= $normalizedLatest)")
                return@withContext null
            }

            val assets = releaseJson.optJSONArray("assets") ?: return@withContext null
            var downloadUrl: String? = null
            var assetName: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                    downloadUrl = url
                    assetName = name
                    if (name.contains(normalizedLatest, ignoreCase = true)) {
                        break
                    }
                }
            }

            if (downloadUrl == null || assetName == null) {
                Log.w(TAG, "Nessun asset APK trovato nella release $normalizedLatest")
                return@withContext null
            }

            // Garantisce che il nome del file locale contenga sempre il numero di versione
            val finalFileName = if (assetName.contains(normalizedLatest, ignoreCase = true)) {
                assetName
            } else {
                "OrangeBeat-$normalizedLatest.apk"
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetApk = File(updatesDir, finalFileName)

            if (!targetApk.exists() || targetApk.length() < MIN_VALID_APK_BYTES) {
                Log.i(TAG, "Download aggiornamento $finalFileName da $downloadUrl...")
                val downloaded = downloadFileAtomically(downloadUrl, targetApk)
                if (!downloaded) {
                    Log.w(TAG, "Download APK fallito per $finalFileName")
                    return@withContext null
                }
            } else {
                Log.i(TAG, "APK $finalFileName già scaricato in cache (${targetApk.length()} bytes)")
            }

            val notes = releaseJson.optString("body", "").trim()
            return@withContext UpdateInfo(
                versionName = normalizedLatest,
                apkFileName = finalFileName,
                apkFile = targetApk,
                releaseNotes = notes
            )
        } catch (e: Exception) {
            Log.w(TAG, "Controllo aggiornamenti non riuscito: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Avvia l'installer di sistema per l'APK scaricato.
     * Restituisce true se l'installer è stato avviato, false se è stato necessario aprire
     * le impostazioni per autorizzare l'installazione da sorgenti sconosciute.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    private fun fetchLatestReleaseJson(): JSONObject? {
        val url = URL(RELEASES_LATEST_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            useCaches = false
            connectTimeout = 5000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OrangeBeat-Android-Client")
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFileAtomically(downloadUrl: String, targetFile: File): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 60000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OrangeBeat-Android-Client")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() >= MIN_VALID_APK_BYTES) {
                    if (targetFile.exists()) targetFile.delete()
                    return tempFile.renameTo(targetFile)
                }
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Errore download APK: ${e.message}")
            return false
        } finally {
            connection?.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun cleanupOldUpdates(context: Context) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) return
        val currentVer = normalizeVersion(BuildConfig.VERSION_NAME)
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            } else if (file.name.endsWith(".apk") && !file.name.contains(currentVer)) {
                // Mantiene solo eventuali APK di versioni strettamente superiori alla corrente
                val match = Regex("""v?\d+\.\d+\.\d+""").find(file.name)?.value
                if (match == null || !isNewerVersion(normalizeVersion(match), currentVer)) {
                    file.delete()
                }
            }
        }
    }

    private fun normalizeVersion(raw: String): String {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        return "v$cleaned"
    }

    private fun parseVersionTuple(version: String): Triple<Int, Int, Int> {
        val parts = version.removePrefix("v").removePrefix("V").split(".")
        val major = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    private fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = parseVersionTuple(candidate)
        val cur = parseVersionTuple(current)
        if (c.first != cur.first) return c.first > cur.first
        if (c.second != cur.second) return c.second > cur.second
        return c.third > cur.third
    }
}
