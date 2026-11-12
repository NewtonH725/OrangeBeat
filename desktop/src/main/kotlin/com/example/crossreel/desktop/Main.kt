package com.example.crossreel.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.crossreel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Orange-Beat ${DesktopBuildConfig.VERSION_NAME} — Studio Desktop",
        state = rememberWindowState(width = 960.dp, height = 860.dp)
    ) {
        OrangeBeatDesktopTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = AppBackground
            ) {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1200)
                    showSplash = false
                }

                AnimatedContent(targetState = showSplash, label = "SplashTransition") { isSplash ->
                    if (isSplash) {
                        DesktopSplashScreen()
                    } else {
                        DesktopMainScreen(onRequestExit = ::exitApplication)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White)) { append("ORANGE ") }
                    withStyle(SpanStyle(color = FlameOrange)) { append("BEAT") }
                },
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                letterSpacing = 5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "STUDIO DESKTOP • ${DesktopBuildConfig.VERSION_NAME}",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun DesktopMainScreen(onRequestExit: () -> Unit) {
    val soundtracks = listOf("Rock", "Metal", "Techno", "Dubstep")
    val ffmpegAvailable = remember { FfmpegLocator.isAvailable() }

    var selectedOrientation by remember { mutableStateOf(OutputOrientation.PORTRAIT) }
    var selectedVideos by remember { mutableStateOf<List<File>>(emptyList()) }
    var totalUsableSourceMs by remember { mutableLongStateOf(0L) }
    var detectedOutputFps by remember { mutableIntStateOf(30) }
    var selectedDuration by remember { mutableIntStateOf(15) }
    var selectedSoundtrack by remember { mutableStateOf("Rock") }
    var selectedColorPreset by remember { mutableStateOf(ColorPreset.NONE) }
    var selectedVfxPreset by remember { mutableStateOf(VfxPreset.NONE) }

    val outputSpecs by remember(selectedOrientation, detectedOutputFps) {
        derivedStateOf {
            OutputVideoSpecs(
                resolutionLabel = selectedOrientation.resolutionLabel,
                formatBadge = selectedOrientation.formatBadge,
                frameRateLabel = "${detectedOutputFps.coerceIn(24, 120)} FPS",
                colorDepthLabel = "8-bit SDR",
                colorSpaceBadge = "Rec.709"
            )
        }
    }

    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Pronto per il montaggio Desktop.") }
    var savedOutputFile by remember { mutableStateOf<File?>(null) }

    var pendingUpdate by remember { mutableStateOf<DesktopUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        val update = DesktopAppUpdater.checkAndDownloadUpdate()
        if (update != null) {
            pendingUpdate = update
            showUpdateDialog = true
        }
    }

    if (showUpdateDialog && pendingUpdate != null) {
        val update = pendingUpdate!!
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            containerColor = SurfaceContainerHigh,
            titleContentColor = Color.White,
            textContentColor = Color(0xFFDADADA),
            title = {
                Text(
                    text = "Nuova versione disponibile (${update.versionName})",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("È stato scaricato automaticamente l'aggiornamento da GitHub Releases.")
                    Text(
                        text = "Versione attuale: ${DesktopBuildConfig.VERSION_NAME}\nNuova versione: ${update.versionName}\nFile: ${update.installerFileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlameOrange
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val launched = DesktopAppUpdater.launchInstaller(update)
                        if (launched) {
                            showUpdateDialog = false
                            if (update.platform == TargetPlatform.WINDOWS) {
                                onRequestExit()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FlameOrange)
                ) {
                    Text("Installa", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Continua con la versione attuale", color = Color(0xFFB0B0B0))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append("ORANGE ") }
                        withStyle(SpanStyle(color = FlameOrange)) { append("BEAT") }
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "Studio di Montaggio Video AI • ${if (DesktopPaths.isWindows) "Windows" else "Ubuntu / Linux"}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Surface(
                color = SurfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, OutlineVariantColor)
            ) {
                Text(
                    text = DesktopBuildConfig.VERSION_NAME,
                    color = FlameOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        if (!ffmpegAvailable) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = Color(0xFF4A1B0C),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, FlameOrange),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Attenzione: FFmpeg/FFprobe non rilevato nel PATH. Installalo per abilitare analisi e rendering (es. sudo apt install ffmpeg su Ubuntu, oppure winget install Gyan.FFmpeg su Windows).",
                    color = Color(0xFFFFDBCC),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Formato & Clip
        Surface(
            color = SurfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineVariantColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("FORMATO & CLIP", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutputOrientation.entries.forEach { orientation ->
                        val isSelected = orientation == selectedOrientation
                        Surface(
                            color = if (isSelected) PrimaryContainerColor else SurfaceContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSelected) FlameOrange else OutlineVariantColor),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isProcessing) { selectedOrientation = orientation }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(orientation.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(orientation.resolutionLabel, color = if (isSelected) WarmAmber else TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val picked = pickVideoFiles()
                                if (picked.isNotEmpty()) {
                                    statusText = "Analisi metadati clip in corso..."
                                    var extractedFps: Int? = null
                                    var usableAcc = 0L
                                    val valid = withContext(Dispatchers.IO) {
                                        picked.filter { file ->
                                            val probe = DesktopMediaAnalyzer.probeVideo(file)
                                            if (probe.durationMs >= 3000L) {
                                                usableAcc += AiDirectorCore.computeUsableDurationMs(probe.durationMs)
                                                if (extractedFps == null && probe.fps != null) {
                                                    extractedFps = probe.fps
                                                }
                                                true
                                            } else false
                                        }
                                    }
                                    selectedVideos = valid
                                    totalUsableSourceMs = usableAcc
                                    detectedOutputFps = extractedFps ?: 30
                                    statusText = "${valid.size} clip pronte (${picked.size - valid.size} scartate < 3s)."
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = FlameOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Seleziona Clip Video", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = if (selectedVideos.isEmpty()) "Nessuna clip selezionata" else "${selectedVideos.size} clip (${totalUsableSourceMs / 1000}s utili)",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Durata VU Meter
        Surface(
            color = SurfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineVariantColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("DURATA REEL", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                DesktopMixerKnobWithVuMeter(
                    selectedDuration = selectedDuration,
                    onDurationChange = { if (!isProcessing) selectedDuration = it }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Colonna Sonora, Color & VFX
        Surface(
            color = SurfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineVariantColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("COLONNA SONORA", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(soundtracks) { genre ->
                        val active = genre == selectedSoundtrack
                        FilterChip(
                            selected = active,
                            onClick = { if (!isProcessing) selectedSoundtrack = genre },
                            label = { Text(genre, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("COLOR GRADING", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ColorPreset.entries) { preset ->
                        FilterChip(
                            selected = preset == selectedColorPreset,
                            onClick = { if (!isProcessing) selectedColorPreset = preset },
                            label = { Text(preset.title) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("EFFETTI VFX", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VfxPreset.entries) { vfx ->
                        FilterChip(
                            selected = vfx == selectedVfxPreset,
                            onClick = { if (!isProcessing) selectedVfxPreset = vfx },
                            label = { Text(vfx.title) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Specifiche Output & Export
        Surface(
            color = SurfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OutlineVariantColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("SPECIFICHE OUTPUT", color = FlameOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${outputSpecs.formatBadge} • ${outputSpecs.resolutionLabel} • ${outputSpecs.frameRateLabel} • ${outputSpecs.colorDepthLabel} (${outputSpecs.colorSpaceBadge})",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = statusText, color = WarmAmber, fontSize = 13.sp, textAlign = TextAlign.Start)

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedVideos.isEmpty() || !ffmpegAvailable || isProcessing) return@Button
                            isProcessing = true
                            savedOutputFile = null

                            scope.launch {
                                try {
                                    statusText = "1/4 Analisi cinetica clip (SAD 64×64)..."
                                    val analyzed = withContext(Dispatchers.Default) {
                                        selectedVideos.map { file ->
                                            async {
                                                val probe = DesktopMediaAnalyzer.probeVideo(file)
                                                val analysis = DesktopMediaAnalyzer.analyzeMotionAndPeaks(file, probe.durationMs)
                                                SharedVideoMetadata(
                                                    id = file.absolutePath,
                                                    durationMs = probe.durationMs,
                                                    motionScore = analysis.motionScore,
                                                    actionPeaks = analysis.actionPeaksMs
                                                )
                                            }
                                        }.awaitAll()
                                    }

                                    val avgMotion = if (analyzed.isNotEmpty()) analyzed.map { it.motionScore }.average().toFloat() else 0f

                                    statusText = "2/4 Smart Audio Director ($selectedSoundtrack)..."
                                    val audioSel = DesktopAudioManager.selectAndPrepareTrack(
                                        genre = selectedSoundtrack,
                                        durationSeconds = selectedDuration,
                                        colorPreset = selectedColorPreset,
                                        vfxPreset = selectedVfxPreset,
                                        orientation = selectedOrientation,
                                        avgMotionScore = avgMotion
                                    )

                                    statusText = "3/4 Rilevamento beat (160 Hz LPF + RMS) su ${audioSel.trackId}..."
                                    val targetMs = selectedDuration * 1000L
                                    val beats = DesktopBeatDetector.findBeats(
                                        audioFile = audioSel.file,
                                        startOffsetMs = audioSel.startOffsetMs,
                                        targetDurationMs = targetMs
                                    )

                                    val plan = withContext(Dispatchers.Default) {
                                        AiDirectorCore().generateEditPlan(
                                            videos = analyzed,
                                            beatTimestamps = beats,
                                            targetDurationSec = selectedDuration
                                        )
                                    }

                                    statusText = "4/4 Rendering FFmpeg (${plan.size} tagli)..."
                                    val outFile = DesktopVideoEditor.executeEditPlan(
                                        videoFiles = selectedVideos,
                                        plan = plan,
                                        audioFile = audioSel.file,
                                        audioStartOffsetMs = audioSel.startOffsetMs,
                                        targetDurationMs = targetMs,
                                        outputFps = detectedOutputFps,
                                        colorPreset = selectedColorPreset,
                                        vfxPreset = selectedVfxPreset,
                                        outputOrientation = selectedOrientation
                                    )

                                    savedOutputFile = outFile
                                    statusText = "Completato! Salvato in: ${outFile.absolutePath}"
                                } catch (e: Exception) {
                                    statusText = "Errore montaggio: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        enabled = selectedVideos.isNotEmpty() && ffmpegAvailable && !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = FlameOrange),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isProcessing) "Elaborazione..." else "CREA REEL", color = Color.White, fontWeight = FontWeight.Black)
                    }

                    if (savedOutputFile != null) {
                        OutlinedButton(
                            onClick = { openInSystem(savedOutputFile!!) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Apri Video", color = FlameOrange, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun pickVideoFiles(): List<File> = withContext(Dispatchers.IO) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Seleziona clip video (minimo 3s)"
        isMultiSelectionEnabled = true
        fileFilter = FileNameExtensionFilter("Video MP4 / MOV / MKV / AVI", "mp4", "mov", "mkv", "avi", "webm")
    }
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFiles?.toList().orEmpty()
    } else {
        emptyList()
    }
}

private fun openInSystem(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        } else if (!DesktopPaths.isWindows) {
            ProcessBuilder("xdg-open", file.absolutePath).start()
        }
    }
}
