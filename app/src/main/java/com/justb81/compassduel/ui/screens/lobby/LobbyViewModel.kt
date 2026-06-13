package com.justb81.compassduel.ui.screens.lobby

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.R
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.engine.GameClock
import com.justb81.compassduel.game.gesture.BowDetector
import com.justb81.compassduel.game.gesture.BowSample
import com.justb81.compassduel.haptics.HapticFeedback
import com.justb81.compassduel.net.TransportError
import com.justb81.compassduel.net.protocol.GameMode
import com.justb81.compassduel.net.protocol.LobbyPlayer
import com.justb81.compassduel.sensor.OrientationSample
import com.justb81.compassduel.sensor.OrientationSensor
import com.justb81.compassduel.session.GameSession
import com.justb81.compassduel.session.StartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A player the local player can greet, with reciprocal greeting status. */
data class GreetingTarget(
    val id: Int,
    val name: String,
    /** True when the local player has bowed at this opponent. */
    val iGreetedThem: Boolean,
    /** True when this opponent has bowed back at the local player. */
    val theyGreetedMe: Boolean,
)

/**
 * Live feedback for the bow currently in progress, used to drive the on-screen
 * tilt indicator and guidance while a target is armed.
 *
 * @param pitchDegrees The latest forward-tilt pitch (positive = top of phone away).
 * @param phase The bow detector's current recognition phase.
 */
data class BowFeedback(
    val pitchDegrees: Float,
    val phase: BowDetector.Phase,
)

/** UI state for the lobby screen. */
data class LobbyUiState(
    /** All players currently in the lobby. */
    val players: List<LobbyPlayer> = emptyList(),
    /** Current game mode chosen by the host. */
    val mode: GameMode = GameMode.STANDARD,
    /** The local player's id (assigned by the host; 0 = not yet known). */
    val myPlayerId: Int = 0,
    /** True while the client has committed to a host but not yet received a LobbyState. */
    val isConnecting: Boolean = false,
    /** String resource for a start-match validation error (host only; null = no error). */
    @StringRes val startErrorRes: Int? = null,
    /** String resource for a transport failure to surface via Snackbar (null = no error). */
    @StringRes val transportErrorRes: Int? = null,
    /** Opponents the local player can greet, with reciprocal greeting status. */
    val greetingTargets: List<GreetingTarget> = emptyList(),
    /** The opponent the local player is currently bowing at, or null when not armed. */
    val armedTargetId: Int? = null,
)

/**
 * ViewModel for the lobby screen.
 *
 * Derives its state from [GameSession.lobby] so that host and client see a consistent
 * picture of the lobby. Host discovery happens on the home screen, not here.
 *
 * ### Greeting handshake
 * To establish their relative positions, players bow at each opponent: the player arms a
 * target (taps their badge), points the phone at them and tilts it forward. A [BowDetector]
 * fed by [OrientationSensor] captures the absolute azimuth at bow onset and reports it via
 * [GameSession.submitBow]. There is no manual seat grid and no aim calibration.
 *
 * @param session The singleton game session facade.
 * @param orientationSensor Source of raw compass azimuth/pitch for bow detection.
 * @param clock Provides timestamps for the bow detector.
 * @param haptics Confirms bow progress (deep threshold) and capture with vibration.
 */
