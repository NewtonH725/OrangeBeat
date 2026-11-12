package com.example.crossreel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MotionAnalyzer {

    suspend fun analyzeMotion(context: Context, videoUri: Uri, durationMs: Long): Float = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            
            val numFrames = 5
            val intervalMs = durationMs / (numFrames + 1)
            
            val frames = mutableListOf<Bitmap>()
            
            for (i in 1..numFrames) {
                val timeUs = i * intervalMs * 1000L
                val scaled = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { bmp ->
                        val s = Bitmap.createScaledBitmap(bmp, 64, 64, true)
                        if (s != bmp) bmp.recycle()
                        s
                    }
                if (scaled != null) {
                    frames.add(scaled)
                }
            }

            if (frames.size < 2) {
                return@withContext 0f
            }

            var totalDifference = 0L
            val pixels1 = IntArray(64 * 64)
            val pixels2 = IntArray(64 * 64)

            for (i in 0 until frames.size - 1) {
                val bmp1 = frames[i]
                val bmp2 = frames[i+1]
                
                bmp1.getPixels(pixels1, 0, 64, 0, 0, 64, 64)
                bmp2.getPixels(pixels2, 0, 64, 0, 0, 64, 64)
                
                for (p in 0 until pixels1.size) {
                    val c1 = pixels1[p]
                    val c2 = pixels2[p]
                    
                    val rDiff = abs(Color.red(c1) - Color.red(c2))
                    val gDiff = abs(Color.green(c1) - Color.green(c2))
                    val bDiff = abs(Color.blue(c1) - Color.blue(c2))
                    
                    totalDifference += rDiff + gDiff + bDiff
                }
            }

            // Max possible difference per pixel per channel is 255.
            // 3 channels per pixel.
            val maxDiffPerPair = 64L * 64L * 3L * 255L
            val totalMaxDiff = maxDiffPerPair * (frames.size - 1)
            
            val rawScore = totalDifference.toDouble() / totalMaxDiff.toDouble()
            
            // Free up bitmaps
            for (bmp in frames) {
                bmp.recycle()
            }

            return@withContext rawScore.toFloat().coerceIn(0f, 1f)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0f
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
