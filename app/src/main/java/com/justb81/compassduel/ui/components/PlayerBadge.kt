package com.justb81.compassduel.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justb81.compassduel.R
import com.justb81.compassduel.game.Element
import com.justb81.compassduel.net.protocol.LobbyPlayer

private const val BADGE_LABEL_SPACING_DP = 4
private const val ELEMENT_EMOJI_FIRE = "🔥"
private const val ELEMENT_EMOJI_WATER = "💧"
private const val ELEMENT_EMOJI_EARTH = "🌿"
private const val ELEMENT_EMOJI_LIGHTNING = "⚡"
private const val SPRITE_EMOJI_0 = "⭐"
private const val SPRITE_EMOJI_1 = "🌙"
private const val SPRITE_EMOJI_2 = "☀️"
private const val SPRITE_EMOJI_3 = "☄️"
private const val SPRITE_INDEX_COMET = 3

/**
 * Reusable chip-style badge for a lobby player.
 *
 * Displays the player's name, their chosen element or sprite (if any) as an
 * emoji indicator, and a seat number when assigned. The chip is tinted in the
 * primary container colour when the player is ready.
 *
 * @param player The lobby player to display.
 * @param modifier Modifier applied to the chip.
 */
@Composable
fun PlayerBadge(
    player: LobbyPlayer,
    modifier: Modifier = Modifier,
) {
    val characterEmoji = when {
        player.element != null -> elementEmoji(player.element)
        player.spriteId != null -> spriteEmoji(player.spriteId)
        else -> null
    }

    val seatLabel = if (player.seatCell != null) {
        stringResource(R.string.player_badge_seat, player.seatCell + 1)
    } else {
        stringResource(R.string.player_badge_no_seat)
    }

    val containerColor = if (player.ready) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    AssistChip(
        onClick = {},
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (characterEmoji != null) {
                    Text(text = characterEmoji)
                    Spacer(modifier = Modifier.width(BADGE_LABEL_SPACING_DP.dp))
                }
                Text(text = player.name)
                Spacer(modifier = Modifier.width(BADGE_LABEL_SPACING_DP.dp))
                Text(
                    text = seatLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
        ),
        modifier = modifier,
    )
}

private fun elementEmoji(element: Element): String = when (element) {
    Element.FIRE -> ELEMENT_EMOJI_FIRE
    Element.WATER -> ELEMENT_EMOJI_WATER
    Element.EARTH -> ELEMENT_EMOJI_EARTH
    Element.LIGHTNING -> ELEMENT_EMOJI_LIGHTNING
}

private fun spriteEmoji(spriteId: Int): String = when (spriteId) {
    0 -> SPRITE_EMOJI_0
    1 -> SPRITE_EMOJI_1
    2 -> SPRITE_EMOJI_2
    SPRITE_INDEX_COMET -> SPRITE_EMOJI_3
    else -> SPRITE_EMOJI_0
}
