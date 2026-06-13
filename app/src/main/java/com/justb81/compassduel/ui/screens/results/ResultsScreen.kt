package com.justb81.compassduel.ui.screens.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justb81.compassduel.R
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.net.protocol.GameMode

private val SCREEN_PADDING_DP = 16.dp
private val SECTION_SPACING_DP = 24.dp
private val ITEM_SPACING_DP = 12.dp
private val CARD_PADDING_DP = 16.dp
private val BUTTON_SPACING_DP = 8.dp

/**
 * Results screen — shows round/match outcomes and awards.
 *
 * Standard: round winner, match score, optional match-winner banner.
 * Kids: one award card per player (loser-free wording, emoji-first).
 *
 * Host sees Rematch + Leave; client sees "Waiting…" + Leave.
 *
 * @param onNavigateHome Called when the player leaves the session; the caller
 *   pops the back stack back to [com.justb81.compassduel.ui.navigation.HomeRoute].
 * @param viewModel Injected [ResultsViewModel].
 */
@Composable
fun ResultsScreen(
    onNavigateHome: () -> Unit,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_PADDING_DP),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.results_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )

        when (uiState.mode) {
            GameMode.STANDARD -> StandardResults(uiState)
            GameMode.KIDS -> KidsResults(uiState)
        }

        Spacer(modifier = Modifier.height(ITEM_SPACING_DP))

        ActionRow(
            isHost = uiState.isHost,
            isWaiting = uiState.isWaitingForRematch,
            onRematch = viewModel::requestRematch,
            onLeave = {
                viewModel.leave()
                onNavigateHome()
            },
        )
    }
}

@Composable
private fun StandardResults(state: ResultsUiState) {
    // Round winner
    val winnerText = if (state.roundWinnerName != null) {
        stringResource(R.string.results_round_winner, state.roundWinnerName)
    } else {
        stringResource(R.string.results_round_draw)
    }
    Text(
        text = winnerText,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )

    // Match winner banner
    if (state.matchWinnerName != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.results_match_winner, state.matchWinnerName),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING_DP),
            )
        }
    }

    // Score list
    if (state.scores.isNotEmpty()) {
        Text(
            text = stringResource(R.string.results_scores_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        state.scores.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = entry.name)
                Text(
                    text = pluralStringResource(
                        R.plurals.results_wins_count,
                        entry.roundWins,
                        entry.roundWins,
                    ),
                )
            }
        }
    }
}

@Composable
private fun KidsResults(state: ResultsUiState) {
    Text(
        text = stringResource(R.string.results_kids_celebration),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )

    state.awards.forEach { entry ->
        AwardCard(entry)
    }
}

@Composable
private fun AwardCard(entry: KidsAwardEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CARD_PADDING_DP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
        ) {
            Text(
                text = awardEmoji(entry.award),
                style = MaterialTheme.typography.headlineMedium,
            )
            Column {
                Text(
                    text = awardName(entry.award),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    isHost: Boolean,
    isWaiting: Boolean,
    onRematch: () -> Unit,
    onLeave: () -> Unit,
) {
    if (isHost) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING_DP),
        ) {
            Button(
                onClick = onRematch,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.results_rematch_button))
            }
            OutlinedButton(
                onClick = onLeave,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.results_leave_button))
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP),
        ) {
            if (isWaiting) {
                Text(
                    text = stringResource(R.string.results_waiting_for_host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onLeave) {
                Text(text = stringResource(R.string.results_leave_button))
            }
        }
    }
}

private fun awardEmoji(award: KidsAward): String = when (award) {
    KidsAward.STAR_CHAMPION -> "⭐"
    KidsAward.BUBBLE_HERO -> "🫧"
    KidsAward.BUSY_BEE -> "🐝"
    KidsAward.SUPER_SPARKLER -> "✨"
}

@Composable
private fun awardName(award: KidsAward): String = when (award) {
    KidsAward.STAR_CHAMPION -> stringResource(R.string.results_award_star_champion)
    KidsAward.BUBBLE_HERO -> stringResource(R.string.results_award_bubble_hero)
    KidsAward.BUSY_BEE -> stringResource(R.string.results_award_busy_bee)
    KidsAward.SUPER_SPARKLER -> stringResource(R.string.results_award_super_sparkler)
}
