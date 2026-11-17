package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.CompassReliability
import com.example.data.location.HeadingState
import com.example.data.location.RadarGeoMath
import com.example.data.model.EnvironmentMode
import com.example.data.model.RadarCategory
import com.example.data.model.RadarTarget
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusEstimated
import com.example.ui.theme.StatusOutdated
import com.example.ui.theme.StatusVerified
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time Hybrid Rescue Radar Canvas:
 * - Concentric range rings scaled dynamically to radarRangeMeters (50m..1000m)
 * - Mode A (Building / Collapsed Structure UWB sector focus) vs Mode B (Open-Area 360° FMCW)
 * - North-Up or Heading-Up orientation (degrades safely to North-Up if compass unavailable)
 * - Strict 4-color marker semantics: GREEN (Team), YELLOW (Unclassified Presence), RED (SOS), GREY (Stale/Uncertain)
 * - Explicitly reports targets outside radar range & targets missing direction
 */
@Composable
fun HybridRadarCanvas(
    targets: List<RadarTarget>,
    radarRangeMeters: Double,
    environmentMode: EnvironmentMode,
    headingState: HeadingState,
    northUpMode: Boolean,
    selectedTargetId: String?,
    onTargetSelected: (RadarTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val effectiveNorthUp = northUpMode || !headingState.reliability.isUsable
    val headingDeg = if (effectiveNorthUp) 0.0 else headingState.azimuthDegrees

    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep_deg",
    )

    val outOfRangeCount = remember(targets, radarRangeMeters) {
        targets.count { (it.distanceMeters ?: 0.0) > radarRangeMeters }
    }
    val noDirectionCount = remember(targets) {
        targets.count { !it.hasDirection }
    }
    var renderedPositions by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(NavyDeep)
            .border(1.dp, outlineColor, RoundedCornerShape(18.dp))
            .testTag("hybrid_radar_canvas"),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(targets, renderedPositions) {
                    detectTapGestures { tap ->
                        val hitId = renderedPositions.entries
                            .minByOrNull { hypot(it.value.x - tap.x, it.value.y - tap.y) }
                            ?.takeIf { hypot(it.value.x - tap.x, it.value.y - tap.y) <= 56f }
                            ?.key
                        if (hitId != null) {
                            targets.firstOrNull { it.id == hitId }?.let(onTargetSelected)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxRadius = (min(w, h) * 0.44f).coerceAtLeast(60f)

            // 1. Subtle radial radar backdrop
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0A2438), NavyDeep),
                    center = Offset(cx, cy),
                    radius = maxRadius * 1.05f,
                ),
                radius = maxRadius,
                center = Offset(cx, cy),
            )

            // 2. Mode A UWB sector emphasis vs Mode B 360-degree scan ring
            if (environmentMode == EnvironmentMode.BUILDING_COLLAPSED) {
                drawArc(
                    color = StatusEstimated.copy(alpha = 0.14f),
                    startAngle = -145f,
                    sweepAngle = 110f,
                    useCenter = true,
                    topLeft = Offset(cx - maxRadius, cy - maxRadius),
                    size = Size(maxRadius * 2f, maxRadius * 2f),
                )
            }

            // 3. Concentric range rings (25%, 50%, 75%, 100%)
            val fractions = listOf(0.25f, 0.50f, 0.75f, 1.0f)
            fractions.forEach { frac ->
                val r = maxRadius * frac
                drawCircle(
                    color = CyanElectric.copy(alpha = if (frac == 1.0f) 0.45f else 0.22f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = if (frac == 1.0f) 2f else 1.2f),
                )
                val ringMeters = (radarRangeMeters * frac).toInt()
                drawRadarLabel(
                    measurer = textMeasurer,
                    text = "${ringMeters}m",
                    topLeft = Offset(cx + 4f, cy - r + 2f),
                    color = CyanElectric.copy(alpha = 0.75f),
                )
            }

            // 4. Crosshair axes & cardinal bearings
            drawLine(
                color = CyanElectric.copy(alpha = 0.22f),
                start = Offset(cx - maxRadius, cy),
                end = Offset(cx + maxRadius, cy),
                strokeWidth = 1f,
            )
            drawLine(
                color = CyanElectric.copy(alpha = 0.22f),
                start = Offset(cx, cy - maxRadius),
                end = Offset(cx, cy + maxRadius),
                strokeWidth = 1f,
            )

            val cardinals = listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)
            cardinals.forEach { (label, trueDeg) ->
                val relDeg = RadarGeoMath.relativeBearingDegrees(trueDeg, headingDeg, effectiveNorthUp)
                val rad = Math.toRadians(relDeg - 90.0)
                val lx = cx + (maxRadius + 14f) * cos(rad).toFloat() - 5f
                val ly = cy + (maxRadius + 14f) * sin(rad).toFloat() - 7f
                drawRadarLabel(
                    measurer = textMeasurer,
                    text = label,
                    topLeft = Offset(lx, ly),
                    color = if (label == "N") StatusCritical else Color.White.copy(alpha = 0.8f),
                )
            }

            // 5. Rotating radar sweep cone
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to Color.Transparent,
                    0.82f to Color.Transparent,
                    1.0f to CyanElectric.copy(alpha = 0.28f),
                    center = Offset(cx, cy),
                ),
                startAngle = sweepAngle - 90f,
                sweepAngle = 55f,
                useCenter = true,
                topLeft = Offset(cx - maxRadius, cy - maxRadius),
                size = Size(maxRadius * 2f, maxRadius * 2f),
            )

            // 6. Plot targets in GREEN / YELLOW / RED / GREY
            val posMap = mutableMapOf<String, Offset>()
            var missingDirIdx = 0
            targets.forEach { target ->
                val color = when (target.category) {
                    RadarCategory.GREEN_TEAM_MEMBER -> StatusVerified
                    RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE -> StatusEstimated
                    RadarCategory.RED_EMERGENCY_ALERT -> StatusCritical
                    RadarCategory.GREY_STALE_OR_UNCERTAIN -> StatusOutdated
                }

                val pt = if (target.distanceMeters != null && target.bearingDegrees != null) {
                    val relBearing = RadarGeoMath.relativeBearingDegrees(
                        target.bearingDegrees,
                        headingDeg,
                        effectiveNorthUp,
                    )
                    val clampedRatio = (target.distanceMeters / radarRangeMeters).coerceIn(0.05, 1.0)
                    val rPx = (maxRadius * clampedRatio).toFloat()
                    val rad = Math.toRadians(relBearing - 90.0)
                    Offset(cx + rPx * cos(rad).toFloat(), cy + rPx * sin(rad).toFloat())
                } else {
                    // Range-only or missing bearing: place gently around outer uncertainty halo in GREY
                    val angleDeg = (missingDirIdx * 37.0) % 360.0
                    missingDirIdx++
                    val rPx = if (target.distanceMeters != null) {
                        (maxRadius * (target.distanceMeters / radarRangeMeters).coerceIn(0.15, 0.96)).toFloat()
                    } else {
                        maxRadius * 0.96f
                    }
                    val rad = Math.toRadians(angleDeg - 90.0)
                    Offset(cx + rPx * cos(rad).toFloat(), cy + rPx * sin(rad).toFloat())
                }

                posMap[target.id] = pt
                val isSelected = target.id == selectedTargetId

                if (target.distanceMeters != null && target.bearingDegrees == null) {
                    // Range-only uncertainty ring (no azimuth available)
                    drawCircle(
                        color = StatusOutdated.copy(alpha = 0.25f),
                        radius = hypot(pt.x - cx, pt.y - cy),
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)),
                    )
                }

                if (isSelected) {
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = 18f, center = pt)
                }

                when (target.category) {
                    RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE -> {
                        val r = 9f
                        val diamond = Path().apply {
                            moveTo(pt.x, pt.y - r)
                            lineTo(pt.x + r, pt.y)
                            lineTo(pt.x, pt.y + r)
                            lineTo(pt.x - r, pt.y)
                            close()
                        }
                        drawPath(diamond, color = color.copy(alpha = 0.3f))
                        drawPath(diamond, color = color, style = Stroke(width = 2.4f))
                    }
                    RadarCategory.RED_EMERGENCY_ALERT -> {
                        drawCircle(color.copy(alpha = 0.3f), radius = 16f, center = pt)
                        drawCircle(color, radius = 8.5f, center = pt)
                        drawCircle(Color.White, radius = 10f, center = pt, style = Stroke(width = 2f))
                    }
                    RadarCategory.GREEN_TEAM_MEMBER -> {
                        drawCircle(color.copy(alpha = 0.25f), radius = 13f, center = pt)
                        drawCircle(color, radius = 7.5f, center = pt)
                        drawCircle(Color.White, radius = 9f, center = pt, style = Stroke(width = 1.8f))
                    }
                    RadarCategory.GREY_STALE_OR_UNCERTAIN -> {
                        drawCircle(color.copy(alpha = 0.2f), radius = 11f, center = pt)
                        drawCircle(color, radius = 6f, center = pt, style = Stroke(width = 2f))
                    }
                }

                val tagText = when (target.category) {
                    RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE -> "UNCLASSIFIED"
                    RadarCategory.RED_EMERGENCY_ALERT -> "SOS"
                    else -> target.title.take(12)
                }
                drawRadarLabel(textMeasurer, tagText, Offset(pt.x + 10f, pt.y - 7f), color)
            }
            renderedPositions = posMap

            // 7. Own center operator blip
            drawCircle(CyanElectric, radius = 6f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = 8f, center = Offset(cx, cy), style = Stroke(width = 1.8f))
        }

        // Top-left Orientation & Mode badge
        Surface(
            color = surfaceColor.copy(alpha = 0.9f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, outlineColor),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                Text(
                    text = if (effectiveNorthUp) "NORTH-UP • ${radarRangeMeters.toInt()}m" else "HEADING-UP (${headingDeg.toInt()}°) • ${radarRangeMeters.toInt()}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanElectric,
                )
                Text(
                    text = if (environmentMode == EnvironmentMode.BUILDING_COLLAPSED) "Mode A: UWB Building" else "Mode B: Open Area 360°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!northUpMode && headingState.reliability != CompassReliability.RELIABLE_HIGH && headingState.reliability != CompassReliability.RELIABLE_MEDIUM) {
                    Text(
                        text = headingState.reliability.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusEstimated,
                    )
                }
            }
        }

        // Top-right Out-of-range / Uncertain geometry count badges
        if (outOfRangeCount > 0 || noDirectionCount > 0) {
            Surface(
                color = surfaceColor.copy(alpha = 0.9f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, StatusEstimated.copy(alpha = 0.7f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .testTag("radar_overflow_badge"),
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (outOfRangeCount > 0) {
                        Text(
                            text = "$outOfRangeCount target(s) outside range",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusEstimated,
                        )
                    }
                    if (noDirectionCount > 0) {
                        Text(
                            text = "$noDirectionCount missing direction",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusOutdated,
                        )
                    }
                }
            }
        }

        // Bottom-left 4-color legend
        Surface(
            color = surfaceColor.copy(alpha = 0.88f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, outlineColor.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadarLegendDot(StatusVerified, "Team")
                RadarLegendDot(StatusEstimated, "Presence")
                RadarLegendDot(StatusCritical, "SOS")
                RadarLegendDot(StatusOutdated, "Stale/Low")
            }
        }
    }
}

@Composable
private fun RadarLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadarLabel(
    measurer: TextMeasurer,
    text: String,
    topLeft: Offset,
    color: Color,
) {
    val result = measurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = 10.sp),
    )
    if (topLeft.x >= 0f && topLeft.y >= 0f && topLeft.x + result.size.width <= size.width && topLeft.y + result.size.height <= size.height) {
        drawText(result, topLeft = topLeft)
    }
}
