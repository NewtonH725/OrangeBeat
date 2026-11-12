package com.example.crossreel

import java.util.Locale

class AudioBeatDetector {

    companion object {
        // Traccia globale del numero di selezioni per risorsa audio (rotazione equa)
        private val trackUsageCount = mutableMapOf<Int, Int>()

        // Memorizza l'ultimo resId selezionato per ciascun genere (anti-ripetizione consecutiva)
        private val lastUsedTrackByGenre = mutableMapOf<String, Int>()

        // 4 Generi ufficiali Orange-Beat (16 tracce autentiche in studio, 4 per genere, zero filler):
        private val ROCK_POOL = listOf(
            R.raw.rock_01,
            R.raw.rock_02,
            R.raw.rock_03,
            R.raw.rock_04
        )

        private val METAL_POOL = listOf(
            R.raw.metal_01,
            R.raw.metal_02,
            R.raw.metal_03,
            R.raw.metal_04
        )

        private val TECHNO_POOL = listOf(
            R.raw.techno_01,
            R.raw.techno_02,
            R.raw.techno_03,
            R.raw.techno_04
        )

        private val DUBSTEP_POOL = listOf(
            R.raw.dubstep_01,
            R.raw.dubstep_02,
            R.raw.dubstep_03,
            R.raw.dubstep_04
        )
    }

    /**
     * Offset di partenza per le tracce raw.
     * Tutte le tracce del nuovo catalogo v3 sono già pre-ritagliate con Drop-Hunter sul beat iniziale (offset = 0).
     */
    fun getRawTrackOffsetMs(@Suppress("UNUSED_PARAMETER") resId: Int): Long = 0L

    /**
     * Restituisce la sorgente audio locale configurata con il punto di attacco sul ritmo (zero intro lente).
     */
    fun getLocalAudioSource(soundtrack: String): AudioSource.RawResource {
        val resId = getAudioResourceId(soundtrack)
        val offset = getRawTrackOffsetMs(resId)
        return AudioSource.RawResource(resId = resId, startOffsetMs = offset)
    }

    /**
     * Seleziona una traccia audio locale (offline fallback) garantendo rotazione uniforme e
     * prevenzione delle ripetizioni consecutive per lo stesso genere.
     *
     * @param soundtrack Genere musicale ("Rock", "Metal", "Techno", "Dubstep")
     * @param durationSeconds Durata video in secondi (mantenuta per retrocompatibilità con MainActivity)
     */
    @Synchronized
    fun getAudioResourceId(
        soundtrack: String,
        @Suppress("UNUSED_PARAMETER") durationSeconds: Int = 0
    ): Int {
        val genreKey = soundtrack.trim().uppercase(Locale.ROOT)
        val pool = when (genreKey) {
            "ROCK" -> ROCK_POOL
            "METAL" -> METAL_POOL
            "TECHNO" -> TECHNO_POOL
            "DUBSTEP" -> DUBSTEP_POOL
            else -> TECHNO_POOL
        }

        // 1. Escludi l'ultimo resId registrato per questo genere se il pool ha più di 1 elemento
        val lastUsed = lastUsedTrackByGenre[genreKey]
        val nonConsecutiveCandidates = if (pool.size > 1 && lastUsed != null) {
            pool.filter { it != lastUsed }
        } else {
            pool
        }
        val poolCandidates = nonConsecutiveCandidates.ifEmpty { pool }

        // 2. Tra i candidati rimanenti, individua il conteggio minimo di utilizzi
        val minCount = poolCandidates.minOf { trackUsageCount[it] ?: 0 }

        // 3. Filtra solo le tracce con il conteggio minimo
        val leastUsedCandidates = poolCandidates.filter { (trackUsageCount[it] ?: 0) == minCount }

        // 4. Estrazione casuale tra le tracce a utilizzo minimo
        val selectedResId = leastUsedCandidates.random()

        // 5. Aggiornamento contatori e memoria dell'ultimo brano
        trackUsageCount[selectedResId] = (trackUsageCount[selectedResId] ?: 0) + 1
        lastUsedTrackByGenre[genreKey] = selectedResId

        return selectedResId
    }
}
