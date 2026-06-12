package com.justb81.compassduel.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.justb81.compassduel.R
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

    var showPeerLostDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(appViewModel) {
        appViewModel.sessionEvents.collect { event ->
            when (event) {
                SessionEvent.RoundStarted -> navController.navigate(GameRoute)
                SessionEvent.MatchOver -> navController.navigate(ResultsRoute)
                SessionEvent.RematchRequested -> {
                    // Pop Game (and Results if present) back to Lobby so the
                    // same players can adjust seats/picks before the next round.
                    navController.popBackStack<GameRoute>(inclusive = true)
                }
                SessionEvent.PeerLost -> showPeerLostDialog = true
            }
        }
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

