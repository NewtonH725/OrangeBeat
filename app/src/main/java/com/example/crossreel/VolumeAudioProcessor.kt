package com.example.crossreel

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Applica un volume costante, un fade-in lineare all'attacco e un fade-out lineare in chiusura.
 * @param volume guadagno costante (1.0 = invariato)
 * @param fadeInDurationMs durata del fade-in in ms dall'inizio dello stream (0 o < 0 = nessun fade-in)
 * @param fadeOutStartMs istante (in ms dall'inizio dello stream) da cui parte il fade-out; < 0 = nessun fade-out
 * @param fadeOutDurationMs durata del fade-out in ms
 */
class VolumeAudioProcessor(
    private val volume: Float = 1.0f,
    private val fadeInDurationMs: Long = 500L,
    private val fadeOutStartMs: Long = -1L,
    private val fadeOutDurationMs: Long = 1500L
) : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var channelCount = 2
    private var framesProcessed = 0L

    // CRITICO: senza questo override, Media3 ignora completamente il processor!
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Gestiamo solo PCM 16-bit: con altri formati il processor resta inattivo (pass-through) invece di corrompere l'audio
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = maxOf(1, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        framesProcessed = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val outputBuffer = replaceOutputBuffer(remaining)

        val inputShorts = inputBuffer.asShortBuffer()
        val outputShorts = outputBuffer.asShortBuffer()

        val fadeInEnabled = fadeInDurationMs > 0L
        val fadeOutEnabled = fadeOutStartMs >= 0L && fadeOutDurationMs > 0L
        var channelIdx = 0

        while (inputShorts.hasRemaining()) {
            val sample = inputShorts.get()
            var gain = volume

            if (fadeInEnabled || fadeOutEnabled) {
                val posMs = framesProcessed * 1000L / sampleRate

                if (fadeInEnabled && posMs < fadeInDurationMs) {
                    val fadeIn = (posMs.toFloat() / fadeInDurationMs.toFloat()).coerceIn(0f, 1f)
                    gain *= fadeIn
                }

                if (fadeOutEnabled && posMs >= fadeOutStartMs) {
                    val fadeOut = (1f - ((posMs - fadeOutStartMs).toFloat() / fadeOutDurationMs.toFloat())).coerceIn(0f, 1f)
                    gain *= fadeOut
                }
            }

            val adjusted = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputShorts.put(adjusted.toShort())

            channelIdx++
            if (channelIdx == channelCount) {
                channelIdx = 0
                framesProcessed++
            }
        }

        inputBuffer.position(inputBuffer.position() + remaining)
        outputBuffer.limit(outputBuffer.capacity())
        outputBuffer.position(0)
    }
}
