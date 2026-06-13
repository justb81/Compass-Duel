package com.justb81.compassduel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotating compass ring with opponent dots and a fixed top reticle.
 *
 * The ring rotates so that the player's current aim direction always points
 * straight up. N/E/S/W tick marks rotate with the ring — when the player
 * faces north, N is at the top; when they face east, E is at the top.
 *
 * Opponent [targets] are plotted at `(target.bearingDegrees − currentAzimuthDegrees)`
 * relative to the ring centre, so they appear at the correct absolute position.
 *
 * The reticle at the top of the canvas is fixed (does not rotate). When any
 * target has [CompassTarget.onTarget] == true, the reticle and ring outline
 * are highlighted in the [highlightColor].
 *
 * @param currentAzimuthDegrees The local player's current (calibrated) aim azimuth.
 * @param targets Opponents to render as coloured dots.
 * @param isTargeted True when any opponent is currently in the aim cone — mirrors
 *   the "warning indicator" (someone is aiming at us), distinct from [targets].
 *   Draws a pulsing outer ring in [warningColor].
 * @param warningColor Colour used for the warning ring (red for standard, yellow for kids).
 * @param highlightColor Colour used when the local player's reticle is on an opponent.
 * @param modifier Modifier for the Canvas.
 */
@Composable
fun CompassRing(
    currentAzimuthDegrees: Float,
    targets: List<CompassTarget>,
    isTargeted: Boolean,
    warningColor: Color,
    highlightColor: Color,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val anyOnTarget = targets.any { it.onTarget }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ASPECT_RATIO),
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) * RING_RADIUS_FACTOR

        // Warning outer ring (someone is aiming at us)
        if (isTargeted) {
            drawCircle(
                color = warningColor.copy(alpha = WARNING_ALPHA),
                radius = radius + WARNING_RING_OFFSET_DP.dp.toPx(),
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = WARNING_RING_STROKE_DP.dp.toPx(),
                ),
            )
        }

        // Ring outline — highlighted when on-target
        val ringColor = if (anyOnTarget) highlightColor else Color.Gray
        drawCircle(
            color = ringColor.copy(alpha = RING_ALPHA),
            radius = radius,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = RING_STROKE_DP.dp.toPx(),
            ),
        )

        // Rotating content: N/E/S/W ticks + opponent dots
        rotate(degrees = -currentAzimuthDegrees, pivot = centre) {
            drawCompassTicks(centre, radius, textMeasurer)
            targets.forEach { target ->
                drawOpponentDot(target, centre, radius)
            }
        }

        // Fixed reticle at the top (does not rotate)
        drawReticle(centre, radius, anyOnTarget, highlightColor)
    }
}

private fun DrawScope.drawCompassTicks(
    centre: Offset,
    radius: Float,
    textMeasurer: TextMeasurer,
) {
    val cardinals = listOf("N" to 0f, "E" to EAST_DEG, "S" to SOUTH_DEG, "W" to WEST_DEG)
    cardinals.forEach { (label, angle) ->
        val rad = Math.toRadians(angle.toDouble())
        val tickStart = Offset(
            centre.x + (radius - TICK_LENGTH_DP.dp.toPx()) * sin(rad).toFloat(),
            centre.y - (radius - TICK_LENGTH_DP.dp.toPx()) * cos(rad).toFloat(),
        )
        val tickEnd = Offset(
            centre.x + radius * sin(rad).toFloat(),
            centre.y - radius * cos(rad).toFloat(),
        )
        drawLine(
            color = if (label == "N") Color.Red else Color.Gray,
            start = tickStart,
            end = tickEnd,
            strokeWidth = TICK_STROKE_DP.dp.toPx(),
        )
        val textResult = textMeasurer.measure(
            text = label,
            style = TextStyle(
                fontSize = CARDINAL_FONT_SP.sp,
                fontWeight = if (label == "N") FontWeight.Bold else FontWeight.Normal,
                color = if (label == "N") Color.Red else Color.DarkGray,
            ),
        )
        val textOffset = Offset(
            centre.x + (radius - TEXT_OFFSET_DP.dp.toPx()) * sin(rad).toFloat() - textResult.size.width / 2f,
            centre.y - (radius - TEXT_OFFSET_DP.dp.toPx()) * cos(rad).toFloat() - textResult.size.height / 2f,
        )
        drawText(textResult, topLeft = textOffset)
    }
}

