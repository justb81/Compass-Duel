package com.justb81.compassduel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.net.protocol.GameMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transient, element-aware action effect surfaced by the game ViewModel and
 * drawn over the [CompassRing] and the flash overlay.
 *
 * Effects are derived entirely from events/state the ViewModel already observes
 * (host [com.justb81.compassduel.net.protocol.GameEvent]s, the local player's
 * [com.justb81.compassduel.net.protocol.PlayerStatus], and the on-target
 * reticle) — no new network payload fields or engine messages are introduced.
 *
 * @param kind The category of effect to play.
 * @param element The local player's element for Standard-Mode theming; null in Kids Mode.
 * @param triggerId A monotonically increasing id; a change restarts the animation
 *   even when [kind] repeats, so back-to-back attacks each animate.
 */
data class ActionEffect(
    val kind: ActionEffectKind,
    val element: Element?,
    val triggerId: Long,
)

/** Categories of one-shot action effects. */
enum class ActionEffectKind {
    /** Local player launched an attack/toss — projectile flies up toward the reticle. */
    PROJECTILE_FIRED,

    /** Local player landed a hit (Standard) or a catch (Kids) — burst at the reticle. */
    IMPACT_LANDED,

    /** Local player's attack was blocked/bubbled — guarded burst at the reticle. */
    IMPACT_BLOCKED,

    /** Local player took damage (Standard only) — inward shock from the screen edge. */
    DAMAGE_TAKEN,
}

/**
 * Full-screen overlay that renders the active [effect] (one-shot) plus the
 * persistent shield aura while [shielding].
 *
 * Drawing is pure Canvas with a small set of [Animatable]s; no per-frame
 * allocations. Effects branch on [mode]: Kids Mode uses friendly sparkles,
 * bubbles and stars — never aggressive flames or red impact shocks.
 *
 * @param effect The transient effect to play, or null when none is active.
 * @param mode The active game mode; drives friendly vs. combat visuals.
 * @param shielding True while the local player holds a shield/bubble.
 * @param modifier Modifier for the overlay Canvas (should fill the ring area).
 */
@Composable
fun ActionEffectOverlay(
    effect: ActionEffect?,
    mode: GameMode,
    shielding: Boolean,
    modifier: Modifier = Modifier,
) {
    // One-shot progress 0f..1f, restarted whenever the trigger id changes.
    val progress = remember { Animatable(1f) }
    val triggerId = effect?.triggerId ?: -1L
    LaunchedEffect(triggerId) {
        if (effect != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = tween(EFFECT_DURATION_MILLIS, easing = LinearEasing))
        }
    }

    // Slow, looping phase for the persistent shield aura shimmer.
    val auraTransition = rememberInfiniteTransition(label = "aura")
    val auraPhase by auraTransition.animateFloatLooping()

    Canvas(modifier = modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) * RING_RADIUS_FACTOR
        val palette = palette(mode, effect?.element)

        if (shielding) {
            drawShieldAura(centre, radius, palette, auraPhase)
        }

        val active = effect ?: return@Canvas
        val t = progress.value
        if (t >= 1f) return@Canvas
        when (active.kind) {
            ActionEffectKind.PROJECTILE_FIRED -> drawProjectile(centre, radius, palette, mode, t)
            ActionEffectKind.IMPACT_LANDED -> drawImpactBurst(centre, radius, palette, mode, landed = true, t = t)
            ActionEffectKind.IMPACT_BLOCKED -> drawImpactBurst(centre, radius, palette, mode, landed = false, t = t)
            ActionEffectKind.DAMAGE_TAKEN -> drawDamageShock(centre, radius, t)
        }
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatLooping() = animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(AURA_PERIOD_MILLIS, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    ),
    label = "auraPhase",
)

// -------------------------------------------------------------------------
// Palette
// -------------------------------------------------------------------------

/**
 * Resolves the colour pair (primary, accent) for the effect. Standard Mode uses
 * element-themed combat colours; Kids Mode always uses friendly magical colours
 * regardless of element so no aggressive reds/flames appear.
 */
private fun palette(mode: GameMode, element: Element?): EffectPalette = when (mode) {
    GameMode.KIDS -> EffectPalette(KIDS_PRIMARY, KIDS_ACCENT)
    GameMode.STANDARD -> when (element) {
        Element.FIRE -> EffectPalette(FIRE_PRIMARY, FIRE_ACCENT)
        Element.WATER -> EffectPalette(WATER_PRIMARY, WATER_ACCENT)
        Element.EARTH -> EffectPalette(EARTH_PRIMARY, EARTH_ACCENT)
        Element.LIGHTNING -> EffectPalette(LIGHTNING_PRIMARY, LIGHTNING_ACCENT)
        null -> EffectPalette(Color.White, Color.LightGray)
    }
}

