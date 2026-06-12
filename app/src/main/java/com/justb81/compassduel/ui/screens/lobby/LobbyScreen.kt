package com.justb81.compassduel.ui.screens.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justb81.compassduel.R
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.ui.components.PlayerBadge
import com.justb81.compassduel.ui.components.SeatGrid

private val SCREEN_PADDING_DP = 16.dp
private val SECTION_SPACING_DP = 24.dp
private val ITEM_SPACING_DP = 12.dp
private val BADGE_SPACING_DP = 8.dp
private val ELEMENT_BUTTON_HEIGHT_DP = 56.dp
private val KIDS_BUTTON_HEIGHT_DP = 72.dp
private val PROGRESS_INDICATOR_SIZE_DP = 24.dp

// Pastel colours for Kids Mode character buttons
private val PASTEL_STAR = Color(0xFFFFEB3B)
private val PASTEL_MOON = Color(0xFFB39DDB)
private val PASTEL_SUN = Color(0xFFFF8A65)
private val PASTEL_COMET = Color(0xFF80DEEA)

private val SPRITE_EMOJIS = listOf("⭐", "🌙", "☀️", "☄️")
private val SPRITE_PASTEL_COLORS = listOf(PASTEL_STAR, PASTEL_MOON, PASTEL_SUN, PASTEL_COMET)
private const val SPRITE_COUNT = 4

