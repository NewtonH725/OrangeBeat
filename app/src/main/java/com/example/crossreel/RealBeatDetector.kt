package com.example.crossreel

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

class RealBeatDetector {

    companion object {
        private const val TAG = "RealBeatDetector"
    }

    suspend fun findBeats(context: Context, audioResId: Int, targetDurationMs: Long): List<Long> =
        findBeats(context, AudioSource.RawResource(audioResId), targetDurationMs)

    suspend fun findBeats(context: Context, audioSource: AudioSource, targetDurationMs: Long): List<Long> = withContext(Dispatchers.Default) {
        val defaultBpm = 128.0

        val deferred = CompletableDeferred<List<Long>>()

        val decoderThread = Thread {
            try {
                val result = decodeAndFindBeats(context, audioSource, targetDurationMs)
                deferred.complete(result)
            } catch (e: Exception) {
                Log.w(TAG, "Decodifica fallita, uso fallback", e)
                deferred.complete(BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs))
            }
        }
        decoderThread.name = "BeatDetector-Worker"
        decoderThread.isDaemon = true
        decoderThread.start()

        val result = withTimeoutOrNull(10000L) { // 10 secondi di timeout
            deferred.await()
        }

        if (result != null) {
            Log.i(TAG, "Beat detection completata: ${result.size} beats rilevati")
            return@withContext result
        } else {
            Log.w(TAG, "Beat detection timeout, uso fallback matematico")
            deferred.cancel()
            return@withContext BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs)
        }
    }

    private fun decodeAndFindBeats(context: Context, audioSource: AudioSource, targetDurationMs: Long): List<Long> {
        val defaultBpm = 128.0
        var audioDurationMs = targetDurationMs
        
        val extractor = MediaExtractor()
        when (audioSource) {
            is AudioSource.LocalFile -> {
                extractor.setDataSource(audioSource.file.absolutePath)
            }
            is AudioSource.RawResource -> {
                val afd: AssetFileDescriptor = context.resources.openRawResourceFd(audioSource.resId)
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                format = f
                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            return BeatDspCore.generateRegularBeats(defaultBpm, targetDurationMs)
        }

        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            audioDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
        }

        extractor.selectTrack(audioTrackIndex)

        // Se è presente uno startOffsetMs (intro da saltare nel fallback offline),
        // posiziona l'estrattore esattamente sul punto del drop/ritmo
        if (audioSource.startOffsetMs > 0L) {
            extractor.seekTo(audioSource.startOffsetMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: run {
                extractor.release()
                return BeatDspCore.generateRegularBeats(defaultBpm, audioDurationMs)
            }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
        val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 2 }

        val samplesPerWindow = sampleRate * 50 / 1000 // 50ms per finestra
        val bytesPerWindow = samplesPerWindow * channelCount * 2 // 16-bit PCM = 2 bytes per canale
        if (bytesPerWindow <= 0) {
            codec.stop(); codec.release(); extractor.release()
            return BeatDspCore.generateRegularBeats(defaultBpm, audioDurationMs)
        }

        // Filtro passa-basso digitale a 1 polo (~160 Hz) per isolare la cassa (kick drum) ed eliminare piatti/voci
        val cutoffHz = 160.0
        val dt = 1.0 / sampleRate
        val lpfAlpha = (2.0 * Math.PI * dt * cutoffHz).coerceIn(0.005, 0.25)
        var lpfState = 0.0

        val info = MediaCodec.BufferInfo()
        val energies = mutableListOf<Double>()
        val timestamps = mutableListOf<Long>()

        var isInputEOS = false
        var isOutputEOS = false
        var bufferedData = ByteArray(0)
        var totalFramesProcessed = 0L

        try {
            // Decodifica finché non finisce il file o abbiamo raggiunto la durata necessaria per il video
            while (!isOutputEOS && (totalFramesProcessed * 1000L / sampleRate) < (targetDurationMs + 2000L) && !Thread.currentThread().isInterrupted) {

                // Input
                if (!isInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(1000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Output
                val outIndex = codec.dequeueOutputBuffer(info, 1000)
                if (outIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }

                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)

                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)

                        // Accumula buffer PCM
                        val newBuf = ByteArray(bufferedData.size + chunk.size)
                        System.arraycopy(bufferedData, 0, newBuf, 0, bufferedData.size)
                        System.arraycopy(chunk, 0, newBuf, bufferedData.size, chunk.size)
                        bufferedData = newBuf

                        // Processa finestre da 50ms calcolando il timestamp lineare e continuo
                        var safety = 0
                        val bytesPerFrame = channelCount * 2
                        while (bufferedData.size >= bytesPerWindow && safety < 2000) {
                            safety++
                            var sumSq = 0.0
                            for (i in 0 until bytesPerWindow step bytesPerFrame) {
                                var channelSum = 0.0
                                for (ch in 0 until channelCount) {
                                    val offset = i + ch * 2
                                    val s = ((bufferedData[offset + 1].toInt() shl 8) or (bufferedData[offset].toInt() and 0xFF)).toShort()
                                    channelSum += s
                                }
                                val mono = channelSum / channelCount
                                // Applicazione LPF sulle frequenze basse
                                lpfState += lpfAlpha * (mono - lpfState)
                                sumSq += lpfState * lpfState
                            }

                            val rms = sqrt(sumSq / samplesPerWindow)
                            energies.add(rms)
                            // Timestamp esatto calcolato dai campioni PCM reali, immune da jitter
                            val exactTimeMs = totalFramesProcessed * 1000L / sampleRate
                            timestamps.add(exactTimeMs)

                            totalFramesProcessed += samplesPerWindow

                            val remaining = ByteArray(bufferedData.size - bytesPerWindow)
                            System.arraycopy(bufferedData, bytesPerWindow, remaining, 0, remaining.size)
                            bufferedData = remaining
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        return BeatDspCore.detectBeatsFromRmsWindows(
            energies = energies,
            timestamps = timestamps,
            targetDurationMs = targetDurationMs,
            defaultBpm = defaultBpm
        )
    }
}
