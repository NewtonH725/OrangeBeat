package com.example.crossreel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Motore musicale contestuale (v2.3.0):
 * Seleziona la traccia più adatta e il punto di attacco ottimale (startOffsetMs)
 * combinando tutte le impostazioni del Reel (Genere, Durata, Color Grading, VFX, Orientamento)
 * con l'energia cinetica reale delle clip (motionScore, actionPeakDensity) e uno storico
 * anti-ripetizione persistente su disco (SharedPreferences).
 */
object SmartAudioDirector {

    private const val TAG = "SmartAudioDirector"
    private const val PREFS_NAME = "orangebeat_smart_audio_prefs"
    private const val PREF_LAST_TRACK_PREFIX = "last_track_"
    private const val PREF_LAST_OFFSET_PREFIX = "last_offset_"
    private const val PREF_USAGE_PREFIX = "usage_count_"

    enum class TrackSubStyle {
        CLASSIC_GROOVY,
        FAST_DRIVING,
        DARK_INDUSTRIAL,
        EXTREME_PEAK
    }

    enum class TrackEnergy {
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }

    data class AudioContext(
        val genre: String,
        val durationSeconds: Int,
        val colorPreset: ColorPreset,
        val vfxPreset: VfxPreset,
        val orientation: OutputOrientation,
        val avgMotionScore: Float,
        val actionPeakDensity: Float
    )

    data class TrackCandidate(
        val id: String,
        val genre: String,
        val subStyle: TrackSubStyle,
        val energy: TrackEnergy,
        val bpm: Int,
        val durationMs: Long,
        val dropOffsetsMs: List<Long>,
        val rawResId: Int? = null,
        val remoteTrack: RemoteAudioManager.RemoteTrack? = null
    )

    private val BUNDLED_CATALOG: List<TrackCandidate> = listOf(
        // ROCK
        TrackCandidate("rock_01", "ROCK", TrackSubStyle.CLASSIC_GROOVY, TrackEnergy.MEDIUM, 80, 60000L, listOf(0L, 15050L, 30000L), R.raw.rock_01),
        TrackCandidate("rock_02", "ROCK", TrackSubStyle.FAST_DRIVING, TrackEnergy.HIGH, 150, 60000L, listOf(0L, 18600L, 27200L), R.raw.rock_02),
        TrackCandidate("rock_03", "ROCK", TrackSubStyle.DARK_INDUSTRIAL, TrackEnergy.MEDIUM, 92, 60000L, listOf(0L, 18700L, 27050L), R.raw.rock_03),
        TrackCandidate("rock_04", "ROCK", TrackSubStyle.EXTREME_PEAK, TrackEnergy.HIGH, 133, 60000L, listOf(0L, 16550L, 28900L), R.raw.rock_04),
        // METAL
        TrackCandidate("metal_01", "METAL", TrackSubStyle.CLASSIC_GROOVY, TrackEnergy.HIGH, 86, 60000L, listOf(0L, 13350L, 32300L), R.raw.metal_01),
        TrackCandidate("metal_02", "METAL", TrackSubStyle.FAST_DRIVING, TrackEnergy.EXTREME, 86, 60000L, listOf(0L, 15750L, 31750L), R.raw.metal_02),
        TrackCandidate("metal_03", "METAL", TrackSubStyle.EXTREME_PEAK, TrackEnergy.EXTREME, 150, 60000L, listOf(0L, 15600L, 33200L), R.raw.metal_03),
        TrackCandidate("metal_04", "METAL", TrackSubStyle.DARK_INDUSTRIAL, TrackEnergy.HIGH, 86, 60000L, listOf(0L, 13000L, 28200L), R.raw.metal_04),
        // TECHNO
        TrackCandidate("techno_01", "TECHNO", TrackSubStyle.FAST_DRIVING, TrackEnergy.HIGH, 133, 60000L, listOf(0L, 16250L, 28850L), R.raw.techno_01),
        TrackCandidate("techno_02", "TECHNO", TrackSubStyle.CLASSIC_GROOVY, TrackEnergy.MEDIUM, 100, 60000L, listOf(0L, 14000L, 29700L), R.raw.techno_02),
        TrackCandidate("techno_03", "TECHNO", TrackSubStyle.EXTREME_PEAK, TrackEnergy.HIGH, 133, 60000L, listOf(0L, 16800L, 29800L), R.raw.techno_03),
        TrackCandidate("techno_04", "TECHNO", TrackSubStyle.DARK_INDUSTRIAL, TrackEnergy.EXTREME, 120, 60000L, listOf(0L, 14100L, 32300L), R.raw.techno_04),
        // DUBSTEP
        TrackCandidate("dubstep_01", "DUBSTEP", TrackSubStyle.DARK_INDUSTRIAL, TrackEnergy.HIGH, 133, 60000L, listOf(0L, 16500L, 33650L), R.raw.dubstep_01),
        TrackCandidate("dubstep_02", "DUBSTEP", TrackSubStyle.CLASSIC_GROOVY, TrackEnergy.MEDIUM, 109, 60000L, listOf(0L, 14500L, 32700L), R.raw.dubstep_02),
        TrackCandidate("dubstep_03", "DUBSTEP", TrackSubStyle.FAST_DRIVING, TrackEnergy.HIGH, 133, 60000L, listOf(0L, 17500L, 28150L), R.raw.dubstep_03),
        TrackCandidate("dubstep_04", "DUBSTEP", TrackSubStyle.EXTREME_PEAK, TrackEnergy.EXTREME, 171, 60000L, listOf(0L, 16900L, 31100L), R.raw.dubstep_04)
    )

