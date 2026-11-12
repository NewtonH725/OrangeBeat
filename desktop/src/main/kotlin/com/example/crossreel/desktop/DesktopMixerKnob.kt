package com.example.crossreel.desktop

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun DesktopMixerKnobWithVuMeter(
    selectedDuration: Int,
    onDurationChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(15, 30, 45, 60)
    val minDur = 15f
    val maxDur = 60f

    val targetFraction = ((selectedDuration - minDur) / (maxDur - minDur)).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "NeedlePhysics"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VintageVuMeterDisplay(
            selectedDuration = selectedDuration,
            animatedFraction = animatedFraction,
            steps = steps
        )

        Spacer(modifier = Modifier.height(14.dp))

        AnalogFaderTrack(
            selectedDuration = selectedDuration,
            steps = steps,
            onDurationChange = onDurationChange
        )
    }
}

@Composable
private fun VintageVuMeterDisplay(
    selectedDuration: Int,
    animatedFraction: Float,
    steps: List<Int>
) {
    val textMeasurer = rememberTextMeasurer()
    val scaleTextStyle = remember {
        TextStyle(
            color = Color(0xFF4E342E),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
    val redScaleTextStyle = remember {
        TextStyle(
            color = Color(0xFFC62828),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }

    val measuredLabels = remember(steps, textMeasurer) {
        steps.map { step ->
            val style = if (step >= 45) redScaleTextStyle else scaleTextStyle
            textMeasurer.measure("${step}s", style)
        }
    }

    val vuBacklightBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFE082),
                Color(0xFFFFCA28),
                Color(0xFFD89C27),
                Color(0xFF8D5B14)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0908))
            .border(1.5.dp, Color(0xFF3A3530), RoundedCornerShape(16.dp))
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(vuBacklightBrush)
                .border(1.dp, Color(0xFF5D4037), RoundedCornerShape(12.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val pivot = Offset(w / 2f, h * 1.15f)
                val radius = h * 0.88f
                val startAngle = -132f
                val sweepTotal = 84f

                val redZoneStart = startAngle + (sweepTotal * 0.65f)
                val redZoneSweep = sweepTotal * 0.35f
                drawArc(
                    color = Color(0xFFD32F2F),
                    startAngle = redZoneStart,
                    sweepAngle = redZoneSweep,
                    useCenter = false,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    color = Color(0xFF3E2723),
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * 0.65f,
                    useCenter = false,
                    topLeft = Offset(pivot.x - radius, pivot.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                val numSubTicks = 18
                for (i in 0..numSubTicks) {
                    val frac = i.toFloat() / numSubTicks
                    val angleDeg = startAngle + frac * sweepTotal
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val isMajor = i % 6 == 0
                    val innerR = radius - (if (isMajor) 12.dp.toPx() else 6.dp.toPx())
                    val outerR = radius

                    drawLine(
                        color = if (frac >= 0.65f) Color(0xFFB71C1C) else Color(0xFF3E2723),
                        start = Offset(
                            pivot.x + (innerR * cos(angleRad)).toFloat(),
                            pivot.y + (innerR * sin(angleRad)).toFloat()
                        ),
                        end = Offset(
                            pivot.x + (outerR * cos(angleRad)).toFloat(),
                            pivot.y + (outerR * sin(angleRad)).toFloat()
                        ),
                        strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                steps.forEachIndexed { idx, _ ->
                    val frac = idx.toFloat() / (steps.size - 1)
                    val angleDeg = startAngle + frac * sweepTotal
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val textR = radius - 26.dp.toPx()
                    val tx = pivot.x + (textR * cos(angleRad)).toFloat()
                    val ty = pivot.y + (textR * sin(angleRad)).toFloat()
                    val layoutResult = measuredLabels[idx]
                    drawText(
                        textLayoutResult = layoutResult,
                        topLeft = Offset(
                            tx - layoutResult.size.width / 2f,
                            ty - layoutResult.size.height / 2f
                        )
                    )
                }

                val needleAngleDeg = startAngle + animatedFraction * sweepTotal
                val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
                val needleLength = radius + 4.dp.toPx()
                val needleTip = Offset(
                    pivot.x + (needleLength * cos(needleAngleRad)).toFloat(),
                    pivot.y + (needleLength * sin(needleAngleRad)).toFloat()
                )

                drawLine(
                    color = Color(0x40000000),
                    start = Offset(pivot.x + 2.dp.toPx(), pivot.y),
                    end = Offset(needleTip.x + 2.dp.toPx(), needleTip.y + 2.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                drawLine(
                    color = Color(0xFFD84315),
                    start = pivot,
                    end = needleTip,
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            Text(
                text = "VU",
                color = Color(0x663E2723),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 10.dp)
            )

            Surface(
                color = Color(0xCC1A1510),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "${selectedDuration}s",
                    style = TabularNumberStyle.copy(color = WarmAmber, fontSize = 13.sp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AnalogFaderTrack(
    selectedDuration: Int,
    steps: List<Int>,
    onDurationChange: (Int) -> Unit
) {
    val thumbBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF45423E), Color(0xFF282523), Color(0xFF1A1816))
        )
    }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceContainerHigh)
            .border(1.dp, OutlineVariantColor, RoundedCornerShape(14.dp))
            .pointerInput(steps) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (trackWidthPx > 0f) {
                            val frac = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                            val idx = (frac * (steps.size - 1)).roundToInt().coerceIn(0, steps.lastIndex)
                            if (steps[idx] != selectedDuration) onDurationChange(steps[idx])
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (trackWidthPx > 0f) {
                            val frac = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                            val idx = (frac * (steps.size - 1)).roundToInt().coerceIn(0, steps.lastIndex)
                            if (steps[idx] != selectedDuration) onDurationChange(steps[idx])
                        }
                    }
                )
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        trackWidthPx = constraints.maxWidth.toFloat()
        val usableWidth = maxWidth - 36.dp
        val currentIdx = steps.indexOf(selectedDuration).coerceAtLeast(0)
        val fraction = currentIdx.toFloat() / (steps.size - 1).coerceAtLeast(1)

        val animatedFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
            label = "FaderKnobPos"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF0A0908))
                .border(0.5.dp, Color(0xFF3A3530), RoundedCornerShape(3.dp))
        )

        Box(
            modifier = Modifier
                .offset(x = usableWidth * animatedFraction)
                .width(36.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(thumbBrush)
                .border(1.dp, Color(0xFF605B55), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FlameOrange)
            )
        }
    }
}
