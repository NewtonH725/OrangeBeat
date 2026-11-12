package com.example.crossreel

import kotlin.math.abs

/**
 * Calcolo puro della Sum of Absolute Differences (SAD) su frame RGB24 a 64x64 pixel.
 */
object MotionSadCore {

    const val FRAME_WIDTH = 64
    const val FRAME_HEIGHT = 64
    const val FRAME_RGB_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 3

    fun computeNormalizedSadScore(rgbFrames: List<ByteArray>): Float {
        if (rgbFrames.size < 2) return 0f

        var totalDifference = 0L
        var validPairs = 0

        for (i in 0 until rgbFrames.size - 1) {
            val f1 = rgbFrames[i]
            val f2 = rgbFrames[i + 1]
            if (f1.size < FRAME_RGB_BYTES || f2.size < FRAME_RGB_BYTES) continue

            for (p in 0 until FRAME_RGB_BYTES) {
                val v1 = f1[p].toInt() and 0xFF
                val v2 = f2[p].toInt() and 0xFF
                totalDifference += abs(v1 - v2)
            }
            validPairs++
        }

        if (validPairs == 0) return 0f
        val totalMaxDiff = FRAME_RGB_BYTES.toLong() * 255L * validPairs
        val rawScore = totalDifference.toDouble() / totalMaxDiff.toDouble()
        return rawScore.toFloat().coerceIn(0f, 1f)
    }
}
