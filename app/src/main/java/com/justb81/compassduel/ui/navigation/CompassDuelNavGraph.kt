package com.justb81.compassduel.ui.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.justb81.compassduel.R
import com.justb81.compassduel.crash.CrashReporter
import com.justb81.compassduel.session.SessionEvent
import com.justb81.compassduel.ui.screens.game.GameScreen
import com.justb81.compassduel.ui.screens.home.HomeScreen
import com.justb81.compassduel.ui.screens.lobby.LobbyScreen
import com.justb81.compassduel.ui.screens.results.ResultsScreen
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Type-safe route objects
// ---------------------------------------------------------------------------

/** Home screen: player name entry and mode selection. */
@Serializable
data object HomeRoute

/** Lobby screen: seat grid, character selection, and game start. */
@Serializable
data class LobbyRoute(val isHost: Boolean)

/** Game screen: active combat / star-catching phase. */
@Serializable
data object GameRoute

/** Results screen: round and match results, rematch / leave actions. */
@Serializable
data object ResultsRoute

// ---------------------------------------------------------------------------
// NavHost
// ---------------------------------------------------------------------------

/**
 * Root navigation host for Compass Duel.
 *
 * Navigation is driven by two sources:
 * 1. Direct user actions (e.g., pressing "Host" or "Join" on the home screen).
 * 2. [SessionEvent]s collected from [AppViewModel.sessionEvents]:
 *    - [SessionEvent.RoundStarted] → [GameRoute]
 *    - [SessionEvent.MatchOver] → [ResultsRoute]
 *    - [SessionEvent.RematchRequested] → pop back to [LobbyRoute]
 *    - [SessionEvent.PeerLost] → show a dialog, then leave the session and return to [HomeRoute]
 *
 * @param navController The [NavHostController] managing back-stack state.
 */
@Composable
fun CompassDuelNavGraph(navController: NavHostController) {
    val appViewModel: AppViewModel = hiltViewModel()

    val context = LocalContext.current
    // Read (and clear) the previous crash once per composition; shown in a dialog
    // so testers can copy the stack trace even without a debugger attached.
    var lastCrash by rememberSaveable { mutableStateOf(CrashReporter.consumeLastCrash(context)) }

    var showPeerLostDialog by rememberSaveable { mutableStateOf(false) }
    var showReconnecting by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(appViewModel) {
        appViewModel.sessionEvents.collect { event ->
            when (event) {
                SessionEvent.RoundStarted -> {
                    // launchSingleTop reuses an existing GameRoute entry rather than
                    // pushing a duplicate — per-round RoundStarted events will not
                    // accumulate extra back-stack entries (#67).
                    // popUpTo(GameRoute) inclusive ensures any stale GameRoute
                    // beneath is replaced, keeping the stack tidy in best-of-3
                    // scenarios.
                    navController.navigate(GameRoute) {
                        popUpTo<GameRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                SessionEvent.MatchOver -> {
                    // Pop any existing ResultsRoute before navigating so that a
                    // replayed event (replay=1, #63) doesn't push a second copy.
                    navController.navigate(ResultsRoute) {
                        popUpTo<ResultsRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                SessionEvent.RematchRequested -> {
                    // Pop Game (and Results if present) back to Lobby so the
                    // same players can adjust picks/greetings before the next round.
                    navController.popBackStack<GameRoute>(inclusive = true)
                }
                SessionEvent.RegreetRequired -> {
                    // A player left their seat mid-match; everyone re-greets in the
                    // lobby before the host resumes. Match score is preserved.
                    navController.popBackStack<GameRoute>(inclusive = true)
                }
                SessionEvent.PeerReconnecting -> showReconnecting = true
                SessionEvent.PeerReconnected -> showReconnecting = false
                SessionEvent.PeerLost -> {
                    showReconnecting = false
                    showPeerLostDialog = true
                }
            }
        }
    }

    lastCrash?.let { crashText ->
        CrashReportDialog(
            details = crashText,
            onDismiss = { lastCrash = null },
        )
    }

    if (showReconnecting && !showPeerLostDialog) {
        ReconnectingDialog()
    }

    if (showPeerLostDialog) {
        PeerLostDialog(
            onDismiss = {
                showPeerLostDialog = false
                appViewModel.leaveSession()
                navController.popBackStack(HomeRoute, inclusive = false)
            },
        )
    }

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onNavigateToLobby = { isHost ->
                    navController.navigate(LobbyRoute(isHost = isHost))
                },
            )
        }
        composable<LobbyRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<LobbyRoute>()
            LobbyScreen(isHost = route.isHost)
        }
        composable<GameRoute> {
            GameScreen()
        }
        composable<ResultsRoute> {
            ResultsScreen(
                onNavigateHome = {
                    navController.popBackStack(HomeRoute, inclusive = false)
                },
            )
        }
    }
}

@Composable
private fun CrashReportDialog(details: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.crash_dialog_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = stringResource(R.string.crash_dialog_message) + "\n\n" + details,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = CRASH_DIALOG_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(details))
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.crash_dialog_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.crash_dialog_dismiss))
            }
        },
    )
}

private const val CRASH_DIALOG_MAX_HEIGHT_DP = 320

@Composable
private fun ReconnectingDialog() {
    // Non-dismissible: the empty onDismissRequest/confirmButton keep the overlay up until the
    // session resolves the reconnect (PeerReconnected → hide, PeerLost → terminal dialog).
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator() },
        title = { Text(text = stringResource(R.string.reconnecting_title)) },
        text = { Text(text = stringResource(R.string.reconnecting_message)) },
        confirmButton = {},
    )
}

@Composable
private fun PeerLostDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.peer_lost_title)) },
        text = { Text(text = stringResource(R.string.peer_lost_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.peer_lost_ok))
            }
        },
    )
}
