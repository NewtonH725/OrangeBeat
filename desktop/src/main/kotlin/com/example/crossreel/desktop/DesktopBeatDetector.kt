package com.example.crossreel.desktop

import com.example.crossreel.BeatDspCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object DesktopBeatDetector {

    suspend fun findBeats(
        audioFile: File,
        startOffsetMs: Long,
        targetDurationMs: Long
    ): List<Long> = withContext(Dispatchers.Default) {
        val defaultBpm = 128.0
        val ffmpeg = FfmpegLocator.findFfmpeg()
            ?: return@withContext BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs)

        try {
            val startSec = String.format(Locale.US, "%.3f", startOffsetMs / 1000.0)
            val durSec = String.format(Locale.US, "%.3f", (targetDurationMs / 1000.0) + 1.0)

            val process = ProcessBuilder(
                ffmpeg,
                "-v", "quiet",
                "-ss", startSec,
                "-t", durSec,
                "-i", audioFile.absolutePath,
                "-vn",
                "-ac", "1",
                "-ar", "44100",
                "-f", "s16le",
                "pipe:1"
            ).start()

            val pcmBytes = process.inputStream.readBytes()
            process.waitFor()

            if (pcmBytes.isEmpty()) {
                return@withContext BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs)
            }

            BeatDspCore.detectBeatsFromPcmStream(
                pcmBytes = pcmBytes,
                sampleRate = 44100,
                channelCount = 1,
                targetDurationMs = targetDurationMs,
                defaultBpm = defaultBpm
            )
        } catch (_: Exception) {
            BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs)
        }
    }
}
