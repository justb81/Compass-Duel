package com.justb81.compassduel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Center-of-compass shield indicator for Standard Mode.
 *
 * Renders a shield glyph that communicates three things at a glance:
 * - **budget arc**: a ring around the glyph proportional to [remainingFraction];
 *   it shrinks as the per-round shield-time budget is consumed.
 * - **active**: a solid, glowing shield when [active] (the host reports SHIELDING).
 * - **loading**: while the shield is arming (held upright & steady, not yet active)
 *   the glyph fills from the bottom up to [armProgress].
 * When the budget is exhausted (`remainingFraction <= 0`) the glyph is dimmed.
 *
 * All inputs are plain values so the composable is preview- and test-friendly.
 *
 * @param active True while the shield is active (host-authoritative).
 * @param armProgress Local arming progress in `[0, 1]` (the <1 s hold).
 * @param remainingFraction Remaining shield budget in `[0, 1]`.
 * @param modifier Layout modifier; the indicator draws within its bounds.
 */
@Composable
fun ShieldIndicator(
    active: Boolean,
    armProgress: Float,
    remainingFraction: Float,
    modifier: Modifier = Modifier,
) {
    val depleted = remainingFraction <= 0f
    val activeColor = MaterialTheme.colorScheme.primary
    val armingColor = MaterialTheme.colorScheme.primary.copy(alpha = ARMING_ALPHA)
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DIM_ALPHA)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val budgetColor = if (depleted) dimColor else activeColor

    Canvas(modifier = modifier) {
        drawBudgetRing(remainingFraction.coerceIn(0f, 1f), trackColor, budgetColor)

        val shield = shieldPath()
        val glyphColor = if (active && !depleted) activeColor else dimColor
        // Glyph outline.
        drawPath(shield, glyphColor, style = Stroke(width = GLYPH_STROKE_DP.dp.toPx()))

        // Fill: full when active, otherwise rise from the bottom to the arming progress.
        val fill = if (active) 1f else armProgress.coerceIn(0f, 1f)
        if (fill > 0f && !depleted) {
            val glyphTop = size.height * GLYPH_INSET
            val glyphBottom = size.height * (1f - GLYPH_INSET)
            val fillTop = glyphBottom - (glyphBottom - glyphTop) * fill
            clipRect(top = fillTop, left = 0f, right = size.width, bottom = size.height) {
                drawPath(shield, if (active) activeColor else armingColor)
            }
        }
    }
}

/** Draws the circular budget track plus the remaining-budget arc, starting at the top. */
private fun DrawScope.drawBudgetRing(fraction: Float, track: Color, arc: Color) {
    val stroke = Stroke(width = RING_STROKE_DP.dp.toPx())
    val inset = stroke.width / 2f
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
    drawArc(track, RING_START_ANGLE, FULL_CIRCLE, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    drawArc(arc, RING_START_ANGLE, FULL_CIRCLE * fraction, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
}

/** Builds a classic shield silhouette inset within the canvas bounds. */
private fun DrawScope.shieldPath(): Path {
    val left = size.width * GLYPH_INSET
    val right = size.width * (1f - GLYPH_INSET)
    val top = size.height * GLYPH_INSET
    val bottom = size.height * (1f - GLYPH_INSET)
    val cx = size.width / 2f
    val glyphHeight = bottom - top
    val shoulder = top + glyphHeight * SHOULDER_FACTOR
    return Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, shoulder)
        quadraticBezierTo(right, top + glyphHeight * CURVE_FACTOR, cx, bottom)
        quadraticBezierTo(left, top + glyphHeight * CURVE_FACTOR, left, shoulder)
        close()
    }
}

private const val FULL_CIRCLE = 360f
private const val RING_START_ANGLE = -90f
private const val RING_STROKE_DP = 5f
private const val GLYPH_STROKE_DP = 2.5f
private const val GLYPH_INSET = 0.28f
private const val SHOULDER_FACTOR = 0.45f
private const val CURVE_FACTOR = 0.8f
private const val ARMING_ALPHA = 0.45f
private const val DIM_ALPHA = 0.3f
private const val TRACK_ALPHA = 0.2f
