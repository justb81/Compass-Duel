package com.justb81.compassduel.ui.navigation

import androidx.lifecycle.ViewModel
import com.justb81.compassduel.session.GameSession
import com.justb81.compassduel.session.SessionEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Top-level ViewModel that re-exposes [GameSession.sessionEvents] for the nav graph.
 *
 * Screens should not collect sessionEvents themselves; the nav graph handles all
 * navigation-level transitions in one place, avoiding duplicate handling.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val session: GameSession,
) : ViewModel() {

    /** Navigation-level events forwarded from the session. */
    val sessionEvents: SharedFlow<SessionEvent> = session.sessionEvents

    /** Leaves the current session (called after PeerLost is acknowledged). */
    fun leaveSession() {
        session.leave()
    }
}
