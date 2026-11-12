package com.example.crossreel

import android.net.Uri

class AiDirector {

    private val core = AiDirectorCore()

    companion object {
        fun computeUsableBounds(durationMs: Long): LongRange =
            AiDirectorCore.computeUsableBounds(durationMs)

        fun computeUsableDurationMs(durationMs: Long): Long =
            AiDirectorCore.computeUsableDurationMs(durationMs)
    }

    fun generateEditPlanLocal(
        videos: List<VideoMetadata>,
        beatTimestamps: List<Long>,
        targetDurationSec: Int
    ): String {
        val sharedVideos = videos.map {
            SharedVideoMetadata(
                id = it.uri.toString(),
                durationMs = it.durationMs,
                motionScore = it.motionScore,
                actionPeaks = it.actionPeaks
            )
        }
        val clips = core.generateEditPlan(sharedVideos, beatTimestamps, targetDurationSec)
        return AiDirectorCore.toJsonString(clips)
    }
}

data class VideoMetadata(
    val uri: Uri,
    val durationMs: Long,
    val motionScore: Float = 0f,
    val actionPeaks: List<Long> = emptyList()
)