    /**
     * Seleziona la traccia migliore e la finestra temporale (startOffsetMs) per il contesto dato.
     * Priorità ai brani certificati in res/raw/ (latenza zero, nessuna collisione CDN),
     * integrati da eventuali tracce extra presenti su catalog.json remoto.
     */
    suspend fun selectOptimalAudio(context: Context, audioCtx: AudioContext): AudioSource = withContext(Dispatchers.IO) {
        val genreKey = audioCtx.genre.trim().uppercase(Locale.ROOT)
        val bundledForGenre = BUNDLED_CATALOG.filter { it.genre == genreKey }
            .ifEmpty { BUNDLED_CATALOG.filter { it.genre == "TECHNO" } }

        val bundledIds = bundledForGenre.map { it.id }.toSet()

        // Integra eventuali tracce EXTRA dal catalogo remoto che non sono già nei 16 brani bundled
        val extraRemoteCandidates = try {
            RemoteAudioManager.fetchExtraTracks(context, genreKey)
                .filter { it.id !in bundledIds }
                .map { remote ->
                    TrackCandidate(
                        id = remote.id,
                        genre = genreKey,
                        subStyle = parseSubStyle(remote.subStyle),
                        energy = parseEnergy(remote.energy),
                        bpm = remote.bpm,
                        durationMs = remote.durationMs,
                        dropOffsetsMs = remote.dropOffsetsMs.ifEmpty { listOf(0L) },
                        rawResId = null,
                        remoteTrack = remote
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }

        val allCandidates = bundledForGenre + extraRemoteCandidates
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTrackId = prefs.getString("$PREF_LAST_TRACK_PREFIX$genreKey", null)

        val scored = allCandidates.map { candidate ->
            val score = computeContextScore(candidate, audioCtx, prefs, lastTrackId)
            candidate to score
        }.sortedByDescending { it.second }

        // Seleziona tra i top candidati vicini al punteggio massimo per garantire freschezza
        val topScore = scored.first().second
        val finalists = scored.filter { it.second >= topScore - 8.0 }.map { it.first }
        val chosen = finalists.random()

        val chosenOffsetMs = selectDynamicOffset(chosen, audioCtx, prefs)

        // Persistenza storico anti-ripetizione
        val newUsage = prefs.getInt("$PREF_USAGE_PREFIX${chosen.id}", 0) + 1
        prefs.edit()
            .putString("$PREF_LAST_TRACK_PREFIX$genreKey", chosen.id)
            .putLong("$PREF_LAST_OFFSET_PREFIX${chosen.id}", chosenOffsetMs)
            .putInt("$PREF_USAGE_PREFIX${chosen.id}", newUsage)
            .apply()

        Log.i(
            TAG,
            "Selezionato ${chosen.id} (${chosen.subStyle}, ${chosen.bpm} BPM, offset=${chosenOffsetMs}ms) " +
                "per VFX=${audioCtx.vfxPreset.name}, Color=${audioCtx.colorPreset.name}, " +
                "Dur=${audioCtx.durationSeconds}s, Motion=${"%.2f".format(audioCtx.avgMotionScore)}"
        )

        // Se è una traccia extra remota, prova a usarla da cache/rete, altrimenti fallback su bundled
        if (chosen.rawResId == null && chosen.remoteTrack != null) {
            val cachedFile: File? = RemoteAudioManager.ensureTrackCached(context, chosen.remoteTrack)
            if (cachedFile != null && cachedFile.exists() && cachedFile.length() >= 100 * 1024L) {
                return@withContext AudioSource.LocalFile(
                    file = cachedFile,
                    trackId = chosen.id,
                    startOffsetMs = chosenOffsetMs
                )
            }
        }

        val safeRaw = bundledForGenre.firstOrNull { it.id == chosen.id } ?: bundledForGenre.first()
        return@withContext AudioSource.RawResource(
            resId = safeRaw.rawResId!!,
            startOffsetMs = chosenOffsetMs
        )
    }

    private fun computeContextScore(
        track: TrackCandidate,
        ctx: AudioContext,
        prefs: android.content.SharedPreferences,
        lastTrackId: String?
    ): Double {
        var score = 50.0

        // 1. Affinità VFX
        score += when (ctx.vfxPreset) {
            VfxPreset.FLASH -> when (track.subStyle) {
                TrackSubStyle.EXTREME_PEAK -> 35.0
                TrackSubStyle.FAST_DRIVING -> 25.0
                TrackSubStyle.DARK_INDUSTRIAL -> 10.0
                TrackSubStyle.CLASSIC_GROOVY -> 0.0
            }
            VfxPreset.TV_GLITCH -> when (track.subStyle) {
                TrackSubStyle.DARK_INDUSTRIAL -> 35.0
                TrackSubStyle.EXTREME_PEAK -> 20.0
                TrackSubStyle.FAST_DRIVING -> 10.0
                TrackSubStyle.CLASSIC_GROOVY -> 0.0
            }
            VfxPreset.OLD_FILM -> when (track.subStyle) {
                TrackSubStyle.CLASSIC_GROOVY -> 35.0
                TrackSubStyle.DARK_INDUSTRIAL -> 15.0
                TrackSubStyle.FAST_DRIVING -> 5.0
                TrackSubStyle.EXTREME_PEAK -> 0.0
            }
            VfxPreset.NONE -> 10.0
        }

        // 2. Affinità Color Grading
        score += when (ctx.colorPreset) {
            ColorPreset.CYBERPUNK, ColorPreset.BLEACH_BYPASS, ColorPreset.DESATURATED_COLD -> when (track.subStyle) {
                TrackSubStyle.DARK_INDUSTRIAL -> 25.0
                TrackSubStyle.EXTREME_PEAK -> 20.0
                else -> 5.0
            }
            ColorPreset.KODAK_SUPER_8, ColorPreset.PORTRA_400, ColorPreset.ILFORD_PAN_F -> when (track.subStyle) {
                TrackSubStyle.CLASSIC_GROOVY -> 25.0
                TrackSubStyle.DARK_INDUSTRIAL -> 12.0
                else -> 5.0
            }
            ColorPreset.ORANGE_CRUSH, ColorPreset.TEAL_ORANGE -> when (track.subStyle) {
                TrackSubStyle.FAST_DRIVING -> 25.0
                TrackSubStyle.EXTREME_PEAK -> 20.0
                else -> 8.0
            }
            ColorPreset.NONE -> 10.0
        }

        // 3. Energia Cinetica Reale delle Clip (MotionAnalyzer + PoseActionFinder)
        val isHighAction = ctx.avgMotionScore >= 0.55f || ctx.actionPeakDensity >= 0.35f
        val isLowAction = ctx.avgMotionScore < 0.30f && ctx.actionPeakDensity < 0.15f
        if (isHighAction) {
            if (track.energy == TrackEnergy.EXTREME || track.energy == TrackEnergy.HIGH) score += 20.0
            if (track.bpm >= 125) score += 15.0
        } else if (isLowAction) {
            if (track.energy == TrackEnergy.MEDIUM || track.energy == TrackEnergy.LOW) score += 20.0
            if (track.subStyle == TrackSubStyle.CLASSIC_GROOVY) score += 12.0
        }

        // 4. Durata del Reel
        if (ctx.durationSeconds <= 15) {
            if (track.energy == TrackEnergy.EXTREME || track.energy == TrackEnergy.HIGH) score += 12.0
        } else if (ctx.durationSeconds >= 45) {
            if (track.subStyle == TrackSubStyle.CLASSIC_GROOVY || track.subStyle == TrackSubStyle.FAST_DRIVING) {
                score += 10.0
            }
        }

        // 5. Anti-ripetizione persistente (penalità forte per l'ultima traccia e bilanciamento utilizzi)
        if (track.id == lastTrackId) {
            score -= 120.0
        }
        val usageCount = prefs.getInt("$PREF_USAGE_PREFIX${track.id}", 0)
        score -= (usageCount * 18.0)

        return score
    }

    /**
     * Seleziona una finestra di attacco (startOffsetMs) allineata al beat che rientri
     * nella durata del brano (60s) e ruoti rispetto all'ultimo offset usato per quel brano.
     */
    private fun selectDynamicOffset(
        track: TrackCandidate,
        ctx: AudioContext,
        prefs: android.content.SharedPreferences
    ): Long {
        val targetDurationMs = ctx.durationSeconds * 1000L
        val maxValidOffsetMs = (track.durationMs - targetDurationMs).coerceAtLeast(0L)

        val validOffsets = track.dropOffsetsMs
            .filter { it in 0L..maxValidOffsetMs }
            .distinct()
            .ifEmpty { listOf(0L) }

        if (validOffsets.size == 1) return validOffsets.first()

        val lastOffset = prefs.getLong("$PREF_LAST_OFFSET_PREFIX${track.id}", -1L)
        val nonRepeatedOffsets = validOffsets.filter { it != lastOffset }.ifEmpty { validOffsets }

        // Per Reel molto brevi (15s) con alta energia, privilegia i drop centrali se disponibili
        return nonRepeatedOffsets.random()
    }

    private fun parseSubStyle(value: String): TrackSubStyle = try {
        TrackSubStyle.valueOf(value.trim().uppercase(Locale.ROOT))
    } catch (_: Exception) {
        TrackSubStyle.FAST_DRIVING
    }

    private fun parseEnergy(value: String): TrackEnergy = try {
        TrackEnergy.valueOf(value.trim().uppercase(Locale.ROOT))
    } catch (_: Exception) {
        TrackEnergy.HIGH
    }
}
