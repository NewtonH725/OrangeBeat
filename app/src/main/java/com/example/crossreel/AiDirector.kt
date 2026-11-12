package com.example.crossreel

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

class AiDirector {

    companion object {
        private const val BIN_SIZE_MS = 100L
        private const val MIN_CUT_TIMELINE_MS = 380L
        private const val MIN_SOURCE_SPAN_MS = 260L

        /**
         * Calcola l'intervallo utile di una clip sorgente (5%..95%, con margine massimo di 500ms ai bordi),
         * recuperando fino al +35% di girato reale rispetto alla vecchia finestra 20%..80%.
         */
        fun computeUsableBounds(durationMs: Long): LongRange {
            if (durationMs <= 0L) return 0L..0L
            val margin = minOf((durationMs * 0.05f).toLong(), 500L)
            val uStart = margin
            val uEnd = durationMs - margin
            return if (uEnd - uStart >= 1500L) (uStart..uEnd) else (0L..durationMs)
        }

        /**
         * Restituisce i millisecondi netti utilizzabili di una singola clip sorgente.
         */
        fun computeUsableDurationMs(durationMs: Long): Long {
            val bounds = computeUsableBounds(durationMs)
            return (bounds.last - bounds.first).coerceAtLeast(0L)
        }
    }

    // ========================================================================================
    // Struttura Musicale
    // Divide la timeline in sezioni emotive (intro, build-up, drop, breakdown, climax)
    // con pattern ritmici dedicati e progressione in crescendo per i Reel lunghi (> 30s).
    // ========================================================================================

    enum class MusicSection(val startPct: Float, val endPct: Float) {
        INTRO(0.00f, 0.15f),       // Atmosfera, tagli ampi
        BUILDUP(0.15f, 0.35f),     // Accelerazione progressiva
        DROP(0.35f, 0.60f),        // Alta energia ritmica
        BREAKDOWN(0.60f, 0.75f),   // Respiro cinematografico
        CLIMAX(0.75f, 1.00f)       // Crescendo finale + raffica esplosiva + chiusura
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

