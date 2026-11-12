package com.example.crossreel

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

data class EditClipPlan(
    val videoIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val speed: Double,
    val section: String
)

data class SharedVideoMetadata(
    val id: String,
    val durationMs: Long,
    val motionScore: Float = 0f,
    val actionPeaks: List<Long> = emptyList()
)

class AiDirectorCore {

    companion object {
        private const val BIN_SIZE_MS = 100L
        private const val MIN_CUT_TIMELINE_MS = 380L
        private const val MIN_SOURCE_SPAN_MS = 260L

        fun computeUsableBounds(durationMs: Long): LongRange {
            if (durationMs <= 0L) return 0L..0L
            val margin = minOf((durationMs * 0.05f).toLong(), 500L)
            val uStart = margin
            val uEnd = durationMs - margin
            return if (uEnd - uStart >= 1500L) (uStart..uEnd) else (0L..durationMs)
        }

        fun computeUsableDurationMs(durationMs: Long): Long {
            val bounds = computeUsableBounds(durationMs)
            return (bounds.last - bounds.first).coerceAtLeast(0L)
        }

        fun toJsonString(clips: List<EditClipPlan>): String {
            val sb = StringBuilder(clips.size * 96 + 2)
            sb.append('[')
            clips.forEachIndexed { index, clip ->
                if (index > 0) sb.append(',')
                sb.append('{')
                sb.append("\"videoIndex\":").append(clip.videoIndex).append(',')
                sb.append("\"startTimeMs\":").append(clip.startTimeMs).append(',')
                sb.append("\"endTimeMs\":").append(clip.endTimeMs).append(',')
                sb.append("\"speed\":").append(clip.speed).append(',')
                sb.append("\"section\":\"").append(clip.section).append('"')
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }
    }

    enum class MusicSection(val startPct: Float, val endPct: Float) {
        INTRO(0.00f, 0.15f),
        BUILDUP(0.15f, 0.35f),
        DROP(0.35f, 0.60f),
        BREAKDOWN(0.60f, 0.75f),
        CLIMAX(0.75f, 1.00f)
    }

    private fun getSectionForTimeline(currentTimelineMs: Long, targetMs: Long): MusicSection {
        val progress = if (targetMs > 0) (currentTimelineMs.toFloat() / targetMs.toFloat()).coerceIn(0f, 1f) else 0f
        return when {
            progress < MusicSection.INTRO.endPct -> MusicSection.INTRO
            progress < MusicSection.BUILDUP.endPct -> MusicSection.BUILDUP
            progress < MusicSection.DROP.endPct -> MusicSection.DROP
            progress < MusicSection.BREAKDOWN.endPct -> MusicSection.BREAKDOWN
            else -> MusicSection.CLIMAX
        }
    }

    private fun getRhythmPattern(
        section: MusicSection,
        isLongReel: Boolean,
        inFinalBurst: Boolean,
        isVeryScarceFootage: Boolean
    ): List<Int> {
        val basePattern = when (section) {
            MusicSection.INTRO -> listOf(8, 4, 8, 4)
            MusicSection.BUILDUP -> if (isLongReel) {
                listOf(4, 4, 2, 2, 4, 2, 2, 1)
            } else {
                listOf(4, 2, 2, 1, 4, 2, 1, 1)
            }
            MusicSection.DROP -> if (isLongReel) {
                listOf(2, 2, 1, 1, 2, 4, 2, 1, 1)
            } else {
                listOf(1, 1, 2, 1, 1, 2, 1, 1)
            }
            MusicSection.BREAKDOWN -> listOf(8, 4, 8)
            MusicSection.CLIMAX -> if (isLongReel) {
                if (inFinalBurst) {
                    listOf(1, 1, 2, 1, 1, 1, 2)
                } else {
                    listOf(4, 2, 4, 2, 2, 2)
                }
            } else {
                listOf(2, 2, 1, 1, 1, 2, 1, 4)
            }
        }

        if (isVeryScarceFootage && !inFinalBurst) {
            return basePattern.map { beats ->
                when {
                    beats <= 1 -> 2
                    beats == 2 && (section == MusicSection.INTRO || section == MusicSection.BREAKDOWN || section == MusicSection.BUILDUP) -> 4
                    else -> beats
                }
            }
        }
        return basePattern
    }

    fun generateEditPlan(
        videos: List<SharedVideoMetadata>,
        beatTimestamps: List<Long>,
        targetDurationSec: Int
    ): List<EditClipPlan> {
        val plan = mutableListOf<EditClipPlan>()
        var currentTimelineMs = 0L
        val targetMs = targetDurationSec * 1000L
        val totalBeats = beatTimestamps.size

        if (videos.isEmpty() || beatTimestamps.isEmpty() || targetMs <= 0L) return emptyList()

        val n = videos.size
        val isLongReel = targetDurationSec > 30

        val usableBounds = Array(n) { idx -> computeUsableBounds(videos[idx].durationMs) }
        val usableDurationsMs = LongArray(n) { idx ->
            (usableBounds[idx].last - usableBounds[idx].first).coerceAtLeast(1L)
        }
        val usageBins = Array(n) { idx ->
            val binCount = maxOf(1, ((videos[idx].durationMs + BIN_SIZE_MS - 1L) / BIN_SIZE_MS).toInt())
            IntArray(binCount) { 0 }
        }
        val lastSegmentStartMs = LongArray(n) { -1L }

        val totalUsableMs = usableDurationsMs.sum()
        val coverageRatio = totalUsableMs.toFloat() / targetMs.toFloat()
        val isScarceFootage = coverageRatio < 1.10f
        val isVeryScarceFootage = coverageRatio < 0.70f

        val motionRank = computeNormalizedMotionScores(videos)

        val fullCooldownSize = when {
            n <= 1 -> 0
            n == 2 -> 1
            n == 3 -> 2
            n == 4 -> 2
            else -> minOf(n - 2, maxOf(3, (n * 2) / 3))
        }
        val recentCooldownQueue = ArrayDeque<Int>()

        fun getFreshUnseenMs(videoIdx: Int): Long {
            val bounds = usableBounds[videoIdx]
            val bins = usageBins[videoIdx]
            val bStart = (bounds.first / BIN_SIZE_MS).toInt().coerceIn(0, bins.lastIndex)
            val bEnd = ((bounds.last - 1L).coerceAtLeast(0L) / BIN_SIZE_MS).toInt().coerceIn(bStart, bins.lastIndex)
            var unseenBins = 0
            for (b in bStart..bEnd) {
                if (bins[b] == 0) unseenBins++
            }
            return unseenBins * BIN_SIZE_MS
        }

        fun getAverageUtilization(videoIdx: Int): Float {
            val bounds = usableBounds[videoIdx]
            val bins = usageBins[videoIdx]
            val bStart = (bounds.first / BIN_SIZE_MS).toInt().coerceIn(0, bins.lastIndex)
            val bEnd = ((bounds.last - 1L).coerceAtLeast(0L) / BIN_SIZE_MS).toInt().coerceIn(bStart, bins.lastIndex)
            val count = (bEnd - bStart + 1).coerceAtLeast(1)
            var sum = 0
            for (b in bStart..bEnd) {
                sum += bins[b]
            }
            return sum.toFloat() / count.toFloat()
        }

        fun markSegmentUsed(videoIdx: Int, startMs: Long, endMs: Long) {
            val bins = usageBins[videoIdx]
            val bStart = (startMs / BIN_SIZE_MS).toInt().coerceIn(0, bins.lastIndex)
            val bEnd = ((endMs - 1L).coerceAtLeast(startMs) / BIN_SIZE_MS).toInt().coerceIn(bStart, bins.lastIndex)
            for (b in bStart..bEnd) {
                bins[b]++
            }
            lastSegmentStartMs[videoIdx] = startMs
        }

        fun getWindowUsageStats(videoIdx: Int, startMs: Long, endMs: Long): Float {
            val bins = usageBins[videoIdx]
            val bStart = (startMs / BIN_SIZE_MS).toInt().coerceIn(0, bins.lastIndex)
            val bEnd = ((endMs - 1L).coerceAtLeast(startMs) / BIN_SIZE_MS).toInt().coerceIn(bStart, bins.lastIndex)
            val count = (bEnd - bStart + 1).coerceAtLeast(1)
            var sum = 0
            for (b in bStart..bEnd) {
                sum += bins[b]
            }
            return sum.toFloat() / count.toFloat()
        }

        var beatIdx = 0
        var cutCounter = 0
        val sectionRhythmIdx = mutableMapOf<MusicSection, Int>()
        var currentSection: MusicSection? = null
        var enteredFinalBurst = false

        while (targetMs - currentTimelineMs >= 150L) {
            while (beatIdx < totalBeats - 1 && beatTimestamps[beatIdx] < currentTimelineMs + 180L) {
                beatIdx++
            }

            val remainingTimelineMs = targetMs - currentTimelineMs
            val section = getSectionForTimeline(currentTimelineMs, targetMs)

            val inFinalBurst = isLongReel &&
                section == MusicSection.CLIMAX &&
                remainingTimelineMs in 1801L..7300L

            if (section != currentSection || (inFinalBurst && !enteredFinalBurst)) {
                currentSection = section
                if (inFinalBurst) enteredFinalBurst = true
                sectionRhythmIdx[section] = 0
            }

            var clipDuration: Long
            var nextBeatIdx: Int
            if (remainingTimelineMs <= 1800L) {
                nextBeatIdx = totalBeats - 1
                clipDuration = remainingTimelineMs
            } else {
                val pattern = getRhythmPattern(section, isLongReel, inFinalBurst, isVeryScarceFootage)
                val patternIdx = sectionRhythmIdx[section] ?: 0
                val beatJump = pattern[patternIdx % pattern.size]
                sectionRhythmIdx[section] = patternIdx + 1

                nextBeatIdx = minOf(beatIdx + beatJump - 1, totalBeats - 1)
                clipDuration = beatTimestamps[nextBeatIdx] - currentTimelineMs

                while (clipDuration < MIN_CUT_TIMELINE_MS && nextBeatIdx < totalBeats - 1) {
                    nextBeatIdx++
                    clipDuration = beatTimestamps[nextBeatIdx] - currentTimelineMs
                }

                if (clipDuration <= 0L || currentTimelineMs + clipDuration > targetMs) {
                    clipDuration = remainingTimelineMs
                }
            }

            if (clipDuration <= 0L) break

            val isHighEnergy = section == MusicSection.DROP || section == MusicSection.CLIMAX

            val minFreshThresholdMs = maxOf(650L, (clipDuration * 0.60f).toLong())
            val realVideoIdx = selectNextVideoIndex(
                videoCount = n,
                section = section,
                isHighEnergy = isHighEnergy,
                minFreshThresholdMs = minFreshThresholdMs,
                recentCooldownQueue = recentCooldownQueue,
                motionRank = motionRank,
                usableDurationsMs = usableDurationsMs,
                getFreshUnseenMs = ::getFreshUnseenMs,
                getAverageUtilization = ::getAverageUtilization
            )

            val video = videos[realVideoIdx]
            val maxDuration = video.durationMs
            val videoFreshMs = getFreshUnseenMs(realVideoIdx)

            if (!isScarceFootage && isHighEnergy && !inFinalBurst && (totalBeats - beatIdx > 4) && Random.nextFloat() < 0.35f) {
                val freshPeaksForRamp = video.actionPeaks.filter { peak ->
                    val checkStart = (peak - 900L).coerceAtLeast(0L)
                    val checkEnd = (peak + 900L).coerceAtMost(maxDuration)
                    getWindowUsageStats(realVideoIdx, checkStart, checkEnd) < 0.25f
                }
                if (freshPeaksForRamp.isNotEmpty()) {
                    val peakMs = freshPeaksForRamp.random()
                    val rampBeatIdx = minOf(beatIdx + 4, totalBeats - 1)
                    var rampClipDuration = beatTimestamps[rampBeatIdx] - currentTimelineMs
                    if (currentTimelineMs + rampClipDuration > targetMs) {
                        rampClipDuration = targetMs - currentTimelineMs
                    }
                    if (rampClipDuration >= 1600L && (rampClipDuration * 1.2f).toLong() <= maxDuration) {
                        val slowTimelineMs = (rampClipDuration * 0.5f).toLong()
                        val fastTimelineMs = rampClipDuration - slowTimelineMs
                        val slowVideoMs = maxOf(MIN_SOURCE_SPAN_MS, (slowTimelineMs * 0.4f).toLong())
                        val halfFastVideoMs = maxOf(MIN_SOURCE_SPAN_MS, (fastTimelineMs * 2.0f).toLong() / 2L)
                        val totalSourceMs = halfFastVideoMs * 2L + slowVideoMs

                        val rampStartMs = minOf(
                            maxOf(0L, peakMs - (slowVideoMs / 2L) - halfFastVideoMs),
                            maxOf(0L, maxDuration - totalSourceMs)
                        )
                        val rampEndMs = rampStartMs + totalSourceMs

                        if (rampEndMs <= maxDuration) {
                            plan.add(EditClipPlan(realVideoIdx, rampStartMs, rampStartMs + halfFastVideoMs, 2.0, section.name))
                            plan.add(EditClipPlan(realVideoIdx, rampStartMs + halfFastVideoMs, rampStartMs + halfFastVideoMs + slowVideoMs, 0.4, section.name))
                            plan.add(EditClipPlan(realVideoIdx, rampStartMs + halfFastVideoMs + slowVideoMs, rampEndMs, 2.0, section.name))

                            markSegmentUsed(realVideoIdx, rampStartMs, rampEndMs)
                            updateCooldown(recentCooldownQueue, realVideoIdx, fullCooldownSize)

                            val actualRampTimelineMs = ((halfFastVideoMs / 2.0) * 2.0 + (slowVideoMs / 0.4)).roundToLong()
                            currentTimelineMs += actualRampTimelineMs
                            cutCounter++
                            continue
                        }
                    }
                }
            }

            val chosenSpeed = chooseAdaptiveSpeed(
                section = section,
                isHighEnergy = isHighEnergy,
                inFinalBurst = inFinalBurst,
                clipDurationMs = clipDuration,
                isScarceFootage = isScarceFootage,
                isVeryScarceFootage = isVeryScarceFootage,
                hasActionPeaks = video.actionPeaks.isNotEmpty(),
                videoHasFreshScenes = videoFreshMs >= minFreshThresholdMs,
                cutCounter = cutCounter
            )

            val maxTimelineAtSpeedMs = (maxDuration.toDouble() / chosenSpeed.toDouble()).toLong().coerceAtLeast(MIN_SOURCE_SPAN_MS)

            if (clipDuration > maxTimelineAtSpeedMs) {
                var fittedBeatIdx = nextBeatIdx
                while (fittedBeatIdx > beatIdx && (beatTimestamps[fittedBeatIdx] - currentTimelineMs) > maxTimelineAtSpeedMs) {
                    fittedBeatIdx--
                }
                val fittedDuration = beatTimestamps[fittedBeatIdx] - currentTimelineMs
                clipDuration = if (fittedDuration in MIN_CUT_TIMELINE_MS..maxTimelineAtSpeedMs) {
                    fittedDuration
                } else {
                    maxTimelineAtSpeedMs
                }
            }

            val remainderMs = targetMs - (currentTimelineMs + clipDuration)
            if (remainderMs in 1L..450L && clipDuration + remainderMs <= maxTimelineAtSpeedMs) {
                clipDuration += remainderMs
            }

            val sourceSpanMs = (clipDuration.toDouble() * chosenSpeed.toDouble())
                .roundToLong()
                .coerceIn(minOf(MIN_SOURCE_SPAN_MS, maxDuration), maxDuration)

            val startMs = selectBestSegmentStart(
                videoIdx = realVideoIdx,
                video = video,
                sourceSpanMs = sourceSpanMs,
                usableRange = usableBounds[realVideoIdx],
                isHighEnergy = isHighEnergy,
                lastStartMs = lastSegmentStartMs[realVideoIdx],
                getWindowUsageStats = ::getWindowUsageStats
            )
            val actualStartMs = startMs.coerceIn(0L, (maxDuration - sourceSpanMs).coerceAtLeast(0L))
            val actualEndMs = (actualStartMs + sourceSpanMs).coerceAtMost(maxDuration)

            plan.add(EditClipPlan(realVideoIdx, actualStartMs, actualEndMs, chosenSpeed.toDouble(), section.name))
            markSegmentUsed(realVideoIdx, actualStartMs, actualEndMs)
            updateCooldown(recentCooldownQueue, realVideoIdx, fullCooldownSize)

            val actualTimelineAdvanceMs = ((actualEndMs - actualStartMs).toDouble() / chosenSpeed.toDouble())
                .roundToLong()
                .coerceAtLeast(1L)
            currentTimelineMs += actualTimelineAdvanceMs
            cutCounter++
        }

        return plan
    }

    private fun selectNextVideoIndex(
        videoCount: Int,
        section: MusicSection,
        isHighEnergy: Boolean,
        minFreshThresholdMs: Long,
        recentCooldownQueue: ArrayDeque<Int>,
        motionRank: FloatArray,
        usableDurationsMs: LongArray,
        getFreshUnseenMs: (Int) -> Long,
        getAverageUtilization: (Int) -> Float
    ): Int {
        if (videoCount <= 1) return 0

        val lastImmediate = recentCooldownQueue.lastOrNull() ?: -1
        val cooldownSet = recentCooldownQueue.toSet()

        val primaryEligible = (0 until videoCount).filter { it !in cooldownSet }
            .ifEmpty { (0 until videoCount).filter { it != lastImmediate } }

        val freshInPrimary = primaryEligible.filter { getFreshUnseenMs(it) >= minFreshThresholdMs }
        if (freshInPrimary.isNotEmpty()) {
            return freshInPrimary.maxBy { idx ->
                val freshFraction = (getFreshUnseenMs(idx).toFloat() / usableDurationsMs[idx].toFloat()).coerceIn(0f, 1f)
                val utilPenalty = getAverageUtilization(idx) * 0.30f
                val energyScore = when (section) {
                    MusicSection.DROP, MusicSection.CLIMAX -> motionRank[idx] * 0.65f
                    MusicSection.INTRO, MusicSection.BREAKDOWN -> (1f - motionRank[idx]) * 0.45f
                    MusicSection.BUILDUP -> motionRank[idx] * 0.35f
                }
                energyScore + (freshFraction * 0.40f) - utilPenalty + (Random.nextFloat() * 0.07f)
            }
        }

        val freshOutsideImmediate = (0 until videoCount).filter {
            it != lastImmediate && getFreshUnseenMs(it) >= maxOf(1400L, minFreshThresholdMs)
        }
        if (freshOutsideImmediate.isNotEmpty()) {
            return freshOutsideImmediate.maxBy { idx ->
                val cooldownAgeBonus = recentCooldownQueue.indexOf(idx).let { pos ->
                    if (pos < 0) 0.5f else (1f - pos.toFloat() / recentCooldownQueue.size.coerceAtLeast(1)) * 0.4f
                }
                val freshFraction = (getFreshUnseenMs(idx).toFloat() / usableDurationsMs[idx].toFloat()).coerceIn(0f, 1f)
                freshFraction * 0.6f + cooldownAgeBonus
            }
        }

        return primaryEligible.maxBy { idx ->
            val utilization = getAverageUtilization(idx)
            val tieBreaker = if (isHighEnergy) motionRank[idx] * 0.12f else (1f - motionRank[idx]) * 0.08f
            (-utilization * 1.6f) + tieBreaker + (Random.nextFloat() * 0.06f)
        }
    }

    private fun chooseAdaptiveSpeed(
        section: MusicSection,
        isHighEnergy: Boolean,
        inFinalBurst: Boolean,
        clipDurationMs: Long,
        isScarceFootage: Boolean,
        isVeryScarceFootage: Boolean,
        hasActionPeaks: Boolean,
        videoHasFreshScenes: Boolean,
        cutCounter: Int
    ): Float {
        if (isVeryScarceFootage) {
            return when {
                section == MusicSection.INTRO || section == MusicSection.BREAKDOWN -> 0.60f
                inFinalBurst -> if (cutCounter % 2 == 0) 0.75f else 1.0f
                clipDurationMs >= 1100L -> if (hasActionPeaks && isHighEnergy) 0.55f else 0.60f
                else -> 0.75f
            }
        }

        if (isScarceFootage) {
            return when {
                section == MusicSection.INTRO || section == MusicSection.BREAKDOWN -> 0.75f
                inFinalBurst -> 1.0f
                clipDurationMs >= 1200L && (cutCounter % 2 == 0 || hasActionPeaks) -> 0.65f
                else -> 1.0f
            }
        }

        if (isHighEnergy && !inFinalBurst && videoHasFreshScenes && hasActionPeaks && clipDurationMs >= 1300L && Random.nextFloat() < 0.22f) {
            return 0.60f
        }

        return 1.0f
    }

    private fun selectBestSegmentStart(
        videoIdx: Int,
        video: SharedVideoMetadata,
        sourceSpanMs: Long,
        usableRange: LongRange,
        isHighEnergy: Boolean,
        lastStartMs: Long,
        getWindowUsageStats: (Int, Long, Long) -> Float
    ): Long {
        val maxDuration = video.durationMs
        if (sourceSpanMs >= maxDuration) return 0L

        val rangeStart: Long
        val rangeEnd: Long
        if (usableRange.last - usableRange.first >= sourceSpanMs) {
            rangeStart = usableRange.first
            rangeEnd = usableRange.last
        } else {
            rangeStart = 0L
            rangeEnd = maxDuration
        }

        val maxValidStart = (rangeEnd - sourceSpanMs).coerceAtLeast(rangeStart)

        if (isHighEnergy && video.actionPeaks.isNotEmpty()) {
            val freshPeakCandidates = video.actionPeaks.mapNotNull { peak ->
                val candidateStart = (peak - sourceSpanMs / 2L).coerceIn(rangeStart, maxValidStart)
                val avgUsage = getWindowUsageStats(videoIdx, candidateStart, candidateStart + sourceSpanMs)
                if (avgUsage < 0.20f) Pair(candidateStart, avgUsage) else null
            }
            if (freshPeakCandidates.isNotEmpty()) {
                return freshPeakCandidates.minBy { it.second }.first
            }
        }

        var bestStart = rangeStart
        var bestCost = Double.MAX_VALUE
        var t = rangeStart

        while (t <= maxValidStart) {
            val avgUsage = getWindowUsageStats(videoIdx, t, t + sourceSpanMs)

            val proximityPenalty = if (lastStartMs >= 0L) {
                val dist = abs(t - lastStartMs)
                val safeSeparation = maxOf(sourceSpanMs, 1200L)
                if (dist < safeSeparation) {
                    (1.0 - dist.toDouble() / safeSeparation.toDouble()) * 650.0
                } else {
                    0.0
                }
            } else {
                0.0
            }

            val chronologicalBias = ((t - rangeStart).toDouble() / maxOf(1L, maxValidStart - rangeStart).toDouble()) * 12.0
            val jitter = Random.nextDouble(0.0, 8.0)

            val cost = (avgUsage * 1000.0) + proximityPenalty + chronologicalBias + jitter
            if (cost < bestCost) {
                bestCost = cost
                bestStart = t
            }
            t += BIN_SIZE_MS
        }

        return bestStart
    }

    private fun computeNormalizedMotionScores(videos: List<SharedVideoMetadata>): FloatArray {
        val n = videos.size
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(1.0f)

        val minScore = videos.minOf { it.motionScore }
        val maxScore = videos.maxOf { it.motionScore }
        val span = (maxScore - minScore).coerceAtLeast(1e-5f)

        val sortedIndices = videos.indices.sortedBy { videos[it].motionScore }
        val rankScores = FloatArray(n)
        for (rank in sortedIndices.indices) {
            val idx = sortedIndices[rank]
            val rankNorm = rank.toFloat() / (n - 1).toFloat()
            val valueNorm = ((videos[idx].motionScore - minScore) / span).coerceIn(0f, 1f)
            rankScores[idx] = (rankNorm * 0.65f + valueNorm * 0.35f).coerceIn(0f, 1f)
        }
        return rankScores
    }

    private fun updateCooldown(queue: ArrayDeque<Int>, usedIdx: Int, maxCooldown: Int) {
        if (maxCooldown <= 0) return
        queue.remove(usedIdx)
        queue.addLast(usedIdx)
        while (queue.size > maxCooldown) {
            queue.removeFirst()
        }
    }
}