private data class EffectPalette(val primary: Color, val accent: Color)

// -------------------------------------------------------------------------
// One-shot effects
// -------------------------------------------------------------------------

/** A projectile/particle travelling from the ring centre up toward the reticle. */
private fun DrawScope.drawProjectile(
    centre: Offset,
    radius: Float,
    palette: EffectPalette,
    mode: GameMode,
    t: Float,
) {
    val reticleY = centre.y - radius * RETICLE_POSITION_FACTOR
    val travel = centre.y - reticleY
    val headY = centre.y - travel * t
    val head = Offset(centre.x, headY)
    val alpha = (1f - t).coerceIn(0f, 1f)

    // A few trailing particles behind the head — fixed count, no per-frame allocation.
    for (i in 0 until PROJECTILE_PARTICLES) {
        val lag = i * PROJECTILE_LAG
        val pt = (t - lag).coerceAtLeast(0f)
        val py = centre.y - travel * pt
        val spread = (sin((pt + i) * PROJECTILE_WOBBLE) * PROJECTILE_SPREAD_DP.dp.toPx())
        val pos = Offset(centre.x + spread, py)
        val pr = PROJECTILE_RADIUS_DP.dp.toPx() * (1f - i.toFloat() / PROJECTILE_PARTICLES)
        drawCircle(palette.accent.copy(alpha = alpha * TRAIL_ALPHA), radius = pr, center = pos)
    }
    // Head — slightly larger glowing core.
    drawCircle(palette.primary.copy(alpha = alpha * GLOW_ALPHA), radius = PROJECTILE_RADIUS_DP.dp.toPx() * GLOW_SCALE, center = head)
    drawCircle(palette.primary.copy(alpha = alpha), radius = PROJECTILE_RADIUS_DP.dp.toPx(), center = head)
    if (mode == GameMode.KIDS) {
        // Friendly sparkle cross on the head.
        drawSparkle(head, PROJECTILE_RADIUS_DP.dp.toPx() * SPARKLE_SCALE, palette.accent.copy(alpha = alpha))
    }
}

/** A radial burst at the reticle when the local attack lands or is blocked. */
private fun DrawScope.drawImpactBurst(
    centre: Offset,
    radius: Float,
    palette: EffectPalette,
    mode: GameMode,
    landed: Boolean,
    t: Float,
) {
    val reticleY = centre.y - radius * RETICLE_POSITION_FACTOR
    val at = Offset(centre.x, reticleY)
    val alpha = (1f - t).coerceIn(0f, 1f)
    val burstRadius = (IMPACT_MIN_RADIUS_DP + IMPACT_GROWTH_DP * t).dp.toPx()

    // Expanding ring.
    val ringColor = if (landed) palette.primary else palette.accent
    drawCircle(
        color = ringColor.copy(alpha = alpha),
        radius = burstRadius,
        center = at,
        style = Stroke(width = IMPACT_STROKE_DP.dp.toPx()),
    )
    // Radiating spokes (sparkles in Kids, sharp rays in Standard).
    val spokes = if (mode == GameMode.KIDS) KIDS_SPOKES else STANDARD_SPOKES
    val inner = burstRadius * IMPACT_INNER_FACTOR
    for (i in 0 until spokes) {
        val ang = (i.toFloat() / spokes) * TWO_PI
        val dir = Offset(sin(ang.toDouble()).toFloat(), -cos(ang.toDouble()).toFloat())
        val start = at + dir * inner
        val end = at + dir * burstRadius
        if (mode == GameMode.KIDS) {
            drawCircle(palette.accent.copy(alpha = alpha), radius = SPARKLE_DOT_DP.dp.toPx(), center = end)
        } else {
            drawLine(ringColor.copy(alpha = alpha), start = start, end = end, strokeWidth = IMPACT_STROKE_DP.dp.toPx())
        }
    }
    if (!landed) {
        // A guard arc to read as "blocked/bubbled".
        drawCircle(
            color = palette.accent.copy(alpha = alpha * GLOW_ALPHA),
            radius = burstRadius * BLOCK_GUARD_FACTOR,
            center = at,
            style = Stroke(width = IMPACT_STROKE_DP.dp.toPx() * BLOCK_STROKE_SCALE),
        )
    }
}

/** Inward shock from the screen edge when the local player takes damage (Standard only). */
private fun DrawScope.drawDamageShock(
    centre: Offset,
    radius: Float,
    t: Float,
) {
    val alpha = (1f - t).coerceIn(0f, 1f) * DAMAGE_ALPHA
    // Vignette ring collapsing inward toward the ring.
    val shockRadius = radius + (size.minDimension * DAMAGE_START_FACTOR) * (1f - t)
    drawCircle(
        color = Color.Red.copy(alpha = alpha),
        radius = shockRadius,
        center = centre,
        style = Stroke(width = DAMAGE_STROKE_DP.dp.toPx()),
    )
}