private fun DrawScope.drawOpponentDot(
    target: CompassTarget,
    centre: Offset,
    radius: Float,
) {
    val rad = Math.toRadians(target.bearingDegrees.toDouble())
    val dotRadius = DOT_PLACEMENT_FACTOR * radius
    val dotX = centre.x + dotRadius * sin(rad).toFloat()
    val dotY = centre.y - dotRadius * cos(rad).toFloat()

    // Outer glow when on-target
    if (target.onTarget) {
        drawCircle(
            color = target.color.copy(alpha = DOT_GLOW_ALPHA),
            radius = DOT_GLOW_RADIUS_DP.dp.toPx(),
            center = Offset(dotX, dotY),
        )
    }
    drawCircle(
        color = target.color,
        radius = DOT_RADIUS_DP.dp.toPx(),
        center = Offset(dotX, dotY),
    )
}

private fun DrawScope.drawReticle(
    centre: Offset,
    radius: Float,
    onTarget: Boolean,
    highlightColor: Color,
) {
    val reticleY = centre.y - radius * RETICLE_POSITION_FACTOR
    val reticleColor = if (onTarget) highlightColor else Color.White
    // Outer ring
    drawCircle(
        color = reticleColor,
        radius = RETICLE_OUTER_RADIUS_DP.dp.toPx(),
        center = Offset(centre.x, reticleY),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = RETICLE_STROKE_DP.dp.toPx()),
    )
    // Inner dot
    drawCircle(
        color = reticleColor,
        radius = RETICLE_INNER_RADIUS_DP.dp.toPx(),
        center = Offset(centre.x, reticleY),
    )
}

/**
 * Represents one opponent dot on the compass ring.
 *
 * @param id Player id.
 * @param name Display name for accessibility / debug.
 * @param color The dot colour assigned to this opponent.
 * @param bearingDegrees Absolute bearing from the local player toward this opponent,
 *   in degrees [0, 360). Captured by the greeting handshake (see
 *   [com.justb81.compassduel.session.GameSession.myBearings]).
 * @param onTarget True when the local player's current aim is within the aim tolerance
 *   of this opponent.
 */
data class CompassTarget(
    val id: Int,
    val name: String,
    val color: Color,
    val bearingDegrees: Float,
    val onTarget: Boolean,
)

// -------------------------------------------------------------------------
// Layout and style constants
// -------------------------------------------------------------------------

private const val EAST_DEG = 90f
private const val SOUTH_DEG = 180f
private const val WEST_DEG = 270f

private const val ASPECT_RATIO = 1f
private const val RING_RADIUS_FACTOR = 0.85f
private const val RING_ALPHA = 0.8f
private const val RING_STROKE_DP = 3f
private const val TICK_LENGTH_DP = 16f
private const val TICK_STROKE_DP = 2f
private const val CARDINAL_FONT_SP = 12f
private const val TEXT_OFFSET_DP = 30f
private const val DOT_PLACEMENT_FACTOR = 0.75f
private const val DOT_RADIUS_DP = 12f
private const val DOT_GLOW_RADIUS_DP = 20f
private const val DOT_GLOW_ALPHA = 0.35f
private const val RETICLE_POSITION_FACTOR = 0.88f
private const val RETICLE_OUTER_RADIUS_DP = 14f
private const val RETICLE_INNER_RADIUS_DP = 4f
private const val RETICLE_STROKE_DP = 2f
private const val WARNING_ALPHA = 0.6f
private const val WARNING_RING_OFFSET_DP = 8f
private const val WARNING_RING_STROKE_DP = 4f
