package com.example.crossreel.desktop

import com.example.crossreel.MotionSadCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class DesktopVideoProbe(
    val durationMs: Long,
    val fps: Int?
)

data class DesktopVideoAnalysis(
    val motionScore: Float,
    val actionPeaksMs: List<Long>
)

object DesktopMediaAnalyzer {

    suspend fun probeVideo(file: File): DesktopVideoProbe = withContext(Dispatchers.IO) {
        val ffprobe = FfmpegLocator.findFfprobe() ?: return@withContext DesktopVideoProbe(0L, null)
        try {
            val process = ProcessBuilder(
                ffprobe,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                file.absolutePath
            ).redirectErrorStream(false).start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            if (stdout.isBlank()) return@withContext DesktopVideoProbe(0L, null)
            val root = JSONObject(stdout)

            val durationSec = root.optJSONObject("format")?.optString("duration", "0")?.toDoubleOrNull() ?: 0.0
            val durationMs = (durationSec * 1000.0).toLong().coerceAtLeast(0L)

            var fps: Int? = null
            val streams = root.optJSONArray("streams")
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    if (s.optString("codec_type") == "video") {
                        val rateStr = s.optString("avg_frame_rate", "").ifBlank { s.optString("r_frame_rate", "") }
                        fps = parseFps(rateStr)
                        if (fps != null) break
                    }
                }
            }

            DesktopVideoProbe(durationMs = durationMs, fps = fps)
        } catch (_: Exception) {
            DesktopVideoProbe(0L, null)
        }
    }

    suspend fun analyzeMotionAndPeaks(file: File, durationMs: Long): DesktopVideoAnalysis = withContext(Dispatchers.Default) {
        val ffmpeg = FfmpegLocator.findFfmpeg() ?: return@withContext DesktopVideoAnalysis(0f, emptyList())
        if (durationMs <= 0L) return@withContext DesktopVideoAnalysis(0f, emptyList())

        val numFrames = 8
        val intervalMs = durationMs / (numFrames + 1)
        val frames = mutableListOf<ByteArray>()
        val timestamps = mutableListOf<Long>()

        for (i in 1..numFrames) {
            val timeMs = i * intervalMs
            val timeSec = String.format(Locale.US, "%.3f", timeMs / 1000.0)
            val rgb = extractScaledRgbFrame(ffmpeg, file, timeSec)
            if (rgb != null) {
                frames.add(rgb)
                timestamps.add(timeMs)
            }
        }

        if (frames.size < 2) {
            return@withContext DesktopVideoAnalysis(0f, emptyList())
        }

        val motionScore = MotionSadCore.computeNormalizedSadScore(frames)

        // Calcola i picchi energetici tra frame consecutivi per lo speed ramping di AiDirectorCore
        val pairDiffs = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until frames.size - 1) {
            val f1 = frames[i]
            val f2 = frames[i + 1]
            var diff = 0L
            for (p in 0 until MotionSadCore.FRAME_RGB_BYTES) {
                diff += abs((f1[p].toInt() and 0xFF) - (f2[p].toInt() and 0xFF))
            }
            pairDiffs.add(timestamps[i + 1] to diff)
        }

        val avgDiff = if (pairDiffs.isNotEmpty()) pairDiffs.sumOf { it.second }.toDouble() / pairDiffs.size else 0.0
        val peaks = pairDiffs.filter { it.second > avgDiff * 1.25 }.map { it.first }

        DesktopVideoAnalysis(motionScore = motionScore, actionPeaksMs = peaks)
    }

    private fun extractScaledRgbFrame(ffmpeg: String, file: File, timeSec: String): ByteArray? {
        return try {
            val process = ProcessBuilder(
                ffmpeg,
                "-v", "quiet",
                "-ss", timeSec,
                "-i", file.absolutePath,
                "-frames:v", "1",
                "-vf", "scale=${MotionSadCore.FRAME_WIDTH}:${MotionSadCore.FRAME_HEIGHT}:flags=fast_bilinear",
                "-f", "rawvideo",
                "-pix_fmt", "rgb24",
                "pipe:1"
            ).start()

            val bytes = process.inputStream.readNBytes(MotionSadCore.FRAME_RGB_BYTES)
            process.waitFor()
            if (bytes.size == MotionSadCore.FRAME_RGB_BYTES) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFps(rateStr: String): Int? {
        if (rateStr.isBlank()) return null
        return if (rateStr.contains('/')) {
            val parts = rateStr.split('/')
            val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
            if (den <= 0.0) null else (num / den).roundToInt().takeIf { it in 24..120 }
        } else {
            rateStr.toDoubleOrNull()?.roundToInt()?.takeIf { it in 24..120 }
        }
    }
}