// -------------------------------------------------------------------------
// Persistent defensive visuals
// -------------------------------------------------------------------------

/** A shimmering protective aura ring drawn while shielding (Standard) / bubbling (Kids). */
private fun DrawScope.drawShieldAura(
    centre: Offset,
    radius: Float,
    palette: EffectPalette,
    phase: Float,
) {
    val pulse = SHIELD_BASE_ALPHA + SHIELD_PULSE_ALPHA * sin(phase * TWO_PI).toFloat()
    val auraRadius = radius + SHIELD_OFFSET_DP.dp.toPx()
    drawCircle(
        color = palette.accent.copy(alpha = pulse.coerceIn(0f, 1f)),
        radius = auraRadius,
        center = centre,
        style = Stroke(width = SHIELD_STROKE_DP.dp.toPx()),
    )
    drawCircle(
        color = palette.primary.copy(alpha = (pulse * GLOW_ALPHA).coerceIn(0f, 1f)),
        radius = auraRadius - SHIELD_INNER_GAP_DP.dp.toPx(),
        center = centre,
        style = Stroke(width = SHIELD_STROKE_DP.dp.toPx() * SHIELD_INNER_SCALE),
    )
}

/** A small four-point sparkle cross. */
private fun DrawScope.drawSparkle(at: Offset, size: Float, color: Color) {
    drawLine(color, start = Offset(at.x - size, at.y), end = Offset(at.x + size, at.y), strokeWidth = SPARKLE_STROKE_DP.dp.toPx())
    drawLine(color, start = Offset(at.x, at.y - size), end = Offset(at.x, at.y + size), strokeWidth = SPARKLE_STROKE_DP.dp.toPx())
}

private operator fun Offset.times(scalar: Float): Offset = Offset(x * scalar, y * scalar)

// -------------------------------------------------------------------------
// Constants
// -------------------------------------------------------------------------

private const val TWO_PI = (2.0 * Math.PI).toFloat()
private const val EFFECT_DURATION_MILLIS = 450
private const val AURA_PERIOD_MILLIS = 1_400

// Must mirror CompassRing's ring/reticle geometry so effects land on the ring.
private const val RING_RADIUS_FACTOR = 0.85f
private const val RETICLE_POSITION_FACTOR = 0.88f

private const val GLOW_ALPHA = 0.4f
private const val GLOW_SCALE = 1.8f
private const val TRAIL_ALPHA = 0.6f

// Projectile
private const val PROJECTILE_PARTICLES = 4
private const val PROJECTILE_LAG = 0.12f
private const val PROJECTILE_WOBBLE = 6f
private const val PROJECTILE_SPREAD_DP = 6f
private const val PROJECTILE_RADIUS_DP = 8f
private const val SPARKLE_SCALE = 1.6f

// Impact burst
private const val IMPACT_MIN_RADIUS_DP = 6f
private const val IMPACT_GROWTH_DP = 26f
private const val IMPACT_STROKE_DP = 3f
private const val IMPACT_INNER_FACTOR = 0.5f
private const val STANDARD_SPOKES = 8
private const val KIDS_SPOKES = 6
private const val SPARKLE_DOT_DP = 3f
private const val BLOCK_GUARD_FACTOR = 0.7f
private const val BLOCK_STROKE_SCALE = 1.5f

// Damage shock
private const val DAMAGE_ALPHA = 0.5f
private const val DAMAGE_START_FACTOR = 0.25f
private const val DAMAGE_STROKE_DP = 6f

// Shield aura
private const val SHIELD_BASE_ALPHA = 0.45f
private const val SHIELD_PULSE_ALPHA = 0.2f
private const val SHIELD_OFFSET_DP = 14f
private const val SHIELD_STROKE_DP = 4f
private const val SHIELD_INNER_GAP_DP = 6f
private const val SHIELD_INNER_SCALE = 0.6f

// Sparkle helper
private const val SPARKLE_STROKE_DP = 2f
private val KIDS_PRIMARY = Color(0xFFFFD54F)
private val KIDS_ACCENT = Color(0xFFFFF8E1)

// Standard-Mode element combat colours (primary, accent).
private val FIRE_PRIMARY = Color(0xFFFF7043)
private val FIRE_ACCENT = Color(0xFFFFCA28)
private val WATER_PRIMARY = Color(0xFF29B6F6)
private val WATER_ACCENT = Color(0xFFB3E5FC)
private val EARTH_PRIMARY = Color(0xFF66BB6A)
private val EARTH_ACCENT = Color(0xFFC5E1A5)
private val LIGHTNING_PRIMARY = Color(0xFFFFEE58)
private val LIGHTNING_ACCENT = Color(0xFFFFF59D)
