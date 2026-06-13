package com.justb81.compassduel.ui.screens.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.session.GameSession
import com.justb81.compassduel.session.SessionRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Per-player award entry for the Kids Mode results screen. */
data class KidsAwardEntry(
    val playerId: Int,
    val name: String,
    val award: KidsAward,
)

/** Per-player score entry for the Standard Mode results screen. */
data class PlayerScoreEntry(
    val playerId: Int,
    val name: String,
    val roundWins: Int,
)

/** UI state for the results screen. */
data class ResultsUiState(
    /** Game mode for this match. */
    val mode: GameMode = GameMode.STANDARD,
    /** Id of the round winner (Standard, may be null for a draw). */
    val roundWinnerId: Int? = null,
    /** Name of the round winner, if any. */
    val roundWinnerName: String? = null,
    /** Id of the match winner (Standard, if the match is over). */
    val matchWinnerId: Int? = null,
    /** Name of the match winner, if any. */
    val matchWinnerName: String? = null,
    /** Per-player round-win scores (Standard). */
    val scores: List<PlayerScoreEntry> = emptyList(),
    /** Per-player award entries (Kids). */
    val awards: List<KidsAwardEntry> = emptyList(),
    /** True when this device is the host (shows Rematch button). */
    val isHost: Boolean = false,
    /** True when awaiting the host to initiate a rematch (client). */
    val isWaitingForRematch: Boolean = false,
)

/**
 * ViewModel for the results screen.
 *
 * Derives its state from [GameSession.roundEnd] and [GameSession.lobby].
 *
 * @param session The singleton game session facade.
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val session: GameSession,
) : ViewModel() {

    /** Combined results UI state. */
    val uiState: StateFlow<ResultsUiState> = combine(
        session.roundEnd,
        session.lobby,
        session.role,
    ) { roundEnd, lobby, role ->
        if (roundEnd == null || lobby == null) return@combine ResultsUiState()

        val players = lobby.players
        val mode = lobby.mode
        val isHost = role == SessionRole.HOST

        fun nameOf(id: Int?): String? = players.firstOrNull { it.id == id }?.name

        when (mode) {
            GameMode.STANDARD -> {
                val scores = players.map { p ->
                    PlayerScoreEntry(
                        playerId = p.id,
                        name = p.name,
                        roundWins = roundEnd.matchScore[p.id] ?: 0,
                    )
                }
                ResultsUiState(
                    mode = mode,
                    roundWinnerId = roundEnd.roundWinnerId,
                    roundWinnerName = nameOf(roundEnd.roundWinnerId),
                    matchWinnerId = roundEnd.matchWinnerId,
                    matchWinnerName = nameOf(roundEnd.matchWinnerId),
                    scores = scores,
                    isHost = isHost,
                    isWaitingForRematch = !isHost,
                )
            }
            GameMode.KIDS -> {
                val awardEntries = roundEnd.kidsAwards?.mapNotNull { (id, award) ->
                    val name = nameOf(id) ?: return@mapNotNull null
                    KidsAwardEntry(playerId = id, name = name, award = award)
                } ?: emptyList()
                ResultsUiState(
                    mode = mode,
                    awards = awardEntries,
                    isHost = isHost,
                    isWaitingForRematch = !isHost,
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_STOP_TIMEOUT_MILLIS),
        initialValue = ResultsUiState(),
    )

    /** Initiates a rematch (host only). */
    fun requestRematch() {
        session.requestRematch()
    }

    /** Leaves the session and returns to Home. */
    fun leave() {
        session.leave()
    }

    companion object {
        private const val SUBSCRIBE_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
