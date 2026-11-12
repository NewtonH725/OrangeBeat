package com.example.crossreel.desktop

import com.example.crossreel.ColorPreset
import com.example.crossreel.EditClipPlan
import com.example.crossreel.OutputOrientation
import com.example.crossreel.VfxPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object DesktopVideoEditor {

    suspend fun executeEditPlan(
        videoFiles: List<File>,
        plan: List<EditClipPlan>,
        audioFile: File,
        audioStartOffsetMs: Long,
        targetDurationMs: Long,
        outputFps: Int,
        colorPreset: ColorPreset,
        vfxPreset: VfxPreset,
        outputOrientation: OutputOrientation
    ): File = withContext(Dispatchers.IO) {
        val ffmpeg = FfmpegLocator.findFfmpeg()
            ?: error("FFmpeg non trovato nel sistema. Installa ffmpeg (es. sudo apt install ffmpeg su Ubuntu).")

        require(plan.isNotEmpty()) { "Piano di montaggio vuoto" }

        val outDir = DesktopPaths.getDefaultVideosOutputDir()
        outDir.mkdirs()
        val outputFile = File(outDir, "OrangeBeat_Final_${System.currentTimeMillis()}.mp4")

        val outW = outputOrientation.width
        val outH = outputOrientation.height
        val safeFps = outputFps.coerceIn(24, 120)

        // Mappa gli input video unici usati nel piano
        val usedIndices = plan.map { it.videoIndex }.distinct().filter { it in videoFiles.indices }
        val inputIndexMap = usedIndices.withIndex().associate { (inputIdx, videoIdx) -> videoIdx to inputIdx }
        val audioInputIndex = usedIndices.size

        val cmd = mutableListOf<String>()
        cmd.add(ffmpeg)
        cmd.add("-y")

        for (videoIdx in usedIndices) {
            cmd.add("-i")
            cmd.add(videoFiles[videoIdx].absolutePath)
        }

        // Audio musicale tagliato all'offset Drop-Hunter
        val audioOffsetSec = String.format(Locale.US, "%.3f", audioStartOffsetMs / 1000.0)
        val targetDurSec = String.format(Locale.US, "%.3f", targetDurationMs / 1000.0)
        cmd.add("-ss")
        cmd.add(audioOffsetSec)
        cmd.add("-t")
        cmd.add(targetDurSec)
        cmd.add("-i")
        cmd.add(audioFile.absolutePath)

        val filterParts = mutableListOf<String>()
        val concatLabels = mutableListOf<String>()

        val colorFilter = buildColorChannelMixerFilter(colorPreset)

        plan.forEachIndexed { segIdx, clip ->
            val inputIdx = inputIndexMap[clip.videoIndex] ?: return@forEachIndexed
            val startSec = String.format(Locale.US, "%.3f", clip.startTimeMs / 1000.0)
            val endSec = String.format(Locale.US, "%.3f", clip.endTimeMs / 1000.0)
            val speed = clip.speed.coerceIn(0.25, 4.0)

            val chain = mutableListOf<String>()
            chain.add("trim=start=$startSec:end=$endSec")
            chain.add("setpts=PTS-STARTPTS")
            if (speed != 1.0) {
                val invSpeed = String.format(Locale.US, "%.4f", 1.0 / speed)
                chain.add("setpts=$invSpeed*PTS")
            }

            // 1. Presentation scaling + center crop
            chain.add("scale=$outW:$outH:force_original_aspect_ratio=increase")
            chain.add("crop=$outW:$outH")
            chain.add("fps=$safeFps")
            chain.add("format=yuv420p")

            // 2. Color Grading
            if (colorFilter != null) {
                chain.add(colorFilter)
            }

            // 3. Ken Burns leggero su INTRO / BREAKDOWN
            if (clip.section == "INTRO" || clip.section == "BREAKDOWN") {
                chain.add("scale=iw*1.08:ih*1.08,crop=$outW:$outH:(iw-ow)/2:(ih-oh)/2")
            }

            // 4. VFX
            when (vfxPreset) {
                VfxPreset.FLASH -> {
                    if (clip.section == "DROP" || clip.section == "CLIMAX") {
                        chain.add("eq=brightness='if(lt(t,0.12),0.35*(1-t/0.12),0)'")
                    }
                }
                VfxPreset.OLD_FILM -> {
                    chain.add("noise=alls=14:allf=t+u")
                    chain.add("vignette=angle=PI/4")
                }
                VfxPreset.TV_GLITCH -> {
                    chain.add("rgbashift=rh=4:bh=-4")
                    chain.add("vignette=angle=PI/5")
                }
                VfxPreset.NONE -> Unit
            }

            val outLabel = "[v$segIdx]"
            filterParts.add("[$inputIdx:v]${chain.joinToString(",")}$outLabel")
            concatLabels.add(outLabel)
        }

        filterParts.add("${concatLabels.joinToString("")}concat=n=${concatLabels.size}:v=1:a=0[vout]")

        val fadeOutStartSec = String.format(Locale.US, "%.3f", maxOf(0.0, (targetDurationMs - 1500L) / 1000.0))
        filterParts.add("[$audioInputIndex:a]afade=t=in:st=0:d=0.5,afade=t=out:st=$fadeOutStartSec:d=1.5[aout]")

        cmd.add("-filter_complex")
        cmd.add(filterParts.joinToString(";"))
        cmd.add("-map")
        cmd.add("[vout]")
        cmd.add("-map")
        cmd.add("[aout]")
        cmd.add("-c:v")
        cmd.add("libx264")
        cmd.add("-preset")
        cmd.add("medium")
        cmd.add("-crf")
        cmd.add("19")
        cmd.add("-pix_fmt")
        cmd.add("yuv420p")
        cmd.add("-c:a")
        cmd.add("aac")
        cmd.add("-b:a")
        cmd.add("192k")
        cmd.add("-movflags")
        cmd.add("+faststart")
        cmd.add("-shortest")
        cmd.add(outputFile.absolutePath)

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val logOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0 || !outputFile.exists()) {
            val tail = logOutput.takeLast(800)
            error("FFmpeg export fallito (code=$exitCode): $tail")
        }

        outputFile
    }

    private fun buildColorChannelMixerFilter(preset: ColorPreset): String? {
        if (preset == ColorPreset.NONE) return null
        val m = preset.getMatrixArray()
        // Column-major 4x4 -> RGB row coefficients
        val rr = String.format(Locale.US, "%.3f", m[0].coerceIn(-2f, 2f))
        val rg = String.format(Locale.US, "%.3f", m[4].coerceIn(-2f, 2f))
        val rb = String.format(Locale.US, "%.3f", m[8].coerceIn(-2f, 2f))

        val gr = String.format(Locale.US, "%.3f", m[1].coerceIn(-2f, 2f))
        val gg = String.format(Locale.US, "%.3f", m[5].coerceIn(-2f, 2f))
        val gb = String.format(Locale.US, "%.3f", m[9].coerceIn(-2f, 2f))

        val br = String.format(Locale.US, "%.3f", m[2].coerceIn(-2f, 2f))
        val bg = String.format(Locale.US, "%.3f", m[6].coerceIn(-2f, 2f))
        val bb = String.format(Locale.US, "%.3f", m[10].coerceIn(-2f, 2f))

        return "colorchannelmixer=rr=$rr:rg=$rg:rb=$rb:gr=$gr:gg=$gg:gb=$gb:br=$br:bg=$bg:bb=$bb"
    }
}
