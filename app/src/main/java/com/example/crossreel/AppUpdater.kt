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

/**
 * Gestore aggiornamenti OTA Android tramite GitHub Releases (NewtonH725/OrangeBeat):
 * - Controlla automaticamente all'avvio se esiste una release più recente di BuildConfig.VERSION_NAME.
 * - Scarica automaticamente in background l'APK versionato (es. OrangeBeat-v3.0.0-android.apk).
 * - Espone i metadati per il popup di conferma e lancia l'installazione tramite FileProvider.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val USER_AGENT = "OrangeBeat-Android-Client"
    private const val MIN_VALID_APK_BYTES = 5L * 1024L * 1024L // 5 MB

    data class UpdateInfo(
        val versionName: String,
        val apkFileName: String,
        val apkFile: File,
        val releaseNotes: String
    )

    suspend fun checkAndDownloadUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        ReleaseUpdaterCore.cleanupOldUpdates(
            updatesDir = updatesDir,
            currentVersion = BuildConfig.VERSION_NAME,
            extensions = TargetPlatform.ANDROID.primaryExtensions
        )

        try {
            val rawJson = ReleaseUpdaterCore.fetchLatestReleaseJsonText(USER_AGENT) ?: return@withContext null
            val releaseJson = JSONObject(rawJson)
            val tagName = releaseJson.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return@withContext null

            val normalizedLatest = ReleaseUpdaterCore.normalizeVersion(tagName)
            val normalizedCurrent = ReleaseUpdaterCore.normalizeVersion(BuildConfig.VERSION_NAME)

            if (!ReleaseUpdaterCore.isNewerVersion(normalizedLatest, normalizedCurrent)) {
                Log.i(TAG, "App già aggiornata ($normalizedCurrent >= $normalizedLatest)")
                return@withContext null
            }

            val assetsJson = releaseJson.optJSONArray("assets") ?: return@withContext null
            val assets = buildList {
                for (i in 0 until assetsJson.length()) {
                    val obj = assetsJson.optJSONObject(i) ?: continue
                    add(
                        GitHubReleaseAsset(
                            name = obj.optString("name", ""),
                            downloadUrl = obj.optString("browser_download_url", ""),
                            sizeBytes = obj.optLong("size", 0L)
                        )
                    )
                }
            }

            val selectedAsset = ReleaseUpdaterCore.selectAssetForPlatform(
                assets = assets,
                platform = TargetPlatform.ANDROID,
                normalizedVersion = normalizedLatest
            ) ?: run {
                Log.w(TAG, "Nessun asset APK trovato nella release $normalizedLatest")
                return@withContext null
            }

            val finalFileName = ReleaseUpdaterCore.buildVersionedLocalFileName(
                assetName = selectedAsset.name,
                normalizedVersion = normalizedLatest,
                platform = TargetPlatform.ANDROID
            )

            updatesDir.mkdirs()
            val targetApk = File(updatesDir, finalFileName)

            if (!targetApk.exists() || targetApk.length() < MIN_VALID_APK_BYTES) {
                Log.i(TAG, "Download aggiornamento $finalFileName da ${selectedAsset.downloadUrl}...")
                val downloaded = ReleaseUpdaterCore.downloadFileAtomically(
                    downloadUrl = selectedAsset.downloadUrl,
                    targetFile = targetApk,
                    minValidBytes = MIN_VALID_APK_BYTES,
                    userAgent = USER_AGENT
                )
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
}
