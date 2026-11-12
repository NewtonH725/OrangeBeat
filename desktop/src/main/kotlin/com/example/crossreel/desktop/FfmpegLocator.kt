package com.example.crossreel.desktop

import java.io.File

object FfmpegLocator {

    fun findFfmpeg(): String? = findExecutable("ffmpeg")

    fun findFfprobe(): String? = findExecutable("ffprobe")

    fun isAvailable(): Boolean = findFfmpeg() != null && findFfprobe() != null

    private fun findExecutable(name: String): String? {
        val exeName = if (DesktopPaths.isWindows) "$name.exe" else name

        // 1. Controlla eventuale binario accanto all'applicazione
        val appDirCandidates = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, exeName) },
            File(System.getProperty("user.dir"), exeName),
            File(System.getProperty("user.dir"), "bin/$exeName")
        )
        for (candidate in appDirCandidates) {
            if (candidate.exists() && candidate.isFile) {
                return candidate.absolutePath
            }
        }

        // 2. Controlla PATH di sistema
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, exeName)
            if (candidate.exists() && candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }

        // 3. Percorsi standard Linux / Windows
        val fallbackCandidates = if (DesktopPaths.isWindows) {
            listOf(
                File("C:\\ffmpeg\\bin\\$exeName"),
                File("C:\\Program Files\\ffmpeg\\bin\\$exeName")
            )
        } else {
            listOf(
                File("/usr/bin/$exeName"),
                File("/usr/local/bin/$exeName"),
                File("/snap/bin/$exeName")
            )
        }

        return fallbackCandidates.firstOrNull { it.exists() && it.isFile }?.absolutePath
    }
}
