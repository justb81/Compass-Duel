package com.justb81.compassduel.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justb81.compassduel.R
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.ui.permissions.NearbyPermissionsGate

private val SCREEN_PADDING_DP = 24.dp
private val ITEM_SPACING_DP = 16.dp
private val SECTION_SPACING_DP = 32.dp
private val BUTTON_SPACING_DP = 12.dp

/** Emoji mode markers; material-icons-extended is intentionally not a dependency. */
private const val MODE_ICON_STANDARD = "⚔️"
private const val MODE_ICON_KIDS = "🌟"

/**
 * Home screen: enter player name, select game mode, then host or join a game.
 *
 * All action buttons are gated behind [NearbyPermissionsGate] so the user is
 * prompted for Bluetooth/Wi-Fi permissions before any Nearby API call is made.
 *
 * @param onNavigateToLobby Called with `isHost = true` when the user hosts a game,
 *   or `isHost = false` when the user joins one.
 * @param viewModel Injected [HomeViewModel].
 */
@Composable
fun HomeScreen(
    onNavigateToLobby: (isHost: Boolean) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SCREEN_PADDING_DP),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP))

        OutlinedTextField(
            value = uiState.playerName,
            onValueChange = viewModel::onPlayerNameChanged,
            label = { Text(text = stringResource(R.string.home_player_name_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(ITEM_SPACING_DP))

        ModeSelector(
            selectedMode = uiState.selectedMode,
            onModeSelected = viewModel::onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP))

        NearbyPermissionsGate {
            val nameIsBlank = uiState.playerName.isBlank()
            GameButtons(
                nameIsBlank = nameIsBlank,
                onHostGame = {
                    viewModel.hostGame()
                    onNavigateToLobby(true)
                },
                onJoinGame = {
                    viewModel.joinGame()
                    onNavigateToLobby(false)
                },
            )
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: GameMode,
    onModeSelected: (GameMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = selectedMode == GameMode.STANDARD,
            onClick = { onModeSelected(GameMode.STANDARD) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Text(text = MODE_ICON_STANDARD) },
        ) {
            Text(text = stringResource(R.string.home_mode_standard))
        }
        SegmentedButton(
            selected = selectedMode == GameMode.KIDS,
            onClick = { onModeSelected(GameMode.KIDS) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Text(text = MODE_ICON_KIDS) },
        ) {
            Text(text = stringResource(R.string.home_mode_kids))
        }
    }
}

@Composable
private fun GameButtons(
    nameIsBlank: Boolean,
    onHostGame: () -> Unit,
    onJoinGame: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onHostGame,
            enabled = !nameIsBlank,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.home_host_game))
        }
        Spacer(modifier = Modifier.width(BUTTON_SPACING_DP))
        OutlinedButton(
            onClick = onJoinGame,
            enabled = !nameIsBlank,
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.home_join_game))
        }
    }
}
