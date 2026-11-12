package com.example.crossreel

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crossreel.ui.theme.FlameOrange
import com.example.crossreel.ui.theme.OutlineVariantColor
import com.example.crossreel.ui.theme.SurfaceContainerHigh
import com.example.crossreel.ui.theme.SurfaceContainerHighest
import com.example.crossreel.ui.theme.TabularNumberStyle
import com.example.crossreel.ui.theme.TextPrimary
import com.example.crossreel.ui.theme.TextSecondary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Misuratore di livello analogico vintage con retroilluminazione arancione/ambra.
 * - Sfondo vintage con gradiente radiale ambra/arancione caldo e ombra perimetrale.
 * - Scala graduata vintage con zona rossa d'avvertimento (50s-60s) e spia LED picco.
 * - Lancetta analogica fisica reattiva con fisica a molla smorzata.
 * - Zero allocazioni in DrawScope (Paint, Stroke, Brush pre-allocati all'esterno).
 * - Interazione touch/drag con rilevamento angolare atan2 e feedback aptico.
 */
@Composable
fun MixerKnobWithVuMeter(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange = 5..60,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableIntStateOf(value) }

    // Angolo lancetta: mappatura da -50° a +50° per una scala 0..60s
    val scaleMax = 60f
    val targetNeedleAngle = -50f + ((value / scaleMax) * 100f)

    val animatedNeedleAngle by animateFloatAsState(
        targetValue = targetNeedleAngle,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 90f),
        label = "vintageNeedleAngle"
    )

    // Pre-allocazione stili grafici e Paint (Zero Allocations in DrawScope)
    val density = LocalDensity.current
    val textPaintBlack = remember(density) {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = with(density) { 13.sp.toPx() }
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val textPaintRed = remember(textPaintBlack) {
        Paint(textPaintBlack).apply {
            color = android.graphics.Color.parseColor("#D32F2F")
        }
    }

    val blackArcStroke = remember(density) {
        Stroke(width = with(density) { 2.dp.toPx() })
    }
    val redArcStroke = remember(density) {
        Stroke(width = with(density) { 3.dp.toPx() })
    }

    val tickMajorWidthPx = remember(density) { with(density) { 2.5.dp.toPx() } }
    val tickMinorWidthPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val needleStrokeWidthPx = remember(density) { with(density) { 2.5.dp.toPx() } }

    val redColor = remember { Color(0xFFD32F2F) }
    val needleColor = remember { Color(0xFF111111) }

    // Gradiente radiale retroilluminazione arancione caldo vintage
    val vintageBacklightBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFFF39624), // Luce ambra calda diffusa al centro
                Color(0xFFD37311), // Transizione morbida arancione vintage
                Color(0xFF853D04), // Bordo caldo brunito
                Color(0xFF261000)  // Ombra scura perimetrale del case
            ),
            radius = 1200f
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)),
        color = SurfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Badge contatore durata tabulare interno al riquadro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceContainerHighest,
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantColor)
                ) {
                    Text(
                        text = "$value s",
                        style = TabularNumberStyle.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = FlameOrange
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Struttura box vintage: chassis nero esterno + display retroilluminato arancione
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(165.dp)
                    .background(Color(0xFF181818), RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(brush = vintageBacklightBrush)
                        .border(1.5.dp, Color(0x66000000), RoundedCornerShape(6.dp))
                        .pointerInput(range) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val pivotX = size.width / 2f
                                val pivotY = size.height * 1.15f
                                val touchX = change.position.x
                                val touchY = change.position.y

                                val deltaX = touchX - pivotX
                                val deltaY = touchY - pivotY
                                val angleRad = atan2(deltaX.toDouble(), -deltaY.toDouble())
                                val angleDeg = Math.toDegrees(angleRad).toFloat()

                                val clampedAngle = angleDeg.coerceIn(-50f, 50f)
                                val frac = (clampedAngle + 50f) / 100f
                                val computedVal = (frac * scaleMax).roundToInt().coerceIn(range.first, range.last)

                                if (computedVal != lastHapticValue) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    lastHapticValue = computedVal
                                    onValueChange(computedVal)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pivot = Offset(size.width / 2f, size.height * 1.15f)
                        val radius = size.height * 0.95f

                        val angleRange = 100f
                        val startAngleCanvas = 270f - (angleRange / 2f)

                        val blackSweep = (50f / 60f) * angleRange
                        val redSweep = (10f / 60f) * angleRange

                        // 1. Arco scala normale (0 - 50s)
                        drawArc(
                            color = Color.Black,
                            startAngle = startAngleCanvas,
                            sweepAngle = blackSweep,
                            useCenter = false,
                            style = blackArcStroke,
                            topLeft = Offset(pivot.x - radius, pivot.y - radius),
                            size = Size(radius * 2, radius * 2)
                        )

                        // 2. Arco zona rossa d'avvertimento (50s - 60s)
                        drawArc(
                            color = redColor,
                            startAngle = startAngleCanvas + blackSweep,
                            sweepAngle = redSweep,
                            useCenter = false,
                            style = redArcStroke,
                            topLeft = Offset(pivot.x - radius, pivot.y - radius),
                            size = Size(radius * 2, radius * 2)
                        )

                        // 3. Tacche e serigrafia numeri
                        for (tick in 0..60 step 5) {
                            val isMajor = tick % 10 == 0
                            val isRed = tick >= 50

                            val tickAngleOff = -50f + ((tick / 60f) * 100f)
                            val angleDeg = 270f + tickAngleOff
                            val angleRad = Math.toRadians(angleDeg.toDouble())

                            val tickColor = if (isRed) redColor else Color.Black

                            val tickInner = radius * if (isMajor) 0.92f else 0.96f
                            val tickOuter = radius * 1.0f

                            drawLine(
                                color = tickColor,
                                start = Offset(
                                    pivot.x + cos(angleRad).toFloat() * tickInner,
                                    pivot.y + sin(angleRad).toFloat() * tickInner
                                ),
                                end = Offset(
                                    pivot.x + cos(angleRad).toFloat() * tickOuter,
                                    pivot.y + sin(angleRad).toFloat() * tickOuter
                                ),
                                strokeWidth = if (isMajor) tickMajorWidthPx else tickMinorWidthPx,
                                cap = StrokeCap.Round
                            )

                            if (isMajor) {
                                val textRadius = radius * 0.81f
                                val textX = pivot.x + cos(angleRad).toFloat() * textRadius
                                val textY = pivot.y + sin(angleRad).toFloat() * textRadius

                                drawContext.canvas.nativeCanvas.drawText(
                                    tick.toString(),
                                    textX,
                                    textY + (textPaintBlack.textSize / 3),
                                    if (isRed) textPaintRed else textPaintBlack
                                )
                            }
                        }

                        // 4. Lancetta analogica fisica
                        withTransform({
                            rotate(animatedNeedleAngle, pivot)
                        }) {
                            val needleTop = pivot.y - radius * 1.08f
                            drawLine(
                                color = needleColor,
                                start = pivot,
                                end = Offset(pivot.x, needleTop),
                                strokeWidth = needleStrokeWidthPx,
                                cap = StrokeCap.Round
                            )
                        }

                        // 5. Perno centrale vintage scuro
                        drawCircle(
                            color = Color(0xFF222222),
                            radius = 16.dp.toPx(),
                            center = pivot
                        )
                        drawCircle(
                            color = Color(0xFF444444),
                            radius = 10.dp.toPx(),
                            center = pivot
                        )
                        drawCircle(
                            color = Color(0xFF111111),
                            radius = 4.dp.toPx(),
                            center = pivot
                        )
                    }

                    // Serigrafia vintage "SEC" al centro in basso
                    Text(
                        text = "SEC",
                        color = Color.Black.copy(alpha = 0.75f),
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )

                    // Spia LED rossa di picco (> 50s)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 16.dp, top = 16.dp)
                            .size(11.dp)
                            .background(
                                color = if (value >= 50) Color.Red else Color(0xFF5A1010),
                                shape = CircleShape
                            )
                            .border(1.dp, Color.Black.copy(alpha = 0.8f), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Trascina la lancetta sul quadrante o usa lo slider sottostante",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
