package com.justb81.compassduel.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.justb81.compassduel.data.preferences.ThemePreference
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.ui.permissions.NearbyPermissionsGate
import com.justb81.compassduel.ui.settings.SettingsViewModel

private val SCREEN_PADDING_DP = 24.dp
private val ITEM_SPACING_DP = 16.dp
private val SECTION_SPACING_DP = 32.dp
private val ROW_SPACING_DP = 12.dp
private val PROGRESS_INDICATOR_SIZE_DP = 24.dp

/** Emoji mode markers; material-icons-extended is intentionally not a dependency. */
private const val MODE_ICON_STANDARD = "⚔️"
private const val MODE_ICON_KIDS = "🌟"

/** Maximum players per lobby, shown as the denominator in the discovery subtitle ("X/4"). */
private const val MAX_PLAYERS = 4

/**
 * Home screen: the combined entry point. Enter a name and (for hosting) pick a mode, then
 * either join one of the nearby games discovered on launch or create your own.
 *
 * Discovery runs only inside [NearbyPermissionsGate] — it starts when the gate's content
 * enters composition (permissions granted) and stops when it leaves, so the Nearby API is
 * never touched before the user has granted Bluetooth access.
 *
 * @param onNavigateToLobby Called with `isHost = true` when the user creates a game,
 *   or `isHost = false` when the user joins one.
 * @param viewModel Injected [HomeViewModel].
 * @param settingsViewModel Injected [SettingsViewModel] backing the theme selector.
 */
@Composable
fun HomeScreen(
    onNavigateToLobby: (isHost: Boolean) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val discoveredEndpoints by viewModel.discoveredEndpoints.collectAsStateWithLifecycle()
    val themePreference by settingsViewModel.themePreference.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_PADDING_DP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(ITEM_SPACING_DP))

        ThemeSelector(
            selected = themePreference,
            onSelected = settingsViewModel::onThemeSelected,
            modifier = Modifier.fillMaxWidth(),
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
            DisposableEffect(Unit) {
                viewModel.startBrowsing()
                onDispose { viewModel.stopBrowsing() }
            }
            val nameIsBlank = uiState.playerName.isBlank()
            NearbyGamesSection(
                endpoints = discoveredEndpoints,
                nameIsBlank = nameIsBlank,
                onJoin = { endpointId ->
                    if (viewModel.joinGame(endpointId)) onNavigateToLobby(false)
                },
            )
            Spacer(modifier = Modifier.height(SECTION_SPACING_DP))
            Button(
                onClick = { if (viewModel.hostGame()) onNavigateToLobby(true) },
                enabled = !nameIsBlank,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.home_create_game))
            }
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

/** Three-way app theme picker (System / Light / Dark), persisted via [SettingsViewModel]. */
@Composable
private fun ThemeSelector(
    selected: ThemePreference,
    onSelected: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = ThemePreference.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(text = stringResource(option.labelRes()))
            }
        }
    }
}

@StringRes
private fun ThemePreference.labelRes(): Int = when (this) {
    ThemePreference.SYSTEM -> R.string.home_theme_system
    ThemePreference.LIGHT -> R.string.home_theme_light
    ThemePreference.DARK -> R.string.home_theme_dark
}

/**
 * Live list of nearby games discovered on launch. Shows a searching indicator until the
 * first host appears, then a Join row per host. Join is disabled while the name is blank.
 */
@Composable
private fun NearbyGamesSection(
    endpoints: List<DiscoveredEndpoint>,
    nameIsBlank: Boolean,
    onJoin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP),
    ) {
        Text(
            text = stringResource(R.string.home_nearby_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (endpoints.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ROW_SPACING_DP),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_INDICATOR_SIZE_DP))
                Text(
                    text = stringResource(R.string.home_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.home_no_games_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            endpoints.forEach { endpoint ->
                NearbyGameRow(
                    endpoint = endpoint,
                    enabled = !nameIsBlank,
                    onJoin = { onJoin(endpoint.endpointId) },
                )
            }
        }
    }
}

/**
 * A single discovered host. Shows the mode icon, the host name, and — when the advertisement
 * carries it — a "<mode> · X/4 players" subtitle (#98), with a Join action on the trailing edge.
 */
@Composable
private fun NearbyGameRow(
    endpoint: DiscoveredEndpoint,
    enabled: Boolean,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onJoin,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ITEM_SPACING_DP, vertical = ROW_SPACING_DP),
    ) {
        Text(
            text = endpoint.mode?.let { modeIcon(it) } ?: MODE_ICON_STANDARD,
            modifier = Modifier.padding(end = ROW_SPACING_DP),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically),
        ) {
            Text(
                text = endpoint.name,
                style = MaterialTheme.typography.titleMedium,
            )
            endpoint.mode?.let { mode ->
                Text(
                    text = stringResource(
                        R.string.home_nearby_game_subtitle,
                        stringResource(modeLabelRes(mode)),
                        endpoint.playerCount,
                        MAX_PLAYERS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(text = stringResource(R.string.home_join_button))
    }
}

/** Emoji marker for [mode], reused from the mode selector. */
private fun modeIcon(mode: GameMode): String = when (mode) {
    GameMode.STANDARD -> MODE_ICON_STANDARD
    GameMode.KIDS -> MODE_ICON_KIDS
}

@StringRes
private fun modeLabelRes(mode: GameMode): Int = when (mode) {
    GameMode.STANDARD -> R.string.home_mode_standard
    GameMode.KIDS -> R.string.home_mode_kids
}
