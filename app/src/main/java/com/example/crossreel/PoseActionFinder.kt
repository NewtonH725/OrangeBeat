package com.example.crossreel

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

suspend fun findActionPeaks(context: Context, videoUri: Uri, durationMs: Long): List<Long> = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, videoUri)
        
        // Extract 10 frames evenly spaced
        val numFrames = 10
        val intervalMs = durationMs / (numFrames + 1)
        
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val poseDetector = PoseDetection.getClient(options)
        
        try {
            val frameData = mutableListOf<Pair<Long, Float>>()
            
            for (i in 1..numFrames) {
                val timeMs = i * intervalMs
                val timeUs = timeMs * 1000L
                // Estrae frame scalati a 480x270: 95% meno RAM e 3x più veloce su video 4K/1080p
                val bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 270)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                
                try {
                    val image = InputImage.fromBitmap(bitmap, 0)
                    val pose = processPose(poseDetector, image)
                    
                    // Track the vertical (Y) coordinate of the hips (or shoulders)
                    val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                    val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                    
                    if (leftHip != null && rightHip != null) {
                        val avgY = (leftHip.position.y + rightHip.position.y) / 2f
                        frameData.add(Pair(timeMs, avgY))
                    } else {
                        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                        if (leftShoulder != null && rightShoulder != null) {
                            val avgY = (leftShoulder.position.y + rightShoulder.position.y) / 2f
                            frameData.add(Pair(timeMs, avgY))
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            
            // Identify timestamps with a sudden, large vertical displacement
            val peaks = mutableListOf<Long>()
            if (frameData.size > 1) {
                var totalDisplacement = 0f
                for (i in 1 until frameData.size) {
                    totalDisplacement += abs(frameData[i].second - frameData[i - 1].second)
                }
                val avgDisplacement = totalDisplacement / (frameData.size - 1)
                val threshold = avgDisplacement * 1.5f
                
                for (i in 1 until frameData.size) {
                    val displacement = abs(frameData[i].second - frameData[i - 1].second)
                    if (displacement > threshold && displacement > 8f) {
                        peaks.add(frameData[i].first)
                    }
                }
            }
            
            return@withContext peaks
        } finally {
            poseDetector.close()
        }
        
    } finally {
        retriever.release()
    }
}

private suspend fun processPose(poseDetector: PoseDetector, image: InputImage): Pose = suspendCancellableCoroutine { continuation ->
    poseDetector.process(image)
        .addOnSuccessListener { pose ->
            if (continuation.isActive) {
                continuation.resume(pose)
            }
        }
        .addOnFailureListener { e ->
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
}
