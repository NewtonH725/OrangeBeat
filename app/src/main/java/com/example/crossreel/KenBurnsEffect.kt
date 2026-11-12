package com.example.crossreel

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation

class KenBurnsMatrix(private val durationUs: Long) : MatrixTransformation {

    // In una EditedMediaItemSequence i timestamp possono essere cumulativi (non ripartono da 0 a ogni clip):
    // memorizziamo il primo timestamp ricevuto e calcoliamo il tempo relativo alla clip.
    private var firstPtsUs = -1L

    // Matrice pre-allocata per garantire zero allocazioni nel loop di rendering per-frame (60fps)
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (firstPtsUs < 0L) firstPtsUs = presentationTimeUs
        val relativeUs = presentationTimeUs - firstPtsUs

        matrix.reset()
        val progress = if (durationUs > 0) {
            (relativeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Zoom in from 1.0x to 1.15x (sempre >= 1.0f per coprire l'intero viewport sia in 9:16 che in 16:9)
        val scale = 1.0f + (0.15f * progress)
        
        // MatrixTransformation uses NDC coordinates (-1 to 1), where (0,0) is the center.
        // Scaling around the origin (0,0) will naturally zoom in on the center of the video.
        matrix.postScale(scale, scale)
        
        return matrix
    }
}