    /**
     * Seleziona il pattern ritmico (in numero di beat per taglio) adattandolo:
     * - alla durata complessiva del Reel (nei Reel > 30s il CLIMAX usa una progressione in crescendo
     *   concentrando la raffica da 1 beat negli ultimi ~5.5 secondi prima della chiusura);
     * - alla disponibilità di girato (allargando leggermente i tagli quando il girato è scarso).
     */
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
                // Groove potente ma respirabile sui Reel lunghi per non bruciare tutte le clip a metà video
                listOf(2, 2, 1, 1, 2, 4, 2, 1, 1)
            } else {
                listOf(1, 1, 2, 1, 1, 2, 1, 1)
            }
            MusicSection.BREAKDOWN -> listOf(8, 4, 8)
            MusicSection.CLIMAX -> if (isLongReel) {
                if (inFinalBurst) {
                    // Ultimi ~5.5 secondi prima dell'inquadratura finale: raffica esplosiva
                    listOf(1, 1, 2, 1, 1, 1, 2)
                } else {
                    // Prima parte del Climax (es. 45s..53s su 60s): progressione in crescendo (2-4 beat)
                    listOf(4, 2, 4, 2, 2, 2)
                }
            } else {
                listOf(2, 2, 1, 1, 1, 2, 1, 4)
            }
        }

        // Se il girato caricato è molto inferiore alla durata target, ampliamo leggermente i tagli
        // fuori dalla raffica finale per valorizzare lo Slow-Motion adattivo ed evitare ripetizioni.
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

    fun generateEditPlanLocal(
        videos: List<VideoMetadata>,
        beatTimestamps: List<Long>,
        targetDurationSec: Int
    ): String {
        val jsonArray = JSONArray()
        var currentTimelineMs = 0L
        val targetMs = targetDurationSec * 1000L
        val totalBeats = beatTimestamps.size

        if (videos.isEmpty() || beatTimestamps.isEmpty() || targetMs <= 0L) return "[]"

        val n = videos.size
        val isLongReel = targetDurationSec > 30

        // 1. Pre-calcolo intervalli utili (5%..95%) e mappa termica di utilizzo a bin da 100ms per ogni clip
        val usableBounds = Array(n) { idx -> computeUsableBounds(videos[idx].durationMs) }
        val usableDurationsMs = LongArray(n) { idx ->
            (usableBounds[idx].last - usableBounds[idx].first).coerceAtLeast(1L)
        }
        val usageBins = Array(n) { idx ->
            val binCount = maxOf(1, ((videos[idx].durationMs + BIN_SIZE_MS - 1L) / BIN_SIZE_MS).toInt())
            IntArray(binCount) { 0 }
        }
        val lastSegmentStartMs = LongArray(n) { -1L }

        // 2. Rapporto di copertura del girato rispetto alla durata richiesta del Reel
        val totalUsableMs = usableDurationsMs.sum()
        val coverageRatio = totalUsableMs.toFloat() / targetMs.toFloat()
        val isScarceFootage = coverageRatio < 1.10f
        val isVeryScarceFootage = coverageRatio < 0.70f

        // 3. Punteggio di movimento normalizzato (combinazione rank + min-max) in [0.0 .. 1.0]
        val motionRank = computeNormalizedMotionScores(videos)

        // 4. Coda di Cooldown dinamica (Smart Deck anti-ping-pong)
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
            // Avanza beatIdx oltre i battiti già superati o troppo ravvicinati (< 180ms) per prevenire micro-tagli
            while (beatIdx < totalBeats - 1 && beatTimestamps[beatIdx] < currentTimelineMs + 180L) {
                beatIdx++
            }

            val remainingTimelineMs = targetMs - currentTimelineMs
            val section = getSectionForTimeline(currentTimelineMs, targetMs)

            // Nei Reel > 30s la raffica finale scatta negli ultimi ~5.5s (prima dello shot conclusivo da 1.8s)
            val inFinalBurst = isLongReel &&
                section == MusicSection.CLIMAX &&
                remainingTimelineMs in 1801L..7300L

            if (section != currentSection || (inFinalBurst && !enteredFinalBurst)) {
                currentSection = section
                if (inFinalBurst) enteredFinalBurst = true
                sectionRhythmIdx[section] = 0
            }

            // Ultimi <= 1.8s (in corrispondenza del Fade-Out audio da 1.5s): un'unica inquadratura conclusiva pulita
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

                // Protezione BPM estremi (170-200 BPM): evita tagli più corti di MIN_CUT_TIMELINE_MS
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

            // --- SELEZIONE INTELLIGENTE DELLA CLIP (Freshness-First + Motion Priority + Cooldown) ---
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

            // --- SCELTA VELOCITÀ (Speed-Ramp su girato abbondante / Slow-Motion Adattivo su girato scarso) ---
            // 1. Tenta Speed-Ramp dinamico (2.0x -> 0.4x -> 2.0x) SOLO se il girato è abbondante e c'è un picco inedito
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
                            appendClipJson(jsonArray, realVideoIdx, rampStartMs, rampStartMs + halfFastVideoMs, 2.0, section)
                            appendClipJson(jsonArray, realVideoIdx, rampStartMs + halfFastVideoMs, rampStartMs + halfFastVideoMs + slowVideoMs, 0.4, section)
                            appendClipJson(jsonArray, realVideoIdx, rampStartMs + halfFastVideoMs + slowVideoMs, rampEndMs, 2.0, section)

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

            // 2. Determinazione velocità singola (1.0x standard o Slow-Motion adattivo 0.55x..0.75x)
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

            // Durata massima copribile in timeline dal video sorgente alla velocità scelta
            val maxTimelineAtSpeedMs = (maxDuration.toDouble() / chosenSpeed.toDouble()).toLong().coerceAtLeast(MIN_SOURCE_SPAN_MS)

            // SECURITY CLAMP CON ANCORAGGIO SUL BATTITO
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

            // ANTI-GLITCH FINALE: assorbe eventuali residui < 450ms a fine timeline
            val remainderMs = targetMs - (currentTimelineMs + clipDuration)
            if (remainderMs in 1L..450L && clipDuration + remainderMs <= maxTimelineAtSpeedMs) {
                clipDuration += remainderMs
            }

            val sourceSpanMs = (clipDuration.toDouble() * chosenSpeed.toDouble())
                .roundToLong()
                .coerceIn(minOf(MIN_SOURCE_SPAN_MS, maxDuration), maxDuration)

            // --- ESTRAZIONE DEL SEGMENTO PIÙ INEDITO E DISTANTE DALL'ULTIMO USATO ---
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

            appendClipJson(jsonArray, realVideoIdx, actualStartMs, actualEndMs, chosenSpeed.toDouble(), section)
            markSegmentUsed(realVideoIdx, actualStartMs, actualEndMs)
            updateCooldown(recentCooldownQueue, realVideoIdx, fullCooldownSize)

            val actualTimelineAdvanceMs = ((actualEndMs - actualStartMs).toDouble() / chosenSpeed.toDouble())
                .roundToLong()
                .coerceAtLeast(1L)
            currentTimelineMs += actualTimelineAdvanceMs
            cutCounter++
        }

        return jsonArray.toString()
    }

    /**
     * Seleziona la prossima clip rispettando:
     * 1. Cooldown dinamico (impedisce l'effetto ping-pong A-B-A-B).
     * 2. Priorità alle clip più movimentate FINCHÉ hanno scene inedite; esaurite quelle,
     *    include tutte le altre clip pur di non ripetere sempre le stesse.
     */
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

        // 1° Livello: clip fuori cooldown che possiedono ancora scene 100% inedite
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

        // 2° Livello: se tutte le clip fuori cooldown sono esaurite, verifica se esiste una clip molto lunga
        // in cooldown (ma NON l'ultima appena mostrata!) che ha ancora abbondante girato totalmente inedito
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

        // 3° Livello (Secondo giro / Pass 2+): tutto il girato è stato visto almeno una volta.
        // Tutte le clip fuori dall'intero cooldown ruotano in modo inclusivo privilegiando quella meno sfruttata.
        return primaryEligible.maxBy { idx ->
            val utilization = getAverageUtilization(idx)
            val tieBreaker = if (isHighEnergy) motionRank[idx] * 0.12f else (1f - motionRank[idx]) * 0.08f
            (-utilization * 1.6f) + tieBreaker + (Random.nextFloat() * 0.06f)
        }
    }

    /**
     * Calcola la velocità di riproduzione della clip:
     * - 1.0f quando il girato è abbondante (salvo occasionali slow-motion eroici sui picchi);
     * - Slow-Motion fluido adattivo (0.55f..0.75f) quando il girato è inferiore alla durata del Reel,
     *   dilatando elegantemente le scene d'azione senza perdere l'ancoraggio ai battiti musicali.
     */
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

        // Girato abbondante: slow-motion atletico occasionale sui tagli medi ad alta energia con picco d'azione
        if (isHighEnergy && !inFinalBurst && videoHasFreshScenes && hasActionPeaks && clipDurationMs >= 1300L && Random.nextFloat() < 0.22f) {
            return 0.60f
        }

        return 1.0f
    }

    /**
     * Individua in modo deterministico sulla griglia a 100ms la porzione del video meno utilizzata
     * e temporalmente più distante dall'ultimo segmento estratto dallo stesso file.
     */
    private fun selectBestSegmentStart(
        videoIdx: Int,
        video: VideoMetadata,
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

        // 1. Nelle sezioni ad alta energia, se esiste un actionPeak la cui intera finestra è ancora fresca, centraci il taglio
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

        // 2. Scansione completa della griglia a passi di 100ms per trovare il minimo costo globale (zero tentativi ciechi)
        var bestStart = rangeStart
        var bestCost = Double.MAX_VALUE
        var t = rangeStart

        while (t <= maxValidStart) {
            val avgUsage = getWindowUsageStats(videoIdx, t, t + sourceSpanMs)

            // Penalità di prossimità rispetto all'ultimo segmento estratto da questa stessa clip
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

            // Leggera preferenza cronologica in avanti a parità di freschezza + micro-jitter
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

    private fun computeNormalizedMotionScores(videos: List<VideoMetadata>): FloatArray {
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

    private fun appendClipJson(
        jsonArray: JSONArray,
        videoIdx: Int,
        startMs: Long,
        endMs: Long,
        speed: Double,
        section: MusicSection
    ) {
        val obj = JSONObject()
        obj.put("videoIndex", videoIdx)
        obj.put("startTimeMs", startMs)
        obj.put("endTimeMs", endMs)
        obj.put("speed", speed)
        obj.put("section", section.name)
        jsonArray.put(obj)
    }
}

data class VideoMetadata(
    val uri: Uri,
    val durationMs: Long,
    val motionScore: Float = 0f,
    val actionPeaks: List<Long> = emptyList()
)
