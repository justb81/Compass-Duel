package com.justb81.compassduel.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.data.preferences.UserPreferencesRepository
import com.justb81.compassduel.net.DiscoveredEndpoint
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.session.GameSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the home screen. */
data class HomeUiState(
    val playerName: String = "",
    val selectedMode: GameMode = GameMode.STANDARD,
)

/**
 * ViewModel for the home screen.
 *
 * Holds the player name and the selected game mode. The last-used name is restored from
 * [UserPreferencesRepository] on init and re-persisted whenever the user hosts or joins. The home
 * screen doubles as the discovery entry point: it browses for nearby hosts via
 * [GameSession.startBrowsing] and lets the user either join one of them or create (host) their own.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: GameSession,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())

    init {
        viewModelScope.launch {
            val saved = prefs.playerName.first()
            // Don't clobber a name the user typed before this async read returned.
            _uiState.update { if (it.playerName.isEmpty()) it.copy(playerName = saved) else it }
        }
    }

    /** Observable UI state consumed by [HomeScreen]. */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Nearby hosts discovered while the home screen is browsing. */
    val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = session.discoveredEndpoints

    /** Updates the player name as the user types. */
    fun onPlayerNameChanged(name: String) {
        _uiState.update { it.copy(playerName = name) }
    }

    /** Switches between Standard and Kids modes. */
    fun onModeSelected(mode: GameMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    /** Starts discovering nearby hosts (call once permissions are granted). */
    fun startBrowsing() = session.startBrowsing()

    /** Stops discovering nearby hosts (call when leaving the home screen). */
    fun stopBrowsing() = session.stopBrowsing()

    /**
     * Starts advertising a lobby with the current (trimmed) player name and mode.
     *
     * @return true when hosting started; false when the name is blank (caller must not navigate).
     */
    fun hostGame(): Boolean {
        val name = _uiState.value.playerName.trim()
        if (name.isBlank()) return false
        viewModelScope.launch { prefs.setPlayerName(name) }
        session.hostLobby(playerName = name, mode = _uiState.value.selectedMode)
        return true
    }

    /**
     * Joins the given discovered host with the current (trimmed) player name.
     *
     * @return true when the join started; false when the name is blank (caller must not navigate).
     */
    fun joinGame(endpointId: String): Boolean {
        val name = _uiState.value.playerName.trim()
        if (name.isBlank()) return false
        viewModelScope.launch { prefs.setPlayerName(name) }
        session.joinLobby(playerName = name, endpointId = endpointId)
        return true
    }
}
