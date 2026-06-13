package com.justb81.compassduel.ui.screens.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.LobbyPlayer
import com.justb81.compassduel.session.GameSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** UI state for the lobby screen. */
data class LobbyUiState(
    /** All players currently in the lobby. */
    val players: List<LobbyPlayer> = emptyList(),
    /** Current game mode chosen by the host. */
    val mode: GameMode = GameMode.STANDARD,
    /** The local player's id (assigned by the host; 0 = not yet known). */
    val myPlayerId: Int = 0,
    /** Endpoints discovered while the client is searching (before connecting). */
    val discoveredEndpoints: List<DiscoveredEndpoint> = emptyList(),
    /** True while the client is discovering and has not yet received a LobbyState. */
    val isSearching: Boolean = false,
    /** Validation error to surface via Snackbar (host only; null = no error). */
    val startError: String? = null,
)

/**
 * ViewModel for the lobby screen.
 *
 * Derives its state from [GameSession.lobby] and [GameSession.discoveredEndpoints]
 * so that host and client see a consistent picture of the lobby.
 *
 * @param session The singleton game session facade.
 */
@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val session: GameSession,
) : ViewModel() {

    private val _startError = MutableStateFlow<String?>(null)

    /** Combined UI state observed by [LobbyScreen]. */
    val uiState: StateFlow<LobbyUiState> = combine(
        session.lobby,
        session.discoveredEndpoints,
        _startError,
    ) { lobby, endpoints, startError ->
        if (lobby != null) {
            LobbyUiState(
                players = lobby.players,
                mode = lobby.mode,
                myPlayerId = lobby.yourPlayerId,
                discoveredEndpoints = emptyList(),
                isSearching = false,
                startError = startError,
            )
        } else {
            LobbyUiState(
                discoveredEndpoints = endpoints,
                isSearching = true,
                startError = startError,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_STOP_TIMEOUT_MILLIS),
        initialValue = LobbyUiState(),
    )

    /** Changes the game mode (host only). */
    fun setMode(mode: GameMode) {
        session.setMode(mode)
    }

    /** Selects a seat cell on the 3×3 grid. */
    fun chooseSeat(cell: Int) {
        session.chooseSeat(cell)
    }

    /**
     * Picks a character for Standard mode.
     *
     * @param element The chosen element.
     */
    fun chooseElement(element: Element) {
        session.chooseCharacter(element = element, spriteId = null)
    }

    /**
     * Picks a sprite for Kids mode.
     *
     * @param spriteId The chosen sprite index (0–3).
     */
    fun chooseSprite(spriteId: Int) {
        session.chooseCharacter(element = null, spriteId = spriteId)
    }

    /**
     * Requests a connection to a discovered host (client only).
     *
     * @param endpointId The endpoint to connect to.
     */
    fun connectTo(endpointId: String) {
        session.connectTo(endpointId)
    }

    /**
     * Attempts to start the match (host only).
     *
     * Validation errors are surfaced via [LobbyUiState.startError].
     */
    fun startMatch() {
        try {
            session.startMatch()
            _startError.value = null
        } catch (e: IllegalStateException) {
            _startError.update { e.message }
        }
    }

    /** Clears the start-error message (e.g. after the Snackbar is dismissed). */
    fun clearStartError() {
        _startError.value = null
    }

    companion object {
        private const val SUBSCRIBE_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
