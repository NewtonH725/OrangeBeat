package com.example.crossreel

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class TargetPlatform(
    val platformSuffix: String,
    val primaryExtensions: List<String>
) {
    ANDROID("android", listOf(".apk")),
    UBUNTU("ubuntu", listOf(".deb", ".appimage")),
    WINDOWS("windows", listOf(".msi", ".exe"))
}

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GitHubLatestRelease(
    val tagName: String,
    val normalizedVersion: String,
    val releaseNotes: String,
    val assets: List<GitHubReleaseAsset>
)

/**
 * Motore condiviso per controllo aggiornamenti OTA su GitHub Releases (NewtonH725/OrangeBeat)
 * per tutte e tre le piattaforme (Android, Ubuntu, Windows).
 * Garantisce che ogni file scaricato contenga sempre il numero di versione (es. OrangeBeat-v3.0.0-...).
 */
object ReleaseUpdaterCore {

    const val RELEASES_LATEST_URL = "https://api.github.com/repos/NewtonH725/OrangeBeat/releases/latest"

    fun detectDesktopPlatform(): TargetPlatform {
        val os = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)
        return if (os.contains("win")) TargetPlatform.WINDOWS else TargetPlatform.UBUNTU
    }

    fun normalizeVersion(raw: String): String {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        return "v$cleaned"
    }

    fun parseVersionTuple(version: String): Triple<Int, Int, Int> {
        val parts = version.removePrefix("v").removePrefix("V").split(".")
        val major = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = parseVersionTuple(candidate)
        val cur = parseVersionTuple(current)
        if (c.first != cur.first) return c.first > cur.first
        if (c.second != cur.second) return c.second > cur.second
        return c.third > cur.third
    }

    fun selectAssetForPlatform(
        assets: List<GitHubReleaseAsset>,
        platform: TargetPlatform,
        normalizedVersion: String
    ): GitHubReleaseAsset? {
        for (ext in platform.primaryExtensions) {
            val matching = assets.filter {
                it.name.endsWith(ext, ignoreCase = true) && it.downloadUrl.isNotEmpty()
            }
            if (matching.isEmpty()) continue

            val withVersionAndPlatform = matching.firstOrNull {
                it.name.contains(normalizedVersion, ignoreCase = true) &&
                    it.name.contains(platform.platformSuffix, ignoreCase = true)
            }
            if (withVersionAndPlatform != null) return withVersionAndPlatform

            val withVersion = matching.firstOrNull {
                it.name.contains(normalizedVersion, ignoreCase = true)
            }
            if (withVersion != null) return withVersion

            return matching.first()
        }
        return null
    }

    /**
     * Garantisce che il file salvato localmente includa sempre il numero di versione dell'app.
     */
    fun buildVersionedLocalFileName(
        assetName: String,
        normalizedVersion: String,
        platform: TargetPlatform
    ): String {
        if (assetName.contains(normalizedVersion, ignoreCase = true)) {
            return assetName
        }
        val dotIdx = assetName.lastIndexOf('.')
        val ext = if (dotIdx >= 0) assetName.substring(dotIdx) else platform.primaryExtensions.first()
        return "OrangeBeat-$normalizedVersion-${platform.platformSuffix}$ext"
    }

    fun fetchLatestReleaseJsonText(userAgent: String): String? {
        val url = URL(RELEASES_LATEST_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            useCaches = false
            connectTimeout = 5000
            readTimeout = 8000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadFileAtomically(
        downloadUrl: String,
        targetFile: File,
        minValidBytes: Long,
        userAgent: String
    ): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                useCaches = false
                connectTimeout = 8000
                readTimeout = 90000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() >= minValidBytes) {
                    if (targetFile.exists()) targetFile.delete()
                    return tempFile.renameTo(targetFile)
                }
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            connection?.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun cleanupOldUpdates(
        updatesDir: File,
        currentVersion: String,
        extensions: List<String>
    ) {
        if (!updatesDir.exists()) return
        val currentVer = normalizeVersion(currentVersion)
        val verRegex = Regex("""v?\d+\.\d+\.\d+""")
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            } else if (extensions.any { file.name.endsWith(it, ignoreCase = true) } && !file.name.contains(currentVer)) {
                val match = verRegex.find(file.name)?.value
                if (match == null || !isNewerVersion(normalizeVersion(match), currentVer)) {
                    file.delete()
                }
            }
        }
    }
}
