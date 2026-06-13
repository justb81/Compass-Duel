package com.justb81.compassduel.ui.screens.game

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justb81.compassduel.BuildConfig
import com.justb81.compassduel.R
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.PlayerStatus
import com.justb81.compassduel.ui.components.ActionEffectOverlay
import com.justb81.compassduel.ui.components.CompassRing
import com.justb81.compassduel.ui.components.ShieldIndicator
import kotlinx.coroutines.flow.StateFlow

private val SCREEN_PADDING_DP = 16.dp
private val SECTION_SPACING_DP = 8.dp
private val CHIP_SPACING_DP = 4.dp
private const val ELIMINATED_OVERLAY_ALPHA = 0.6f
private const val HELP_SCRIM_ALPHA = 0.7f
private const val HP_MAX = 100f
private const val MILLIS_PER_SECOND = 1_000L
private val WARNING_COLOR_KIDS = Color(0xFFFFEB3B)
private val HIGHLIGHT_COLOR = Color(0xFF4CAF50)
private val HELP_CARD_MAX_WIDTH_DP = 360.dp
private val HELP_CARD_PADDING_DP = 20.dp

// Mirrors CompassRing's aspect ratio so the effect overlay shares its geometry.
private const val COMPASS_ASPECT_RATIO = 1f

// Diameter of the center shield indicator overlaid on the compass.
private val SHIELD_INDICATOR_SIZE = 96.dp

// Diameter of the hold-to-shield touch button below the compass.
private val SHIELD_BUTTON_SIZE = 72.dp
private const val SHIELD_BUTTON_IDLE_ALPHA = 0.35f

/**
 * Game screen — renders COUNTDOWN, PLAYING and ROUND_OVER phases.
 *
 * The compass ring updates at full sensor rate via the ViewModel's orientation flow;
 * game-state HUD updates at the 10 Hz snapshot cadence.
 *
 * @param viewModel Injected [GameViewModel].
 */
