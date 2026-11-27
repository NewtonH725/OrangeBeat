package com.example.crossreel.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Studio Analog VU Meter & Precision Fader per Desktop (Ubuntu & Windows).
 * - Quadrante vintage retroilluminato ambra/arancione ad alto contrasto con scala 0..60s.
 * - Interazione diretta su quadrante (click + drag angolare atan2 + rotellina mouse).
 * - Fader analogico 3D fluido (click istantaneo su tutta la corsa + drag continuo + scroll).
 */
@Composable
fun DesktopMixerKnobWithVuMeter(
    selectedDuration: Int,
    onDurationChange: (Int) -> Unit,
    range: IntRange = 5..60,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val quickPresets = remember { listOf(15, 30, 45, 60) }
    val clampedDuration = selectedDuration.coerceIn(range.first, range.last)

    val scaleMax = 60f
    val targetNeedleAngle = -50f + ((clampedDuration / scaleMax) * 100f)
    val animatedNeedleAngle by animateFloatAsState(
        targetValue = targetNeedleAngle,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 110f),
        label = "DesktopNeedlePhysics"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header con badge digitale tabulare e preset rapidi
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DURATA REEL",
                    color = FlameOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Clicca o trascina la lancetta, usa lo slider o un preset",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quickPresets.forEach { preset ->
                    val isSelected = clampedDuration == preset
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) PrimaryContainerColor else SurfaceContainer,
                        label = "PresetChipBg"
                    )
                    Surface(
                        color = chipBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) FlameOrange else OutlineVariantColor
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                            .clickable(enabled = enabled) { onDurationChange(preset) }
                    ) {
                        Text(
                            text = "${preset}s",
                            style = TabularNumberStyle.copy(
                                fontSize = 11.sp,
                                color = if (isSelected) WarmAmber else TextSecondary
                            ),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceContainerHighest,
                    border = BorderStroke(1.dp, FlameOrange.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "$clampedDuration s",
                        style = TabularNumberStyle.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = FlameOrange
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        StudioAnalogVuDisplay(
            selectedDuration = clampedDuration,
            animatedNeedleAngle = animatedNeedleAngle,
            range = range,
            enabled = enabled,
            onDurationChange = onDurationChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        DesktopAnalogFaderSlider(
            value = clampedDuration,
            range = range,
            enabled = enabled,
            onValueChange = onDurationChange
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${range.first} s (Min)",
                style = TabularNumberStyle.copy(fontSize = 11.sp, color = TextMuted)
            )
            Text(
                text = "${range.last} s (Max)",
                style = TabularNumberStyle.copy(fontSize = 11.sp, color = TextMuted)
            )
        }
    }
}

@Composable
private fun StudioAnalogVuDisplay(
    selectedDuration: Int,
    animatedNeedleAngle: Float,
    range: IntRange,
    enabled: Boolean,
    onDurationChange: (Int) -> Unit
) {
    val currentVal by rememberUpdatedState(selectedDuration)
    val currentOnChange by rememberUpdatedState(onDurationChange)
    val currentEnabled by rememberUpdatedState(enabled)

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val labelStyleBlack = remember {
        TextStyle(
            color = Color(0xFF1A0F05),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif
        )
    }
    val labelStyleRed = remember {
        TextStyle(
            color = Color(0xFFB71C1C),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif
        )
    }

    val majorTicks = remember { (0..60 step 10).toList() }
    val measuredTickLabels = remember(majorTicks, textMeasurer) {
        majorTicks.map { tick ->
            val style = if (tick >= 50) labelStyleRed else labelStyleBlack
            textMeasurer.measure(tick.toString(), style)
        }
    }

    val blackArcStroke = remember(density) { Stroke(width = with(density) { 2.dp.toPx() }) }
    val redArcStroke = remember(density) { Stroke(width = with(density) { 3.5.dp.toPx() }, cap = StrokeCap.Round) }
    val tickMajorPx = remember(density) { with(density) { 2.5.dp.toPx() } }
    val tickMinorPx = remember(density) { with(density) { 1.3.dp.toPx() } }
    val needleWidthPx = remember(density) { with(density) { 2.8.dp.toPx() } }

    val glassSheenBrush = remember {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.14f),
            0.32f to Color.White.copy(alpha = 0.03f),
            0.35f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = 0.18f)
        )
    }

    val bezelBrush = remember {
        Brush.verticalGradient(
            listOf(Color(0xFF262320), Color(0xFF141312), Color(0xFF0D0C0B))
        )
    }

    // Chassis esterno centrato con proporzione studio (max 560dp per evitare allungamenti vuoti)
    Box(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(186.dp)
            .shadow(10.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(bezelBrush)
            .border(1.5.dp, Color(0xFF3E3A36), RoundedCornerShape(16.dp))
            .padding(10.dp)
    ) {
        // Viti anodizzate agli angoli del pannello hardware
        val screwMod = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF3D3935)).border(0.5.dp, Color(0xFF0B0A09), CircleShape)
        Box(screwMod.align(Alignment.TopStart))
        Box(screwMod.align(Alignment.TopEnd))
        Box(screwMod.align(Alignment.BottomStart))
        Box(screwMod.align(Alignment.BottomEnd))

        // Finestra vetro retroilluminata ambra/arancione
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.5.dp, Color(0xFF0A0602), RoundedCornerShape(10.dp))
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                .pointerInput(range) {
                    fun updateFromPoint(x: Float, y: Float) {
                        if (!currentEnabled) return
                        val pivotX = size.width / 2f
                        val pivotY = size.height * 1.15f
                        val angleRad = atan2((x - pivotX).toDouble(), -(y - pivotY).toDouble())
                        val clampedDeg = Math.toDegrees(angleRad).toFloat().coerceIn(-50f, 50f)
                        val frac = (clampedDeg + 50f) / 100f
                        val next = (frac * 60f).roundToInt().coerceIn(range.first, range.last)
                        if (next != currentVal) currentOnChange(next)
                    }

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val pos = event.changes.firstOrNull()?.position
                                    if (pos != null && currentEnabled) {
                                        updateFromPoint(pos.x, pos.y)
                                        event.changes.forEach { it.consume() }
                                        do {
                                            val drag = awaitPointerEvent()
                                            val dPos = drag.changes.firstOrNull()?.position
                                            if (dPos != null && drag.changes.any { it.pressed }) {
                                                updateFromPoint(dPos.x, dPos.y)
                                                drag.changes.forEach { it.consume() }
                                            }
                                        } while (drag.changes.any { it.pressed })
                                    }
                                }
                                PointerEventType.Scroll -> {
                                    if (currentEnabled) {
                                        val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        if (delta != 0f) {
                                            val step = if (delta < 0f) 1 else -1
                                            val next = (currentVal + step).coerceIn(range.first, range.last)
                                            if (next != currentVal) currentOnChange(next)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Sfondo caldo calibrato sulle dimensioni reali del canvas (mai ellissi schiacciate)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFF69E2B),
                            Color(0xFFD67412),
                            Color(0xFF8A3E05),
                            Color(0xFF2B1201)
                        ),
                        center = Offset(w / 2f, h * 0.62f),
                        radius = maxOf(w, h) * 0.68f
                    )
                )

                // Riflesso vitreo superiore + vignettatura perimetrale
                drawRect(brush = glassSheenBrush)
                drawRoundRect(
                    color = Color(0x55000000),
                    cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                    style = Stroke(width = 6.dp.toPx())
                )

                val pivot = Offset(w / 2f, h * 1.16f)
                val radius = minOf(w * 0.44f, h * 0.98f)

                val angleRange = 100f
                val startAngleCanvas = 270f - (angleRange / 2f)
                val blackSweep = (50f / 60f) * angleRange
                val redSweep = (10f / 60f) * angleRange

                // Arco scala principale (0 - 50s)
                drawArc(
                    color = Color(0xFF1A0F05),
                    startAngle = startAngleCanvas,
                    sweepAngle = blackSweep,
                    useCenter = false,
                    style = blackArcStroke,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2)
                )

                // Arco zona rossa (50 - 60s)
                drawArc(
                    color = Color(0xFFC62828),
                    startAngle = startAngleCanvas + blackSweep,
                    sweepAngle = redSweep,
                    useCenter = false,
                    style = redArcStroke,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2)
                )

                // Tacche graduate 0..60 (ogni 2.5s) e numeri ogni 10s
                for (step2 in 0..24) {
                    val tickVal = step2 * 2.5f
                    val isMajor = step2 % 4 == 0
                    val isMid = step2 % 2 == 0
                    val isRed = tickVal >= 50f

                    val tickAngleOff = -50f + ((tickVal / 60f) * 100f)
                    val angleRad = Math.toRadians((270f + tickAngleOff).toDouble())

                    val innerRatio = when {
                        isMajor -> 0.89f
                        isMid -> 0.93f
                        else -> 0.96f
                    }
                    val tickInner = radius * innerRatio
                    val tickOuter = radius

                    drawLine(
                        color = if (isRed) Color(0xFFB71C1C) else Color(0xFF1A0F05),
                        start = Offset(
                            pivot.x + cos(angleRad).toFloat() * tickInner,
                            pivot.y + sin(angleRad).toFloat() * tickInner
                        ),
                        end = Offset(
                            pivot.x + cos(angleRad).toFloat() * tickOuter,
                            pivot.y + sin(angleRad).toFloat() * tickOuter
                        ),
                        strokeWidth = if (isMajor) tickMajorPx else tickMinorPx,
                        cap = StrokeCap.Round
                    )

                    if (isMajor) {
                        val majorIdx = step2 / 4
                        val layoutResult = measuredTickLabels[majorIdx]
                        val textR = radius * 0.76f
                        val tx = pivot.x + cos(angleRad).toFloat() * textR
                        val ty = pivot.y + sin(angleRad).toFloat() * textR
                        drawText(
                            textLayoutResult = layoutResult,
                            topLeft = Offset(
                                tx - layoutResult.size.width / 2f,
                                ty - layoutResult.size.height / 2f
                            )
                        )
                    }
                }

                // Ombra morbida della lancetta sul quadrante
                withTransform({
                    rotate(animatedNeedleAngle, pivot)
                }) {
                    val needleTop = pivot.y - radius * 1.04f
                    drawLine(
                        color = Color(0x45000000),
                        start = Offset(pivot.x + 2.5.dp.toPx(), pivot.y + 1.dp.toPx()),
                        end = Offset(pivot.x + 2.5.dp.toPx(), needleTop + 2.dp.toPx()),
                        strokeWidth = needleWidthPx + 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFF111111),
                        start = pivot,
                        end = Offset(pivot.x, needleTop),
                        strokeWidth = needleWidthPx,
                        cap = StrokeCap.Round
                    )
                }

                // Perno metallico centrale a 3 strati
                drawCircle(color = Color(0xFF201D1B), radius = 16.dp.toPx(), center = pivot)
                drawCircle(color = Color(0xFF4B4642), radius = 10.dp.toPx(), center = pivot)
                drawCircle(color = Color(0xFF111111), radius = 4.dp.toPx(), center = pivot)
            }

            // Etichetta serigrafata "SEC" in basso al centro
            Text(
                text = "SEC",
                color = Color(0xBF1A0F05),
                fontSize = 15.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )

            // Indicatore "VU / REC" a sinistra
            Text(
                text = "REEL TIME",
                color = Color(0x991A0F05),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 12.dp)
            )

            // Spia LED rossa di picco (>= 50s) con alone luminoso
            val peakActive = selectedDuration >= 50
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp, top = 12.dp)
                    .size(12.dp)
                    .background(
                        color = if (peakActive) Color(0xFFFF1744) else Color(0xFF4A1010),
                        shape = CircleShape
                    )
                    .border(1.dp, Color(0xCC000000), CircleShape)
            )
        }
    }
}