/**
 * Lobby screen: seat selection, character/sprite picking and (host only) game start.
 *
 * ### Host flow
 * Shows the player list, mode segmented button, seat grid, and element/sprite
 * picker. The Start button is enabled only when the session's own start-match
 * validation would pass (2–4 players, all seated with unique cells, all picked).
 *
 * ### Client flow
 * Before connecting: shows the list of discovered host endpoints with Connect
 * buttons and a "Searching…" indicator.
 * After [LobbyUiState.isSearching] becomes false (LobbyState received): shows
 * the same seat/pick UI as the host and a "Waiting for host to start" label.
 *
 * Kids Mode variant uses oversized touch targets and pastel sprite buttons per
 * the kids-mode-spec UI guidelines.
 *
 * @param isHost True when this device is the lobby host.
 * @param viewModel Injected [LobbyViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    isHost: Boolean,
    viewModel: LobbyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.startError) {
        val error = uiState.startError
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearStartError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isHost) {
                            stringResource(R.string.lobby_title_host)
                        } else {
                            stringResource(R.string.lobby_title_client)
                        },
                    )
                },
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
    ) { innerPadding ->
        when {
            !isHost && uiState.isSearching -> {
                ClientDiscoveryContent(
                    endpoints = uiState.discoveredEndpoints,
                    onConnectTo = viewModel::connectTo,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(SCREEN_PADDING_DP),
                )
            }
            else -> {
                LobbyContent(
                    uiState = uiState,
                    isHost = isHost,
                    onSetMode = viewModel::setMode,
                    onChooseSeat = viewModel::chooseSeat,
                    onChooseElement = viewModel::chooseElement,
                    onChooseSprite = viewModel::chooseSprite,
                    onStartMatch = viewModel::startMatch,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun ClientDiscoveryContent(
    endpoints: List<DiscoveredEndpoint>,
    onConnectTo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(PROGRESS_INDICATOR_SIZE_DP))
        Text(
            text = stringResource(R.string.lobby_searching),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (endpoints.isEmpty()) {
            Text(
                text = stringResource(R.string.lobby_no_hosts_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            endpoints.forEach { endpoint ->
                DiscoveredHostRow(endpoint = endpoint, onConnect = { onConnectTo(endpoint.endpointId) })
            }
        }
    }
}

@Composable
private fun DiscoveredHostRow(
    endpoint: DiscoveredEndpoint,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onConnect,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SCREEN_PADDING_DP, vertical = ITEM_SPACING_DP),
    ) {
        Text(text = endpoint.name)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = stringResource(R.string.lobby_connect_button))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LobbyContent(
    uiState: LobbyUiState,
    isHost: Boolean,
    onSetMode: (GameMode) -> Unit,
    onChooseSeat: (Int) -> Unit,
    onChooseElement: (Element) -> Unit,
    onChooseSprite: (Int) -> Unit,
    onStartMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val me = uiState.players.firstOrNull { it.id == uiState.myPlayerId }
    val takenElements: Set<Element> = uiState.players
        .filter { it.id != uiState.myPlayerId }
        .mapNotNull { it.element }
        .toSet()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_PADDING_DP),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP),
    ) {
        // ---- Players list ----
        SectionHeader(text = stringResource(R.string.lobby_players_header))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(BADGE_SPACING_DP),
            verticalArrangement = Arrangement.spacedBy(BADGE_SPACING_DP),
        ) {
            uiState.players.forEach { player ->
                PlayerBadge(player = player)
            }
        }

        // ---- Mode selector (host only) ----
        if (isHost) {
            SectionHeader(text = stringResource(R.string.lobby_mode_label))
            ModeSelectorRow(
                selectedMode = uiState.mode,
                onModeSelected = onSetMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Seat grid ----
        SectionHeader(text = stringResource(R.string.lobby_seat_header))
        SeatGrid(
            players = uiState.players,
            myPlayerId = uiState.myPlayerId,
            onCellSelected = onChooseSeat,
        )

        // ---- Character picker ----
        SectionHeader(text = stringResource(R.string.lobby_character_header))
        when (uiState.mode) {
            GameMode.STANDARD -> {
                ElementPicker(
                    myElement = me?.element,
                    takenElements = takenElements,
                    onChooseElement = onChooseElement,
                )
            }
            GameMode.KIDS -> {
                SpritePicker(
                    mySprite = me?.spriteId,
                    onChooseSprite = onChooseSprite,
                )
            }
        }

        // ---- Start / wait ----
        Spacer(modifier = Modifier.height(ITEM_SPACING_DP))
        LobbyActionRow(
            isHost = isHost,
            canStart = canStartMatch(uiState),
            onStartMatch = onStartMatch,
        )
    }
}

@Composable
private fun LobbyActionRow(
    isHost: Boolean,
    canStart: Boolean,
    onStartMatch: () -> Unit,
) {
    if (isHost) {
        Button(
            onClick = onStartMatch,
            enabled = canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.lobby_start_button))
        }
    } else {
        Text(
            text = stringResource(R.string.lobby_waiting_for_host),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ModeSelectorRow(
    selectedMode: GameMode,
    onModeSelected: (GameMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = selectedMode == GameMode.STANDARD,
            onClick = { onModeSelected(GameMode.STANDARD) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = { Icon(imageVector = Icons.Filled.Shield, contentDescription = null) },
        ) {
            Text(text = stringResource(R.string.home_mode_standard))
        }
        SegmentedButton(
            selected = selectedMode == GameMode.KIDS,
            onClick = { onModeSelected(GameMode.KIDS) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = { Icon(imageVector = Icons.Filled.ChildCare, contentDescription = null) },
        ) {
            Text(text = stringResource(R.string.home_mode_kids))
        }
    }
}

@Composable
private fun ElementPicker(
    myElement: Element?,
    takenElements: Set<Element>,
    onChooseElement: (Element) -> Unit,
    modifier: Modifier = Modifier,
) {
    val elements = Element.entries
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
    ) {
        elements.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
            ) {
                row.forEach { element ->
                    val isTaken = element in takenElements
                    val isSelected = element == myElement
                    ElementButton(
                        element = element,
                        isSelected = isSelected,
                        isDisabled = isTaken,
                        onClick = { onChooseElement(element) },
                        modifier = Modifier
                            .weight(1f)
                            .height(ELEMENT_BUTTON_HEIGHT_DP),
                    )
                }
                // Pad the last row if it has only one element
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ElementButton(
    element: Element,
    isSelected: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelRes = when (element) {
        Element.FIRE -> R.string.element_fire
        Element.WATER -> R.string.element_water
        Element.EARTH -> R.string.element_earth
        Element.LIGHTNING -> R.string.element_lightning
    }
    if (isSelected) {
        Button(
            onClick = onClick,
            enabled = !isDisabled,
            modifier = modifier,
        ) {
            Text(text = elementEmoji(element) + " " + stringResource(labelRes))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !isDisabled,
            modifier = modifier,
        ) {
            Text(text = elementEmoji(element) + " " + stringResource(labelRes))
        }
    }
}

/** Kids Mode sprite picker with oversized pastel buttons (icon-first). */
@Composable
private fun SpritePicker(
    mySprite: Int?,
    onChooseSprite: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
    ) {
        (0 until SPRITE_COUNT).chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
            ) {
                row.forEach { spriteId ->
                    SpriteButton(
                        spriteId = spriteId,
                        isSelected = spriteId == mySprite,
                        onClick = { onChooseSprite(spriteId) },
                        modifier = Modifier
                            .weight(1f)
                            .height(KIDS_BUTTON_HEIGHT_DP),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SpriteButton(
    spriteId: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pastelColor = SPRITE_PASTEL_COLORS[spriteId]
    val emoji = SPRITE_EMOJIS[spriteId]
    val spriteNameRes = when (spriteId) {
        0 -> R.string.sprite_0
        1 -> R.string.sprite_1
        2 -> R.string.sprite_2
        else -> R.string.sprite_3
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                pastelColor
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = emoji, style = MaterialTheme.typography.headlineSmall)
            Text(text = stringResource(spriteNameRes), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Returns true when the current lobby state satisfies the host-side
 * startMatch validation: 2–4 players, all seated on unique cells, all picked.
 */
private fun canStartMatch(uiState: LobbyUiState): Boolean {
    val players = uiState.players
    if (players.size !in 2..4) return false
    if (players.any { it.seatCell == null }) return false
    val seats = players.mapNotNull { it.seatCell }
    if (seats.size != seats.toSet().size) return false
    if (uiState.mode == GameMode.STANDARD) {
        if (players.any { it.element == null }) return false
        val elements = players.mapNotNull { it.element }
        if (elements.size != elements.toSet().size) return false
    } else {
        if (players.any { it.spriteId == null }) return false
    }
    return true
}

private fun elementEmoji(element: Element): String = when (element) {
    Element.FIRE -> "🔥"
    Element.WATER -> "💧"
    Element.EARTH -> "🌿"
    Element.LIGHTNING -> "⚡"
}
