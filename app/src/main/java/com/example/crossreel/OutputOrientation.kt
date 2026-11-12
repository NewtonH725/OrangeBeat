package com.example.crossreel

import androidx.compose.runtime.Immutable

/**
 * Orientamento e risoluzione del video finale esportato dalla pipeline Media3 Transformer.
 */
enum class OutputOrientation(
    val displayName: String,
    val ratioLabel: String,
    val width: Int,
    val height: Int
) {
    PORTRAIT("Verticale", "9:16", 1080, 1920),
    LANDSCAPE("Orizzontale", "16:9", 1920, 1080);

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()

    val resolutionLabel: String
        get() = "${width}×${height}"

    val formatBadge: String
        get() = "$ratioLabel FHD"
}

/**
 * Modello dati immutabile per il riepilogo in tempo reale delle specifiche tecniche di output.
 */
@Immutable
data class OutputVideoSpecs(
    val resolutionLabel: String,
    val formatBadge: String,
    val frameRateLabel: String,
    val colorDepthLabel: String,
    val colorSpaceBadge: String = "Rec.709"
)
