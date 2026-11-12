package com.example.crossreel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.media.MediaScannerConnection
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn

@OptIn(UnstableApi::class)
class VideoEditor(private val context: Context) {

    companion object {
        const val DEFAULT_OUTPUT_FPS = 30
        const val OUTPUT_COLOR_DEPTH = "8-bit SDR"
        const val OUTPUT_COLOR_SPACE = "Rec.709"

        fun buildOutputSpecs(
            orientation: OutputOrientation,
            detectedFps: Int = DEFAULT_OUTPUT_FPS
        ): OutputVideoSpecs {
            val safeFps = if (detectedFps in 24..120) detectedFps else DEFAULT_OUTPUT_FPS
            return OutputVideoSpecs(
                resolutionLabel = orientation.resolutionLabel,
                formatBadge = orientation.formatBadge,
                frameRateLabel = "$safeFps FPS",
                colorDepthLabel = OUTPUT_COLOR_DEPTH,
                colorSpaceBadge = OUTPUT_COLOR_SPACE
            )
        }
    }

    suspend fun executeEditPlan(
        videos: List<VideoMetadata>,
        jsonPlan: String,
        audioResId: Int,
        targetDurationMs: Long,
        colorPreset: ColorPreset = ColorPreset.NONE,
        vfxPreset: VfxPreset = VfxPreset.NONE,
        outputOrientation: OutputOrientation = OutputOrientation.PORTRAIT
    ): Uri = executeEditPlan(
        videos = videos,
        jsonPlan = jsonPlan,
        audioSource = AudioSource.RawResource(audioResId),
        targetDurationMs = targetDurationMs,
        colorPreset = colorPreset,
        vfxPreset = vfxPreset,
        outputOrientation = outputOrientation
    )