@Composable
fun GameScreen(
    viewModel: GameViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    KeepScreenOn()
    ImmersiveFullScreen()

    // Help overlay visibility is screen-local and default-collapsed; it is
    // toggled by the "?" affordance and dismissed by tapping the scrim.
    var helpVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is GameUiState.Countdown -> CountdownContent(state)
            is GameUiState.Playing -> PlayingContent(
                state = state,
                compassStateFlow = viewModel.compassState,
                onFire = viewModel::fireTouch,
                onShieldPressChange = viewModel::setTouchShield,
            )
            GameUiState.RoundOver -> RoundOverContent()
        }

        // The how-to-play overlay is offered during the COUNTDOWN and PLAYING
        // phases only; its content branches on the active game mode.
        val helpMode: GameMode? = when (val state = uiState) {
            is GameUiState.Countdown -> state.mode
            is GameUiState.Playing -> state.mode
            GameUiState.RoundOver -> null
        }
        if (helpMode != null) {
            HelpAffordance(
                expanded = helpVisible,
                onToggle = { helpVisible = !helpVisible },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(SCREEN_PADDING_DP),
            )
            if (helpVisible) {
                HelpOverlay(
                    mode = helpMode,
                    onDismiss = { helpVisible = false },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// How-to-play overlay
// -------------------------------------------------------------------------

/**
 * Small "?" icon button that toggles the [HelpOverlay]. Placed in the top-end
 * corner so it stays clear of the [CompassRing].
 */
@Composable
private fun HelpAffordance(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(if (expanded) R.string.game_help_close else R.string.game_help_open)
    // material-icons-extended is intentionally not a dependency, so use a "?" glyph
    // (matching the emoji-marker convention used on the home and lobby screens).
    FilledTonalIconButton(
        onClick = onToggle,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(text = "?", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Translucent scrim + card summarizing the controls. Tapping anywhere on the
 * scrim (or the affordance again) dismisses it. Content branches on [mode] so
 * Kids Mode never shows combat wording.
 */
@Composable
private fun HelpOverlay(
    mode: GameMode,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = HELP_SCRIM_ALPHA))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(SCREEN_PADDING_DP)
                .widthIn(max = HELP_CARD_MAX_WIDTH_DP),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(HELP_CARD_PADDING_DP),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP),
            ) {
                val titleRes = when (mode) {
                    GameMode.STANDARD -> R.string.game_help_title_standard
                    GameMode.KIDS -> R.string.game_help_title_kids
                }
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                )
                val lineResources = when (mode) {
                    GameMode.STANDARD -> listOf(
                        R.string.game_help_aim_standard,
                        R.string.game_help_attack_standard,
                        R.string.game_help_shield_standard,
                        R.string.game_help_touch_standard,
                    )
                    GameMode.KIDS -> listOf(
                        R.string.game_help_aim_kids,
                        R.string.game_help_toss_kids,
                        R.string.game_help_bubble_kids,
                        R.string.game_help_touch_kids,
                        R.string.game_help_rest_kids,
                    )
                }
                lineResources.forEach { lineRes ->
                    Text(
                        text = stringResource(lineRes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.game_help_dismiss_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SECTION_SPACING_DP),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Side effect tied to the [GameScreen] lifecycle: keeps the screen awake for the
 * whole time the game screen is shown, then releases it on dispose. During a
 * round the player moves the phone instead of touching the display, so without
 * this the screen would dim or the device would auto-lock mid-match.
 *
 * Keyed on [LocalView] (always non-null inside a composition and guaranteed to
 * belong to the real window): setting [android.view.View.setKeepScreenOn]
 * applies `FLAG_KEEP_SCREEN_ON` to the host window without depending on
 * resolving the Activity.
 *
 * Disposal fires whenever the [GameRoute] composable leaves the back stack —
 * navigating to results/lobby, back navigation, or a peer-lost teardown — so
 * the screen never stays awake indefinitely.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Side effect tied to the [GameScreen] lifecycle: enters immersive full-screen
 * (status + navigation bars hidden) for the whole time the game screen is shown,
 * then restores the bars on dispose. System UI is distracting during a
 * physical-movement game and an accidental swipe on the bars can interrupt play.
 *
 * The window is resolved from [LocalView]'s context (unwrapping any
 * [ContextWrapper]s) rather than via `LocalActivity`. The hide is re-applied on
 * window-focus regain because Android re-shows the bars after focus loss and
 * window settle — the common reason a one-shot `hide()` does not stick under
 * `enableEdgeToEdge`.
 */
@Composable
private fun ImmersiveFullScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

            onDispose {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** Unwraps [ContextWrapper]s to find the host [Activity], or null if none. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// -------------------------------------------------------------------------
// Countdown phase
// -------------------------------------------------------------------------

@Composable
private fun CountdownContent(state: GameUiState.Countdown) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP),
            modifier = Modifier.padding(SCREEN_PADDING_DP),
        ) {
            Text(
                text = stringResource(R.string.game_countdown_instruction),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            if (state.secondsLeft > 0) {
                Text(
                    text = state.secondsLeft.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Playing phase
// -------------------------------------------------------------------------

@Composable
private fun PlayingContent(
    state: GameUiState.Playing,
    compassStateFlow: StateFlow<CompassUiState>,
    onFire: () -> Unit,
    onShieldPressChange: (Boolean) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SCREEN_PADDING_DP),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top HUD — mode-branched. Recomposes only at snapshot cadence; the
            // sensor-rate compass slice is isolated in CompassSection below (#71).
            when (state.mode) {
                GameMode.STANDARD -> StandardHud(state)
                GameMode.KIDS -> KidsHud(state)
            }

            // Compass ring + shield indicator + effect overlay, isolated in
            // CompassSection so it recomposes at sensor rate without re-running the HUD (#71).
            //
            // Double-tapping anywhere on the compass area fires (touch alternative to
            // the swing gesture). The detector lives on this box — not the whole
            // screen — so it stays clear of the hold-to-shield button below and the
            // help "?" affordance the parent draws on top.
            CompassSection(
                state = state,
                compassStateFlow = compassStateFlow,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(state.isEliminated) {
                        detectTapGestures(onDoubleTap = { if (!state.isEliminated) onFire() })
                    },
            )

            // Hold-to-shield button (touch alternative to the upright-hold gesture).
            // Hidden once eliminated — a spectator has nothing to shield.
            if (!(state.isEliminated && state.mode == GameMode.STANDARD)) {
                ShieldHoldButton(
                    mode = state.mode,
                    active = state.shielding,
                    onPressChange = onShieldPressChange,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = SECTION_SPACING_DP),
                )
            }

            // Bottom status line
            StatusLine(state)
        }

        // Eliminated spectator overlay (Standard only — no red in Kids)
        if (state.isEliminated && state.mode == GameMode.STANDARD) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = ELIMINATED_OVERLAY_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.game_eliminated_spectating),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The compass ring + shield indicator + action-effect overlay, isolated from the
 * HUD so it can recompose at full sensor rate without re-running the HUD (#71).
 *
 * The high-frequency [CompassUiState] (azimuth, shield-arm progress, debug aim) is
 * collected here; snapshot-cadence values (targets, warning, shield budget) come from
 * [state]. The action-effect overlay shares the ring's geometry so projectiles/impacts
 * land on the reticle.
 */
@Composable
private fun CompassSection(
    state: GameUiState.Playing,
    compassStateFlow: StateFlow<CompassUiState>,
    modifier: Modifier = Modifier,
) {
    val compass by compassStateFlow.collectAsStateWithLifecycle()
    val warningColor = if (state.mode == GameMode.STANDARD) Color.Red else WARNING_COLOR_KIDS
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        CompassRing(
            currentAzimuthDegrees = compass.azimuthDegrees,
            targets = state.compassTargets,
            isTargeted = state.warningActive,
            warningColor = warningColor,
            highlightColor = HIGHLIGHT_COLOR,
        )
        ActionEffectOverlay(
            effect = state.actionEffect,
            mode = state.mode,
            shielding = state.shielding,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COMPASS_ASPECT_RATIO),
        )
        if (state.mode == GameMode.STANDARD) {
            ShieldIndicator(
                active = state.shielding,
                armProgress = compass.shieldArmProgress,
                remainingFraction = state.shieldRemainingFraction,
                modifier = Modifier.size(SHIELD_INDICATOR_SIZE),
            )
        }
        if (BuildConfig.DEBUG) {
            compass.debugAimDegrees?.let { aim ->
                Text(
                    text = "Az:%.1f Aim:%.1f".format(compass.azimuthDegrees, aim),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = SECTION_SPACING_DP),
                )
            }
        }
    }
}

// -------------------------------------------------------------------------
// Standard HUD
// -------------------------------------------------------------------------

@Composable
private fun StandardHud(state: GameUiState.Playing) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP)) {
        // HP bar
        LinearProgressIndicator(
            progress = { (state.myHp / HP_MAX).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Element emoji+name label (already formatted by ViewModel)
            Text(text = state.myElement ?: "", style = MaterialTheme.typography.bodyLarge)
            // Round timer
            val seconds = (state.remainingMillis / MILLIS_PER_SECOND).toInt()
            Text(
                text = stringResource(R.string.game_timer_label, seconds),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        // Best-of-3 round-win chips
        if (state.roundWins.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING_DP)) {
                state.roundWins.forEach { (playerId, wins) ->
                    val name = state.compassTargets
                        .firstOrNull { it.id == playerId }?.name
                        ?: playerId.toString()
                    AssistChip(
                        onClick = {},
                        label = { Text(text = "$name: $wins") },
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Kids HUD
// -------------------------------------------------------------------------

@Composable
private fun KidsHud(state: GameUiState.Playing) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP),
    ) {
        // Oversized star counter
        Text(
            text = "⭐ ${state.myStars}",
            style = MaterialTheme.typography.headlineLarge,
        )
        // Sprite emoji (already formatted by ViewModel)
        val spriteEmoji = state.mySpriteEmoji ?: ""
        if (spriteEmoji.isNotEmpty()) {
            Text(text = spriteEmoji, style = MaterialTheme.typography.headlineMedium)
        }
        // Round timer
        val seconds = (state.remainingMillis / MILLIS_PER_SECOND).toInt()
        Text(
            text = stringResource(R.string.game_timer_label, seconds),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Rest countdown notice when resting
        if (state.restingUntilMillis > 0) {
            Text(
                text = stringResource(R.string.game_status_resting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

// -------------------------------------------------------------------------
// Hold-to-shield touch button
// -------------------------------------------------------------------------

/**
 * Press-and-hold button that raises the shield (Standard) or magic bubble (Kids)
 * for as long as it is held — the touch alternative to the upright-hold gesture.
 *
 * Always shown alongside the motion gestures so a player can pick whichever feels
 * better. The host still enforces the shield budget (Standard) and authoritatively
 * decides blocks, so this control only reports intent. [active] brightens the glyph
 * while the host confirms the shield is up; a local pressed flag gives an immediate
 * touch-down highlight even before the next snapshot arrives.
 *
 * @param mode Active game mode; selects the glyph and label.
 * @param active Whether the host reports the shield/bubble as currently up.
 * @param onPressChange Invoked with true on press and false on release/cancel.
 */
@Composable
private fun ShieldHoldButton(
    mode: GameMode,
    active: Boolean,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val glyph = if (mode == GameMode.KIDS) "🫧" else "🛡️"
    val description = stringResource(
        if (mode == GameMode.KIDS) R.string.game_bubble_button_hold else R.string.game_shield_button_hold,
    )
    val highlighted = pressed || active
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = SHIELD_BUTTON_IDLE_ALPHA)
    }
    Box(
        modifier = modifier
            .size(SHIELD_BUTTON_SIZE)
            .clip(CircleShape)
            .background(containerColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPressChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onPressChange(false)
                    },
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.headlineSmall)
    }
}

// -------------------------------------------------------------------------
// Status line
// -------------------------------------------------------------------------

@Composable
private fun StatusLine(state: GameUiState.Playing) {
    val statusText = when (state.myStatus) {
        PlayerStatus.SHIELDING.name -> stringResource(R.string.game_status_shielding)
        PlayerStatus.RESTING.name -> stringResource(R.string.game_status_resting)
        PlayerStatus.ELIMINATED.name -> stringResource(R.string.game_status_eliminated)
        else -> stringResource(R.string.game_status_ready)
    }
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = SECTION_SPACING_DP),
        textAlign = TextAlign.Center,
    )
}

// -------------------------------------------------------------------------
// Round-over phase
// -------------------------------------------------------------------------

@Composable
private fun RoundOverContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.game_round_over),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