@Composable
private fun DesktopAnalogFaderSlider(
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val currentVal by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val density = LocalDensity.current

    val faderCapBrush = remember {
        Brush.horizontalGradient(
            0.0f to Color(0xFF423F3C),
            0.20f to Color(0xFF2B2825),
            0.44f to Color(0xFF1A1816),
            0.50f to Color(0xFF131211),
            0.56f to Color(0xFF1A1816),
            0.80f to Color(0xFF2B2825),
            1.0f to Color(0xFF3E3B38)
        )
    }
    val slotShadowBrush = remember {
        Brush.verticalGradient(
            listOf(Color(0xFF040404), Color(0xFF141312), Color(0xFF060606))
        )
    }
    val tickMajorPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val tickMinorPx = remember(density) { with(density) { 1.dp.toPx() } }
    val tickColorMajor = remember { Color(0xFF8A827A) }
    val tickColorMinor = remember { Color(0xFF4A4642) }
    val tickColorActive = remember { FlameOrange }

    val thumbWidthDp = 38.dp
    val thumbWidthPx = remember(density) { with(density) { thumbWidthDp.toPx() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            .pointerInput(range) {
                fun updateFromX(x: Float) {
                    if (!currentEnabled) return
                    val usableWidth = (size.width.toFloat() - thumbWidthPx).coerceAtLeast(1f)
                    val clampedX = (x - thumbWidthPx / 2f).coerceIn(0f, usableWidth)
                    val frac = clampedX / usableWidth
                    val next = (range.first + (frac * (range.last - range.first)).roundToInt())
                        .coerceIn(range.first, range.last)
                    if (next != currentVal) currentOnChange(next)
                }

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> {
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null && currentEnabled) {
                                    updateFromX(pos.x)
                                    event.changes.forEach { it.consume() }
                                    do {
                                        val drag = awaitPointerEvent()
                                        val dPos = drag.changes.firstOrNull()?.position
                                        if (dPos != null && drag.changes.any { it.pressed }) {
                                            updateFromX(dPos.x)
                                            drag.changes.forEach { it.consume() }
                                        }
                                    } while (drag.changes.any { it.pressed })
                                }
                            }
                            PointerEventType.Scroll -> {
                                if (currentEnabled) {
                                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (delta != 0f) {
                                        val step = if (delta < 0f) 1 else -1
                                        val next = (currentVal + step).coerceIn(range.first, range.last)
                                        if (next != currentVal) currentOnChange(next)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val usableTrackWidth = (widthPx - thumbWidthPx).coerceAtLeast(1f)
        val frac = ((value - range.first).toFloat() / (range.last - range.first).toFloat()).coerceIn(0f, 1f)
        val thumbOffsetPx = frac * usableTrackWidth

        // 1. Tacche graduate sopra e sotto il binario
        Canvas(modifier = Modifier.fillMaxSize()) {
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

                drawLine(
                    color = color,
                    start = Offset(x, centerY - gapFromCenter - tickLen),
                    end = Offset(x, centerY - gapFromCenter),
                    strokeWidth = strokeW
                )
                drawLine(
                    color = color,
                    start = Offset(x, centerY + gapFromCenter),
                    end = Offset(x, centerY + gapFromCenter + tickLen),
                    strokeWidth = strokeW
                )
            }
        }

        // 2. Scanalatura incassata con scia arancione
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(3.dp))
                .background(slotShadowBrush)
                .border(1.dp, Color(0xFF2C2926), RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(with(density) { (thumbOffsetPx + thumbWidthPx / 2f).toDp() })
                    .align(Alignment.CenterStart)
                    .background(FlameOrange.copy(alpha = 0.9f))
            )
        }

        // 3. Cursore fader 3D
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                .align(Alignment.CenterStart)
                .width(thumbWidthDp)
                .height(48.dp)
                .shadow(6.dp, RoundedCornerShape(5.dp))
                .background(faderCapBrush, RoundedCornerShape(5.dp))
                .border(1.dp, Color(0xFF0A0A0A), RoundedCornerShape(5.dp))
                .padding(vertical = 4.dp, horizontal = 3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.72f).background(Color(0xFF111111)))
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.82f).background(Color(0xFF111111)))
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .fillMaxHeight(0.94f)
                        .background(FlameOrange, RoundedCornerShape(1.dp))
                )
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.82f).background(Color(0xFF111111)))
                Box(modifier = Modifier.width(1.5.dp).fillMaxHeight(0.72f).background(Color(0xFF111111)))
            }
        }
    }
}
