package com.example.crossreel

import kotlin.math.sqrt

/**
 * Motore DSP puro per l'analisi dei beat su stream PCM 16-bit:
 * - Filtro digitale passa-basso IIR a 160 Hz (isolamento kick drum)
 * - Calcolo energia RMS su finestre mobili da 50 ms
 * - Rilevamento massimi locali, mediana BPM e gap-filler
 */
object BeatDspCore {

    fun detectBeatsFromPcmStream(
        pcmBytes: ByteArray,
        sampleRate: Int = 44100,
        channelCount: Int = 1,
        targetDurationMs: Long,
        defaultBpm: Double = 128.0
    ): List<Long> {
        val safeChannels = channelCount.coerceAtLeast(1)
        val samplesPerWindow = (sampleRate * 50) / 1000
        val bytesPerFrame = safeChannels * 2
        val bytesPerWindow = samplesPerWindow * bytesPerFrame
        if (bytesPerWindow <= 0 || pcmBytes.size < bytesPerWindow) {
            return generateRegularBeats(defaultBpm, targetDurationMs)
        }

        val energies = ArrayList<Double>(pcmBytes.size / bytesPerWindow + 1)
        val timestamps = ArrayList<Long>(pcmBytes.size / bytesPerWindow + 1)

        val lpfAlpha = (2.0 * Math.PI * 160.0 / sampleRate).coerceIn(0.01, 0.5)
        var lpfState = 0.0
        var totalFramesProcessed = 0L

        var pos = 0
        while (pos + bytesPerWindow <= pcmBytes.size) {
            var sumSq = 0.0
            var i = pos
            val windowEnd = pos + bytesPerWindow
            while (i < windowEnd) {
                var channelSum = 0.0
                for (ch in 0 until safeChannels) {
                    val offset = i + ch * 2
                    val s = ((pcmBytes[offset + 1].toInt() shl 8) or (pcmBytes[offset].toInt() and 0xFF)).toShort()
                    channelSum += s
                }
                val mono = channelSum / safeChannels
                lpfState += lpfAlpha * (mono - lpfState)
                sumSq += lpfState * lpfState
                i += bytesPerFrame
            }

            val rms = sqrt(sumSq / samplesPerWindow)
            energies.add(rms)
            val exactTimeMs = totalFramesProcessed * 1000L / sampleRate
            timestamps.add(exactTimeMs)

            totalFramesProcessed += samplesPerWindow
            pos += bytesPerWindow
        }

        return detectBeatsFromRmsWindows(energies, timestamps, targetDurationMs, defaultBpm)
    }

    fun detectBeatsFromRmsWindows(
        energies: List<Double>,
        timestamps: List<Long>,
        targetDurationMs: Long,
        defaultBpm: Double = 128.0
    ): List<Long> {
        val beats = mutableListOf<Long>()
        val windowSize = 20 // 1 secondo (20 finestre da 50ms)
        var sum = 0.0

        for (i in energies.indices) {
            sum += energies[i]
            if (i >= windowSize) sum -= energies[i - windowSize]

            val count = if (i + 1 < windowSize) i + 1 else windowSize
            val avg = sum / count

            val isLocalMax = (i == 0 || energies[i] >= energies[i - 1]) &&
                (i == energies.size - 1 || energies[i] >= energies[i + 1])

            if (isLocalMax && energies[i] > avg * 1.35 && (beats.isEmpty() || timestamps[i] - beats.last() >= 250)) {
                beats.add(timestamps[i])
            }
        }

        val intervals = mutableListOf<Long>()
        for (b in 1 until beats.size) {
            val diff = beats[b] - beats[b - 1]
            if (diff in 250..1200) {
                intervals.add(diff)
            }
        }

        val measuredIntervalMs: Long = if (intervals.isNotEmpty()) {
            intervals.sorted()[intervals.size / 2]
        } else {
            (60000.0 / defaultBpm).toLong()
        }

        val rawBeats = if (beats.isNotEmpty()) beats else generateRegularBeats(60000.0 / measuredIntervalMs, targetDurationMs)

        val intervalMs = measuredIntervalMs
        val filledBeats = mutableListOf<Long>()
        var expectedNext = 0L

        for (beat in rawBeats) {
            while (expectedNext + (intervalMs * 1.4).toLong() < beat) {
                filledBeats.add(expectedNext)
                expectedNext += intervalMs
            }
            filledBeats.add(beat)
            expectedNext = beat + intervalMs
        }

        var current = if (filledBeats.isNotEmpty()) filledBeats.last() else 0L
        while (current < targetDurationMs) {
            current += intervalMs
            filledBeats.add(current)
        }

        return filledBeats
    }

    fun generateRegularBeats(bpm: Double, durationMs: Long): List<Long> {
        val beats = mutableListOf<Long>()
        if (bpm <= 0) return listOf(0L)

        val intervalMs = (60000.0 / bpm).toLong()
        var current = 0L

        while (current < durationMs && beats.size < 10000) {
            beats.add(current)
            current += intervalMs
        }
        return beats
    }
}
