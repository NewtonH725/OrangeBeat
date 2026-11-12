package com.example.crossreel.desktop

import com.example.crossreel.GitHubReleaseAsset
import com.example.crossreel.ReleaseUpdaterCore
import com.example.crossreel.TargetPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class DesktopUpdateInfo(
    val versionName: String,
    val installerFileName: String,
    val installerFile: File,
    val releaseNotes: String,
    val platform: TargetPlatform
)

/**
 * Auto-Updater Desktop per Ubuntu (.deb / .AppImage) e Windows (.msi / .exe)
 * collegato a GitHub Releases (NewtonH725/OrangeBeat).
 */
object DesktopAppUpdater {

    private const val MIN_VALID_INSTALLER_BYTES = 5L * 1024L * 1024L // 5 MB

    suspend fun checkAndDownloadUpdate(): DesktopUpdateInfo? = withContext(Dispatchers.IO) {
        val platform = ReleaseUpdaterCore.detectDesktopPlatform()
        val userAgent = if (platform == TargetPlatform.WINDOWS) {
            "OrangeBeat-Windows-Client"
        } else {
            "OrangeBeat-Ubuntu-Client"
        }

        val updatesDir = DesktopPaths.getUpdatesDir()
        ReleaseUpdaterCore.cleanupOldUpdates(
            updatesDir = updatesDir,
            currentVersion = DesktopBuildConfig.VERSION_NAME,
            extensions = platform.primaryExtensions
        )

        try {
            val rawJson = ReleaseUpdaterCore.fetchLatestReleaseJsonText(userAgent) ?: return@withContext null
            val releaseJson = JSONObject(rawJson)
            val tagName = releaseJson.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return@withContext null

            val normalizedLatest = ReleaseUpdaterCore.normalizeVersion(tagName)
            val normalizedCurrent = ReleaseUpdaterCore.normalizeVersion(DesktopBuildConfig.VERSION_NAME)

            if (!ReleaseUpdaterCore.isNewerVersion(normalizedLatest, normalizedCurrent)) {
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
                platform = platform,
                normalizedVersion = normalizedLatest
            ) ?: return@withContext null

            val finalFileName = ReleaseUpdaterCore.buildVersionedLocalFileName(
                assetName = selectedAsset.name,
                normalizedVersion = normalizedLatest,
                platform = platform
            )

            updatesDir.mkdirs()
            val targetInstaller = File(updatesDir, finalFileName)

            if (!targetInstaller.exists() || targetInstaller.length() < MIN_VALID_INSTALLER_BYTES) {
                val downloaded = ReleaseUpdaterCore.downloadFileAtomically(
                    downloadUrl = selectedAsset.downloadUrl,
                    targetFile = targetInstaller,
                    minValidBytes = MIN_VALID_INSTALLER_BYTES,
                    userAgent = userAgent
                )
                if (!downloaded) return@withContext null
            }

            return@withContext DesktopUpdateInfo(
                versionName = normalizedLatest,
                installerFileName = finalFileName,
                installerFile = targetInstaller,
                releaseNotes = releaseJson.optString("body", "").trim(),
                platform = platform
            )
        } catch (_: Exception) {
            return@withContext null
        }
    }

    fun launchInstaller(update: DesktopUpdateInfo): Boolean {
        return try {
            val file = update.installerFile
            if (!file.exists()) return false

            when (update.platform) {
                TargetPlatform.WINDOWS -> {
                    if (file.name.endsWith(".msi", ignoreCase = true)) {
                        ProcessBuilder("msiexec", "/i", file.absolutePath).start()
                    } else {
                        ProcessBuilder(file.absolutePath).start()
                    }
                    true
                }
                TargetPlatform.UBUNTU -> {
                    if (file.name.endsWith(".deb", ignoreCase = true)) {
                        ProcessBuilder("pkexec", "dpkg", "-i", file.absolutePath).start()
                    } else {
                        file.setExecutable(true)
                        ProcessBuilder(file.absolutePath).start()
                    }
                    true
                }
                TargetPlatform.ANDROID -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
