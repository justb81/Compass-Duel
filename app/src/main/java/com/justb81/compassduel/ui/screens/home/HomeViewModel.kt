package com.justb81.compassduel.ui.screens.home

import androidx.lifecycle.ViewModel
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.sensor.AimCalibrationStore
import com.justb81.compassduel.session.GameSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** UI state for the home screen. */
data class HomeUiState(
    val playerName: String = "",
    val selectedMode: GameMode = GameMode.STANDARD,
)

/**
 * ViewModel for the home screen.
 *
 * Holds the player name (in-memory only in v1) and the selected game mode, then
 * delegates to [GameSession] to start hosting or discovering a lobby.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: GameSession,
    private val calibrationStore: AimCalibrationStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())

    /** Observable UI state consumed by [HomeScreen]. */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Updates the player name as the user types. */
    fun onPlayerNameChanged(name: String) {
        _uiState.update { it.copy(playerName = name) }
    }

    /** Switches between Standard and Kids modes. */
    fun onModeSelected(mode: GameMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    /**
     * Starts advertising a lobby with the current player name and mode.
     *
     * The caller is responsible for navigating to the lobby screen after this call.
     */
    fun hostGame() {
        val state = _uiState.value
        calibrationStore.clear()
        session.hostLobby(playerName = state.playerName.trim(), mode = state.selectedMode)
    }

    /**
     * Starts discovery so the user can find a host to join.
     *
     * The caller is responsible for navigating to the lobby screen after this call.
     */
    fun joinGame() {
        val state = _uiState.value
        calibrationStore.clear()
        session.joinLobby(playerName = state.playerName.trim())
    }
}
