package com.example.crossreel

import androidx.annotation.RawRes
import java.io.File

/**
 * Rappresenta la sorgente della traccia musicale utilizzata per l'analisi dei beat
 * e il montaggio finale Media3 Transformer.
 */
sealed class AudioSource {

    abstract val startOffsetMs: Long

    /**
     * File audio MP3 scaricato dal database remoto (GitHub Raw) e salvato nella cache dell'app.
     * È già stato pre-tagliato sul Drop dallo script Drop-Hunter, quindi inizia a 0L.
     */
    data class LocalFile(
        val file: File,
        val trackId: String,
        override val startOffsetMs: Long = 0L
    ) : AudioSource()

    /**
     * Risorsa audio locale integrata nel pacchetto APK (res/raw/) utilizzata come fallback offline sicuro.
     * startOffsetMs definisce il punto esatto in cui salta l'intro per andare dritto al drop/ritmo.
     */
    data class RawResource(
        @param:RawRes val resId: Int,
        override val startOffsetMs: Long = 0L
    ) : AudioSource()
}
