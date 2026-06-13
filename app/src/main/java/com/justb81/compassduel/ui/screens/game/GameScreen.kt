package com.justb81.compassduel.ui.screens.game

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import com.justb81.compassduel.ui.components.CompassRing

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

    KeepScreenOnAndImmersive()

    // Help overlay visibility is screen-local and default-collapsed; it is
    // toggled by the "?" affordance and dismissed by tapping the scrim.
    var helpVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is GameUiState.Countdown -> CountdownContent(state)
            is GameUiState.Playing -> PlayingContent(state)
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
    val descriptionRes = if (expanded) R.string.game_help_close else R.string.game_help_open
    FilledTonalIconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(descriptionRes),
        )
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
                        R.string.game_help_dodge_standard,
                    )
                    GameMode.KIDS -> listOf(
                        R.string.game_help_aim_kids,
                        R.string.game_help_toss_kids,
                        R.string.game_help_bubble_kids,
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
 * Side effect tied to the [GameScreen] lifecycle: keeps the screen awake and
 * enters immersive full-screen (status + navigation bars hidden) for the whole
 * time the game screen is shown, then reverses both on dispose.
 *
 * Disposal fires whenever the [GameRoute] composable leaves the back stack —
 * navigating to results/lobby, back navigation, or a peer-lost teardown — so
 * the screen never stays awake or full-screen indefinitely.
 */
@Composable
private fun KeepScreenOnAndImmersive() {
    val activity = LocalActivity.current ?: return

    DisposableEffect(activity) {
        val window = activity.window
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
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
private fun PlayingContent(state: GameUiState.Playing) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SCREEN_PADDING_DP),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top HUD — mode-branched
            when (state.mode) {
                GameMode.STANDARD -> StandardHud(state)
                GameMode.KIDS -> KidsHud(state)
            }

            // Compass ring — ring rotates with local sensor at full rate
            val warningColor = if (state.mode == GameMode.STANDARD) {
                Color.Red
            } else {
                WARNING_COLOR_KIDS
            }
            CompassRing(
                currentAzimuthDegrees = state.azimuthDegrees,
                targets = state.compassTargets,
                isTargeted = state.warningActive,
                warningColor = warningColor,
                highlightColor = HIGHLIGHT_COLOR,
                modifier = Modifier.weight(1f),
            )

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
        // Debug readout (debug builds only)
        if (BuildConfig.DEBUG) {
            state.debugAimDegrees?.let { aim ->
                Text(
                    text = "Az:%.1f Aim:%.1f".format(state.azimuthDegrees, aim),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        // Debug readout (debug builds only)
        if (BuildConfig.DEBUG) {
            state.debugAimDegrees?.let { aim ->
                Text(
                    text = "Az:%.1f Aim:%.1f".format(state.azimuthDegrees, aim),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