    suspend fun executeEditPlan(
        videos: List<VideoMetadata>,
        jsonPlan: String,
        audioSource: AudioSource,
        targetDurationMs: Long,
        colorPreset: ColorPreset = ColorPreset.NONE,
        vfxPreset: VfxPreset = VfxPreset.NONE,
        outputOrientation: OutputOrientation = OutputOrientation.PORTRAIT
    ): Uri = suspendCancellableCoroutine { continuation ->
        
        try {
            val planArray = JSONArray(jsonPlan)
            val editedMediaItems = mutableListOf<EditedMediaItem>()

            for (i in 0 until planArray.length()) {
                val clip = planArray.getJSONObject(i)
                val videoIndex = clip.getInt("videoIndex")
                val startMs = clip.getLong("startTimeMs")
                val endMs = clip.getLong("endTimeMs")
                val speed = clip.optDouble("speed", 1.0).toFloat()
                
                // FASE 3: Leggi la sezione musicale dal piano di montaggio
                val section = clip.optString("section", "DROP")
                // Il flash è attivo SOLO se l'utente ha esplicitamente selezionato il preset FLASH
                val useFlash = (vfxPreset == VfxPreset.FLASH) && (section == "DROP" || section == "CLIMAX")

                if (videoIndex >= videos.size) continue

                val mediaItem = MediaItem.Builder()
                    .setUri(videos[videoIndex].uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()

                val baseMatrixArray = colorPreset.getMatrixArray()

                // Se l'utente seleziona ColorPreset.NONE e non c'è flash attivo, saltiamo completamente
                // il passaggio RgbMatrix per preservare i colori 100% nativi della fotocamera e massimizzare la velocità
                val needsColorMatrix = colorPreset != ColorPreset.NONE || useFlash
                val colorGradingMatrix = if (needsColorMatrix) {
                    object : androidx.media3.effect.RgbMatrix {
                        private var firstPtsUs = -1L
                        private val boostedMatrix = baseMatrixArray.copyOf()

                        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
                            if (firstPtsUs < 0L) firstPtsUs = presentationTimeUs
                            val relativeUs = presentationTimeUs - firstPtsUs

                            if (useFlash) {
                                val flashDurationUs = 120_000L // 120ms di flash (incisivo sul drop)
                                if (relativeUs < flashDurationUs) {
                                    val intensity = 1.0f - (relativeUs.toFloat() / flashDurationUs)
                                    val boost = 1.0f + (intensity * 1.8f)
                                    for (idx in 0 until 16) {
                                        boostedMatrix[idx] = if (idx % 4 < 3) baseMatrixArray[idx] * boost else baseMatrixArray[idx]
                                    }
                                    return boostedMatrix
                                }
                            }
                            return baseMatrixArray
                        }
                    }
                } else null

                // Effetto Zoom Dinamico (Ken Burns) per sezioni calme (scalato sulla durata reale post-SpeedChange)
                val isCalmSection = section == "INTRO" || section == "BREAKDOWN"
                val safeSpeed = speed.coerceAtLeast(0.1f)
                val clipDurationUs = (((endMs - startMs).toDouble() / safeSpeed.toDouble()) * 1000.0).toLong()
                val kenBurnsMatrix = if (isCalmSection) KenBurnsMatrix(clipDurationUs) else null

                // Presentation è il PRIMO effetto della catena: ritaglia e scala il frame
                // all'orientamento scelto (1080x1920 Portrait o 1920x1080 Landscape) prima
                // che Color Grading, Ken Burns e gli shader VFX elaborino il fotogramma.
                val videoEffectsList = mutableListOf<androidx.media3.common.Effect>(
                    androidx.media3.effect.Presentation.createForWidthAndHeight(
                        outputOrientation.width,
                        outputOrientation.height,
                        androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    )
                )
                if (colorGradingMatrix != null) {
                    videoEffectsList.add(colorGradingMatrix)
                }
                if (speed != 1.0f) {
                    videoEffectsList.add(androidx.media3.effect.SpeedChangeEffect(speed))
                }
                if (kenBurnsMatrix != null) {
                    videoEffectsList.add(kenBurnsMatrix)
                }
                if (vfxPreset != VfxPreset.NONE && vfxPreset != VfxPreset.FLASH) {
                    videoEffectsList.add(VfxEffect(vfxPreset))
                }

                val editedItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(true)
                    .setEffects(
                        androidx.media3.transformer.Effects(
                            emptyList(),
                            videoEffectsList
                        )
                    )
                    .build()
                
                editedMediaItems.add(editedItem)
            }

            val videoSequence = EditedMediaItemSequence(editedMediaItems)

            // Aggiungiamo la traccia audio musicale (tagliata alla durata esatta del video da file locale o risorsa raw)
            val audioMediaItem = when (audioSource) {
                is AudioSource.LocalFile -> {
                    val startMs = audioSource.startOffsetMs
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(audioSource.file))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(startMs + targetDurationMs)
                                .build()
                        )
                        .build()
                }
                is AudioSource.RawResource -> {
                    val audioUri = Uri.parse("android.resource://${context.packageName}/${audioSource.resId}")
                    val startMs = audioSource.startOffsetMs
                    MediaItem.Builder()
                        .setUri(audioUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(startMs + targetDurationMs)
                                .build()
                        )
                        .build()
                }
            }
                
            // Audio: Musica a guadagno unitario + Fade-In di 500ms + fade-out finale di 1.5s
            val musicBoostProcessor = VolumeAudioProcessor(
                volume = 1.0f,
                fadeInDurationMs = 500L,
                fadeOutStartMs = maxOf(0L, targetDurationMs - 1500L),
                fadeOutDurationMs = 1500L
            )
            
            val audioSequence = EditedMediaItemSequence(
                listOf(
                    EditedMediaItem.Builder(audioMediaItem)
                        .setEffects(
                            androidx.media3.transformer.Effects(
                                listOf(musicBoostProcessor),
                                emptyList()
                            )
                        )
                        .build()
                )
            )

            // Mixiamo video e audio
            val composition = Composition.Builder(listOf(videoSequence, audioSequence)).build()

            val outputFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "OrangeBeat_Final_${System.currentTimeMillis()}.mp4"
            )
            outputFile.parentFile?.mkdirs()

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(outputFile.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                        continuation.resume(Uri.fromFile(outputFile))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        continuation.resumeWithException(exportException)
                    }
                })
                .build()

            transformer.start(composition, outputFile.absolutePath)

            continuation.invokeOnCancellation {
                transformer.cancel()
            }

        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}