@HiltViewModel
class LobbyViewModel @Inject constructor(
    private val session: GameSession,
    private val orientationSensor: OrientationSensor,
    private val clock: GameClock,
    private val haptics: HapticFeedback,
) : ViewModel() {

    private val _startErrorRes = MutableStateFlow<Int?>(null)
    private val _transportErrorRes = MutableStateFlow<Int?>(null)
    private val _armedTargetId = MutableStateFlow<Int?>(null)
    private val _bowFeedback = MutableStateFlow<BowFeedback?>(null)

    /**
     * Live feedback for the in-progress bow (null when no target is armed). Exposed
     * separately from [uiState] so the high-frequency sensor stream only recomposes
     * the greeting rows, not the whole lobby.
     */
    val bowFeedback: StateFlow<BowFeedback?> = _bowFeedback.asStateFlow()

    private val bowDetector = BowDetector()

    init {
        // Surface transport failures (e.g. advertising/discovery could not start) as a
        // user-facing message instead of letting the underlying call crash the app.
        viewModelScope.launch {
            session.transportErrors.collect { error ->
                _transportErrorRes.value = error.toMessageRes()
            }
        }
        // Drive the bow detector from the orientation stream while a target is armed.
        viewModelScope.launch {
            orientationSensor.samples().collect { sample ->
                val target = _armedTargetId.value ?: return@collect
                onBowSample(target, sample)
            }
        }
    }

    /**
     * Feeds one orientation sample into the bow detector while [targetId] is armed,
     * publishing live [bowFeedback] and firing haptics on the deep-threshold
     * transition and on a completed bow.
     */
    private fun onBowSample(targetId: Int, sample: OrientationSample) {
        val wasAscending = bowDetector.phase == BowDetector.Phase.ASCENDING
        val captured = bowDetector.onSample(
            BowSample(
                timestampMillis = clock.nowMillis(),
                pitchDegrees = sample.pitchDegrees,
                azimuthDegrees = sample.azimuthDegrees,
            ),
        )
        if (!wasAscending && bowDetector.phase == BowDetector.Phase.ASCENDING) {
            haptics.greetingDeep()
        }
        if (captured != null) {
            haptics.greetingBowed()
            session.submitBow(targetId, captured)
            cancelBow()
        } else {
            _bowFeedback.value = BowFeedback(sample.pitchDegrees, bowDetector.phase)
        }
    }

    /** Combined UI state observed by [LobbyScreen]. */
    val uiState: StateFlow<LobbyUiState> = combine(
        session.lobby,
        _startErrorRes,
        _transportErrorRes,
        _armedTargetId,
    ) { lobby, startErrorRes, transportErrorRes, armedTargetId ->
        if (lobby != null) {
            LobbyUiState(
                players = lobby.players,
                mode = lobby.mode,
                myPlayerId = lobby.yourPlayerId,
                isConnecting = false,
                startErrorRes = startErrorRes,
                transportErrorRes = transportErrorRes,
                greetingTargets = buildGreetingTargets(lobby.players, lobby.yourPlayerId),
                armedTargetId = armedTargetId,
            )
        } else {
            LobbyUiState(
                isConnecting = true,
                startErrorRes = startErrorRes,
                transportErrorRes = transportErrorRes,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_STOP_TIMEOUT_MILLIS),
        initialValue = LobbyUiState(),
    )

    private fun buildGreetingTargets(players: List<LobbyPlayer>, myId: Int): List<GreetingTarget> {
        val me = players.firstOrNull { it.id == myId }
        return players.filter { it.id != myId }.map { other ->
            GreetingTarget(
                id = other.id,
                name = other.name,
                iGreetedThem = me?.outgoingBearings?.containsKey(other.id) == true,
                theyGreetedMe = other.outgoingBearings.containsKey(myId),
            )
        }
    }

    /** Arms the bow detector to capture a bow at [targetId]. */
    fun armBow(targetId: Int) {
        bowDetector.reset()
        _bowFeedback.value = null
        _armedTargetId.value = targetId
    }

    /** Cancels an in-progress bow capture. */
    fun cancelBow() {
        _armedTargetId.value = null
        _bowFeedback.value = null
        bowDetector.reset()
    }

    /** Changes the game mode (host only). */
    fun setMode(mode: GameMode) {
        session.setMode(mode)
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
     * Attempts to start the match (host only).
     *
     * Validation failures are surfaced via [LobbyUiState.startErrorRes].
     */
    fun startMatch() {
        _startErrorRes.value = when (session.startMatch()) {
            StartResult.Success -> null
            StartResult.NoLobby -> R.string.lobby_start_error_no_lobby
            StartResult.TooFewPlayers -> R.string.lobby_start_error_too_few
            StartResult.TooManyPlayers -> R.string.lobby_start_error_too_many
            StartResult.NotAllGreeted -> R.string.lobby_start_error_not_greeted
            StartResult.MissingElements -> R.string.lobby_start_error_missing_elements
            StartResult.DuplicateElements -> R.string.lobby_start_error_duplicate_elements
        }
    }

    /** Clears the start-error message (e.g. after the Snackbar is dismissed). */
    fun clearStartError() {
        _startErrorRes.value = null
    }

    /** Clears the transport-error message (e.g. after the Snackbar is shown). */
    fun clearTransportError() {
        _transportErrorRes.value = null
    }

    @StringRes
    private fun TransportError.toMessageRes(): Int = when (this) {
        TransportError.ADVERTISE -> R.string.lobby_error_host_failed
        TransportError.DISCOVER -> R.string.lobby_error_discovery_failed
        TransportError.CONNECT -> R.string.lobby_error_connect_failed
    }

    companion object {
        private const val SUBSCRIBE_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
