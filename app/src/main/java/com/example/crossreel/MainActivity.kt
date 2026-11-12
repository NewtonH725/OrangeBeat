package com.example.crossreel

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.crossreel.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrossReelTheme {
                var showSplash by remember { mutableStateOf(true) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (showSplash) Color.Black else AppBackground
                ) {
                    LaunchedEffect(Unit) {
                        delay(2000L)
                        showSplash = false
                    }

                    if (showSplash) {
                        SplashScreen()
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = "OrangeBeat Logo",
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color.White)) {
                        append("ORANGE")
                    }
                    withStyle(style = SpanStyle(color = FlameOrange)) {
                        append("BEAT")
                    }
                },
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = 4.sp
            )
        }

        Text(
            text = BuildConfig.VERSION_NAME,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val soundtracks = listOf("Rock", "Metal", "Techno", "Dubstep")

    var selectedOrientation by remember { mutableStateOf(OutputOrientation.PORTRAIT) }
    var selectedVideos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var totalUsableSourceMs by remember { mutableLongStateOf(0L) }
    var detectedOutputFps by remember { mutableIntStateOf(VideoEditor.DEFAULT_OUTPUT_FPS) }
    var selectedDuration by remember { mutableIntStateOf(15) }
    var selectedSoundtrack by remember { mutableStateOf("Rock") }
    var selectedColorPreset by remember { mutableStateOf(ColorPreset.NONE) }
    var selectedVfxPreset by remember { mutableStateOf(VfxPreset.NONE) }

    val outputSpecs by remember(selectedOrientation, detectedOutputFps) {
        derivedStateOf {
            VideoEditor.buildOutputSpecs(
                orientation = selectedOrientation,
                detectedFps = detectedOutputFps
            )
        }
    }

    var savedVideoUri by remember { mutableStateOf<Uri?>(null) }

    val appContext = LocalContext.current
    var pendingUpdate by remember { mutableStateOf<AppUpdater.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val update = AppUpdater.checkAndDownloadUpdate(appContext)
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
                    Text(
                        text = "È stato scaricato automaticamente un aggiornamento dal repository ufficiale."
                    )
                    Text(
                        text = "Versione attuale: ${BuildConfig.VERSION_NAME}\nNuova versione: ${update.versionName}\nFile: ${update.apkFileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FlameOrange
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val launched = AppUpdater.installApk(appContext, update.apkFile)
                        if (launched) {
                            showUpdateDialog = false
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

    val currentContext = LocalContext.current
    val currentScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            selectedVideos = uris
            totalUsableSourceMs = 0L
            detectedOutputFps = VideoEditor.DEFAULT_OUTPUT_FPS
            return@rememberLauncherForActivityResult
        }

        // Filtriamo le clip troppo brevi in background per non bloccare l'interfaccia
        currentScope.launch(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            var extractedFps: Int? = null
            var accumulatedUsableMs = 0L
            val validUris = uris.filter { uri ->
                try {
                    retriever.setDataSource(currentContext, uri)
                    val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val duration = timeStr?.toLong() ?: 0L
                    if (duration >= 3000L) {
                        accumulatedUsableMs += AiDirector.computeUsableDurationMs(duration)
                    }
                    if (duration >= 3000L && extractedFps == null) {
                        val captureFps = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
                        )?.toFloatOrNull()?.roundToInt()
                        if (captureFps != null && captureFps in 24..120) {
                            extractedFps = captureFps
                        } else {
                            val frameCount = retriever.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
                            )?.toLongOrNull()
                            if (frameCount != null && duration > 0L) {
                                val calcFps = ((frameCount * 1000f) / duration.toFloat()).roundToInt()
                                if (calcFps in 24..120) {
                                    extractedFps = if (calcFps >= 50) 60 else 30
                                }
                            }
                        }
                    }
                    duration >= 3000L
                } catch (e: Exception) {
                    false
                }
            }
            try { retriever.release() } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                if (validUris.size < uris.size) {
                    Toast.makeText(
                        currentContext,
                        "Le clip inferiori a 3 secondi sono state scartate per garantire un montaggio di qualità.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                selectedVideos = validUris
                totalUsableSourceMs = accumulatedUsableMs
                detectedOutputFps = extractedFps ?: VideoEditor.DEFAULT_OUTPUT_FPS
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ====================================================================
        // APP HEADER M3 EXPRESSIVE
        // ====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = buildAnnotatedString {
                        withStyle(style = SpanStyle(color = Color.White)) {
                            append("ORANGE")
                        }
                        withStyle(style = SpanStyle(color = FlameOrange)) {
                            append("BEAT")
                        }
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "AI REEL STUDIO • ON-DEVICE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceContainerHigh,
                border = BorderStroke(1.dp, OutlineVariantColor)
            ) {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = TabularNumberStyle.copy(fontSize = 11.sp, color = FlameOrange),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ====================================================================
        // SEZIONE 0 (PRIMA OPZIONE ASSOLUTA): ORIENTAMENTO OUTPUT FINALE
        // ====================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ORIENTAMENTO OUTPUT",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = "${selectedOrientation.displayName.uppercase()} (${selectedOrientation.ratioLabel})",
                        style = TabularNumberStyle.copy(
                            fontSize = 11.sp,
                            color = FlameOrange,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutputOrientation.entries.forEach { orientation ->
                        ExpressiveFilterChip(
                            title = "${orientation.displayName} ${orientation.ratioLabel}",
                            isSelected = orientation == selectedOrientation,
                            onClick = { selectedOrientation = orientation },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ====================================================================
        // SEZIONE 1: SORGENTI CLIP VIDEO
        // ====================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CLIP VIDEO SORGENTE",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedVideos.isNotEmpty()) PrimaryContainerColor else SurfaceContainerHighest
                    ) {
                        val usableSecBadge = (totalUsableSourceMs / 1000L).coerceAtLeast(1L)
                        Text(
                            text = if (selectedVideos.isNotEmpty()) "${selectedVideos.size} CLIP • ~${usableSecBadge}s" else "NESSUNA",
                            style = TabularNumberStyle.copy(
                                fontSize = 11.sp,
                                color = if (selectedVideos.isNotEmpty()) FlameOrange else TextMuted
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { launcher.launch("video/*") },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedVideos.isEmpty()) FlameOrange else SurfaceContainerHigh,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = if (selectedVideos.isNotEmpty()) "MODIFICA SELEZIONE CLIP" else "SELEZIONA VIDEO DALLA GALLERIA",
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ====================================================================
        // SEZIONE 2: REGOLAZIONE DURATA & STRUMENTO ANALOGICO
        // ====================================================================
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Controllo analogico a lancetta (Canvas ottimizzato a zero allocazioni)
                MixerKnobWithVuMeter(
                    value = selectedDuration,
                    onValueChange = { selectedDuration = it },
                    range = 5..60
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Slider dedicato M3 Expressive per la regolazione rapida
                ExpressiveDurationSlider(
                    value = selectedDuration,
                    onValueChange = { selectedDuration = it },
                    range = 5..60
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "5 s (Min)",
                        style = TabularNumberStyle.copy(fontSize = 11.sp, color = TextMuted)
                    )
                    Text(
                        text = "60 s (Max)",
                        style = TabularNumberStyle.copy(fontSize = 11.sp, color = TextMuted)
                    )
                }

                // Avviso non bloccante M3 Expressive quando la durata richiesta supera il girato utile caricato
                val usableSourceSec = (totalUsableSourceMs / 1000L).toInt()
                if (selectedVideos.size >= 2 && usableSourceSec > 0 && selectedDuration > usableSourceSec) {
                    val isVeryShort = selectedDuration > (usableSourceSec * 1.65f).roundToInt()
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = PrimaryContainerColor.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, FlameOrange.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(FlameOrange)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isVeryShort) {
                                    "Girato ridotto (~${usableSourceSec}s per ${selectedDuration}s): attivati Slow-Motion adattivo e rotazione completa. Aggiungi più clip per zero riusi."
                                } else {
                                    "Girato utile ~${usableSourceSec}s per ${selectedDuration}s: Slow-Motion adattivo attivo per coprire il Reel con scene inedite."
                                },
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp),
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ====================================================================
        // SEZIONE 3: COLONNA SONORA
        // ====================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "COLONNA SONORA",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = selectedSoundtrack.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = FlameOrange,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(soundtracks) { track ->
                        val isSelected = track == selectedSoundtrack
                        ExpressiveFilterChip(
                            title = track,
                            isSelected = isSelected,
                            onClick = { selectedSoundtrack = track }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ====================================================================
        // SEZIONE 4: COLOR GRADING (CON OPZIONE NESSUNO / STOCK IN PRIMA POSIZIONE)
        // ====================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "COLOR GRADING",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = selectedColorPreset.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlameOrange,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ColorPreset.entries) { preset ->
                        ExpressiveFilterChip(
                            title = preset.title,
                            isSelected = preset == selectedColorPreset,
                            onClick = { selectedColorPreset = preset }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ====================================================================
        // SEZIONE 5: EFFETTI VISIVI (VFX CON FLASH OPZIONALE)
        // ====================================================================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariantColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EFFETTI VISIVI (VFX)",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Text(
                        text = selectedVfxPreset.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = FlameOrange,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(VfxPreset.entries) { preset ->
                        ExpressiveFilterChip(
                            title = preset.title,
                            isSelected = preset == selectedVfxPreset,
                            onClick = { selectedVfxPreset = preset }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // ====================================================================
        // SEZIONE 6: CTA PRINCIPALE & STATO AVANZAMENTO REEL
        // ====================================================================
        val scope = rememberCoroutineScope()
        var isGenerating by remember { mutableStateOf(false) }
        var generationStatus by remember { mutableStateOf("") }
        val context = LocalContext.current
        val view = LocalView.current

        DisposableEffect(isGenerating) {
            view.keepScreenOn = isGenerating
            onDispose {
                view.keepScreenOn = false
            }
        }

        fun generateReel() {
            isGenerating = true
            savedVideoUri = null
            generationStatus = "ESTRAZIONE METADATI CLIP..."
            scope.launch {
                var lastPlanJson: String? = null
                try {
                    val videoMetadataList = withContext(Dispatchers.IO) {
                        val retriever = android.media.MediaMetadataRetriever()
                        selectedVideos.map { uri ->
                            try {
                                retriever.setDataSource(context, uri)
                                val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                VideoMetadata(uri, timeStr?.toLong() ?: 5000L)
                            } catch (_: Exception) {
                                VideoMetadata(uri, 5000L)
                            }
                        }.also { retriever.release() }
                    }

                    // --- STEP 1: Analisi Video (AI Motion & Pose) ---
                    generationStatus = "ANALISI AI DEI VIDEO (MOTION & POSE)..."
                    val motionAnalyzer = MotionAnalyzer()

                    val enrichedMetadataList = withContext(Dispatchers.Default) {
                        videoMetadataList.map { video ->
                            async {
                                val motionScore = motionAnalyzer.analyzeMotion(context, video.uri, video.durationMs)
                                val actionPeaks = try {
                                    findActionPeaks(context, video.uri, video.durationMs)
                                } catch (_: Exception) {
                                    emptyList<Long>()
                                }
                                video.copy(motionScore = motionScore, actionPeaks = actionPeaks)
                            }
                        }.awaitAll()
                    }

                    // --- STEP 2: Smart Audio Selection & Beat Detection ---
                    generationStatus = "SELEZIONE AUDIO INTELLIGENTE & BEAT..."
                    val avgMotion = if (enrichedMetadataList.isNotEmpty()) {
                        enrichedMetadataList.map { it.motionScore }.average().toFloat()
                    } else 0.5f
                    val totalDurationSec = (enrichedMetadataList.sumOf { it.durationMs } / 1000f).coerceAtLeast(1f)
                    val peakDensity = enrichedMetadataList.sumOf { it.actionPeaks.size } / totalDurationSec

                    val audioContext = SmartAudioDirector.AudioContext(
                        genre = selectedSoundtrack,
                        durationSeconds = selectedDuration,
                        colorPreset = selectedColorPreset,
                        vfxPreset = selectedVfxPreset,
                        orientation = selectedOrientation,
                        avgMotionScore = avgMotion,
                        actionPeakDensity = peakDensity
                    )
                    val audioSource = withContext(Dispatchers.IO) {
                        SmartAudioDirector.selectOptimalAudio(context, audioContext)
                    }

                    val realBeatDetector = RealBeatDetector()
                    val beats = realBeatDetector.findBeats(context, audioSource, selectedDuration * 1000L)

                    // --- STEP 3: Regia Intelligente ---
                    generationStatus = "CALCOLO TAGLI INTELLIGENTI..."
                    val director = AiDirector()
                    val jsonPlan = director.generateEditPlanLocal(enrichedMetadataList, beats, selectedDuration)
                    lastPlanJson = jsonPlan

                    // --- STEP 4: Montaggio & Rendering Media3 ---
                    generationStatus = "MONTAGGIO MEDIA3 IN CORSO..."
                    val editor = VideoEditor(context)
                    val finalVideoUri = editor.executeEditPlan(
                        videos = enrichedMetadataList,
                        jsonPlan = jsonPlan,
                        audioSource = audioSource,
                        targetDurationMs = selectedDuration * 1000L,
                        colorPreset = selectedColorPreset,
                        vfxPreset = selectedVfxPreset,
                        outputOrientation = selectedOrientation
                    )

                    savedVideoUri = finalVideoUri
                    generationStatus = "REEL COMPLETATO CON SUCCESSO!"
                } catch (e: Exception) {
                    ErrorLogger.log(
                        context = context,
                        error = e,
                        settings = "orientamento=${selectedOrientation.name}, clip=${selectedVideos.size}, durata=${selectedDuration}s, musica=$selectedSoundtrack, colore=${selectedColorPreset.name}, vfx=${selectedVfxPreset.name}",
                        editPlanJson = lastPlanJson
                    )
                    generationStatus = "ERRORE: ${e.message?.uppercase()}"
                } finally {
                    isGenerating = false
                }
            }
        }

        // Riquadro riepilogativo Specifiche Output Finale (M3 Expressive) posizionato subito sopra "CREA REEL"
        OutputSpecsSummaryCard(
            specs = outputSpecs,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isGenerating) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = SurfaceContainerHigh,
                border = BorderStroke(1.dp, FlameOrange.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = FlameOrange,
                        trackColor = SurfaceContainerHighest
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = FlameOrange,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = generationStatus,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = { generateReel() },
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FlameOrange,
                    contentColor = Color.White,
                    disabledContainerColor = SurfaceContainerHighest,
                    disabledContentColor = TextMuted
                ),
                enabled = selectedVideos.size >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = "CREA REEL",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    )
                )
            }
        }

        if (selectedVideos.size < 2 && !isGenerating) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Seleziona almeno 2 video per abilitare la generazione automatica",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        if (savedVideoUri != null && !isGenerating) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openVideo(context, savedVideoUri!!) },
                shape = RoundedCornerShape(24.dp),
                color = PrimaryContainerColor,
                border = BorderStroke(1.5.dp, FlameOrange)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play_arrow),
                        contentDescription = "Apri video",
                        tint = FlameOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "REEL PRONTO! TOCCA QUI PER GUARDARLO",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = Color.White
                    )
                }
            }
        } else if (generationStatus.isNotEmpty() && !isGenerating && generationStatus.startsWith("ERRORE")) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = generationStatus,
                color = Color(0xFFFF5252),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Riquadro riepilogativo in stile Google Material 3 Expressive posizionato subito sopra il pulsante
 * CTA "CREA REEL", con aggiornamento reattivo di Risoluzione, Frame Rate e Profondità di Colore.
 */
@Composable
fun OutputSpecsSummaryCard(
    specs: OutputVideoSpecs,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.animateContentSize(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
        ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(FlameOrange)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SPECIFICHE OUTPUT FINALE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PrimaryContainerColor
                ) {
                    AnimatedContent(
                        targetState = specs.formatBadge,
                        label = "formatBadgeAnim"
                    ) { badge ->
                        Text(
                            text = badge,
                            style = TabularNumberStyle.copy(
                                fontSize = 10.sp,
                                color = FlameOrange,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Colonna 1: Risoluzione
                OutputSpecPill(
                    label = "Risoluzione",
                    value = specs.resolutionLabel,
                    subValue = "px",
                    modifier = Modifier.weight(1f)
                )

                // Colonna 2: Frame Rate
                OutputSpecPill(
                    label = "Frame Rate",
                    value = specs.frameRateLabel,
                    subValue = "Costante",
                    modifier = Modifier.weight(1f)
                )

                // Colonna 3: Profondità Colore
                OutputSpecPill(
                    label = "Profondità Colore",
                    value = specs.colorDepthLabel,
                    subValue = specs.colorSpaceBadge,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun OutputSpecPill(
    label: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, OutlineVariantColor.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(modifier = Modifier.height(4.dp))
            AnimatedContent(
                targetState = value,
                label = "specValueAnim"
            ) { currentVal ->
                Text(
                    text = currentVal,
                    style = TabularNumberStyle.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                style = TabularNumberStyle.copy(
                    fontSize = 9.sp,
                    color = FlameOrange.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Fader orizzontale in stile banco mixer analogico vintage (scanalatura incassata,
 * serigrafia tacche di calibrazione e tasto fader 3D concavo con zigrinature e tacca arancione).
 */
@Composable
fun ExpressiveDurationSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange = 5..60,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableIntStateOf(value) }
    val density = LocalDensity.current

    // Pre-allocazione Brush e dimensioni per evitare allocazioni nel layout/draw
    val faderCapBrush = remember {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            0.0f to Color(0xFF3E3C3A),
            0.18f to Color(0xFF282624),
            0.42f to Color(0xFF181716),
            0.50f to Color(0xFF141312),
            0.58f to Color(0xFF181716),
            0.82f to Color(0xFF282624),
            1.0f to Color(0xFF3A3836)
        )
    }
    val slotShadowBrush = remember {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFF040404), Color(0xFF141312), Color(0xFF060606))
        )
    }
    val tickMajorPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val tickMinorPx = remember(density) { with(density) { 1.dp.toPx() } }
    val tickColorMajor = remember { Color(0xFF8A827A) }
    val tickColorMinor = remember { Color(0xFF4A4642) }
    val tickColorActive = remember { FlameOrange }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val thumbWidthDp = 38.dp
        val thumbWidthPx = with(density) { thumbWidthDp.toPx() }
        val usableTrackWidth = (widthPx - thumbWidthPx).coerceAtLeast(1f)

        val frac = ((value - range.first).toFloat() / (range.last - range.first).toFloat()).coerceIn(0f, 1f)
        val thumbOffsetPx = frac * usableTrackWidth

        // 1. Serigrafia tacche da mixer analogico (sopra e sotto la fessura del fader)
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val centerY = size.height / 2f
            val totalTicks = 22
            for (i in 0..totalTicks) {
                val tFrac = i.toFloat() / totalTicks.toFloat()
                val x = (thumbWidthPx / 2f) + tFrac * usableTrackWidth
                val isMajor = i % 2 == 0
                val isPassed = tFrac <= frac + 0.005f
                val color = when {
                    isPassed && isMajor -> tickColorActive
                    isMajor -> tickColorMajor
                    else -> tickColorMinor
                }
                val strokeW = if (isMajor) tickMajorPx else tickMinorPx
                val tickLen = if (isMajor) 8.dp.toPx() else 5.dp.toPx()
                val gapFromCenter = 9.dp.toPx()

                // Tacca superiore
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(x, centerY - gapFromCenter - tickLen),
                    end = androidx.compose.ui.geometry.Offset(x, centerY - gapFromCenter),
                    strokeWidth = strokeW
                )
                // Tacca inferiore
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(x, centerY + gapFromCenter),
                    end = androidx.compose.ui.geometry.Offset(x, centerY + gapFromCenter + tickLen),
                    strokeWidth = strokeW
                )
            }
        }

        // 2. Scanalatura metallica incassata (Fader Slot)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(3.dp))
                .background(slotShadowBrush)
                .border(1.dp, Color(0xFF2C2926), RoundedCornerShape(3.dp))
        ) {
            // Binario interno nero profondo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(Color.Black)
            )
            // Bagliore ambra/arancione sottile lungo la corsa attiva del binario
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(with(density) { (thumbOffsetPx + thumbWidthPx / 2f).toDp() })
                    .align(Alignment.CenterStart)
                    .background(FlameOrange.copy(alpha = 0.85f))
            )
        }

        // 3. Tasto fisico del Fader (Fader Cap concavo da mixer analogico)
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                .align(Alignment.CenterStart)
                .width(thumbWidthDp)
                .height(52.dp)
                .shadow(8.dp, RoundedCornerShape(4.dp))
                .background(faderCapBrush, RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF0A0A0A), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp, horizontal = 3.dp)
        ) {
            // Zigrinature metalliche e linea di fede centrale arancione
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Grip sinistro
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.72f).background(Color(0xFF111111)))
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.82f).background(Color(0xFF111111)))

                // Tacca centrale di riferimento (inlaid Flame Orange)
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight(0.94f)
                        .background(FlameOrange, RoundedCornerShape(1.dp))
                )

                // Grip destro
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.82f).background(Color(0xFF111111)))
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.72f).background(Color(0xFF111111)))
            }
        }

        // 4. Touch capture su tutta l'area per trascinamento fluido
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(range) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val clampedX = (offset.x - thumbWidthPx / 2f).coerceIn(0f, usableTrackWidth)
                            val newFrac = clampedX / usableTrackWidth
                            val newVal = (range.first + (newFrac * (range.last - range.first)).roundToInt()).coerceIn(range.first, range.last)
                            if (newVal != lastHapticValue) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastHapticValue = newVal
                                onValueChange(newVal)
                            }
                        }
                    ) { change, _ ->
                        change.consume()
                        val clampedX = (change.position.x - thumbWidthPx / 2f).coerceIn(0f, usableTrackWidth)
                        val newFrac = clampedX / usableTrackWidth
                        val newVal = (range.first + (newFrac * (range.last - range.first)).roundToInt()).coerceIn(range.first, range.last)
                        if (newVal != lastHapticValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastHapticValue = newVal
                            onValueChange(newVal)
                        }
                    }
                }
        )
    }
}

/**
 * Chip selettore M3 Expressive a forma dinamica (transizione elastica verso pillola piena quando attivo).
 */
@Composable
fun ExpressiveFilterChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 22.dp else 14.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "chipCorner"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) FlameOrange else SurfaceContainerHigh,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "chipBg"
    )

    val contentColor = if (isSelected) Color.White else TextPrimary
    val borderColor = if (isSelected) FlameOrange else OutlineVariantColor

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.uppercase(),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun openVideo(context: android.content.Context, videoUri: Uri) {
    try {
        val contentUri = if (videoUri.scheme == "file" || videoUri.scheme == null) {
            val filePath = videoUri.path ?: videoUri.schemeSpecificPart
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File video non trovato", Toast.LENGTH_SHORT).show()
                return
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            videoUri
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(null, contentUri)
        }

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri(null, contentUri)
            }
            val chooser = Intent.createChooser(fallbackIntent, "Apri video con").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Errore durante l'apertura del video", e)
        Toast.makeText(context, "Impossibile aprire il video: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
