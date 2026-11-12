package com.example.crossreel.desktop

import java.io.File
import java.util.Locale

object DesktopPaths {

    val isWindows: Boolean by lazy {
        (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("win")
    }

    fun getAppDataDir(): File {
        val base = if (isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) File(localAppData, "OrangeBeat")
            else File(System.getProperty("user.home"), "AppData/Local/OrangeBeat")
        } else {
            val xdgData = System.getenv("XDG_DATA_HOME")
            if (!xdgData.isNullOrBlank()) File(xdgData, "OrangeBeat")
            else File(System.getProperty("user.home"), ".local/share/OrangeBeat")
        }
        return base.apply { mkdirs() }
    }

    fun getUpdatesDir(): File = File(getAppDataDir(), "updates").apply { mkdirs() }

    fun getAudioCacheDir(): File = File(getAppDataDir(), "audio_cache").apply { mkdirs() }

    fun getBundledAudioDir(): File = File(getAppDataDir(), "bundled_audio").apply { mkdirs() }

    fun getDefaultVideosOutputDir(): File {
        val home = File(System.getProperty("user.home"))
        val videos = File(home, "Videos")
        val target = if (videos.exists() && videos.isDirectory) File(videos, "OrangeBeat") else File(home, "OrangeBeat")
        return target.apply { mkdirs() }
    }
}
