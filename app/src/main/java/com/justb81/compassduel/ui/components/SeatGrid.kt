package com.justb81.compassduel.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justb81.compassduel.R
import com.justb81.compassduel.net.protocol.LobbyPlayer

private const val GRID_COLUMN_COUNT = 3
private const val GRID_TOTAL_CELLS = 9
private const val CELL_CORNER_RADIUS_DP = 8
private const val CELL_BORDER_WIDTH_DP = 2
private const val CELL_PADDING_DP = 4
private const val GRID_SPACING_DP = 6
private const val OWN_SEAT_BORDER_FACTOR = 3

/**
 * A 3×3 seat-selection grid for the lobby.
 *
 * Cell index 0–8 in reading order (left-to-right, top-to-bottom). Row 0 is
 * the front of the play area; this matches
 * `Position(x = cell % 3, y = cell / 3)` in [GameSession].
 *
 * Occupied cells show the occupant's name and are disabled for other players.
 * The local player's own seat is highlighted. Tapping a free cell (or the
 * player's current seat to de-select) calls [onCellSelected].
 *
 * @param players All current lobby players.
 * @param myPlayerId The local player's id (used to identify own seat).
 * @param onCellSelected Called with the tapped cell index.
 * @param modifier Modifier applied to the grid.
 */
@Composable
fun SeatGrid(
    players: List<LobbyPlayer>,
    myPlayerId: Int,
    onCellSelected: (cell: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seatToPlayer: Map<Int, LobbyPlayer> = players
        .filter { it.seatCell != null }
        .associateBy { it.seatCell!! }

    val myCurrentSeat: Int? = players.firstOrNull { it.id == myPlayerId }?.seatCell

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMN_COUNT),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        userScrollEnabled = false,
    ) {
        items(GRID_TOTAL_CELLS) { cell ->
            SeatCell(
                cell = cell,
                occupant = seatToPlayer[cell],
                isMyCurrentSeat = cell == myCurrentSeat,
                myPlayerId = myPlayerId,
                onCellSelected = onCellSelected,
            )
        }
    }
}

@Composable
private fun SeatCell(
    cell: Int,
    occupant: LobbyPlayer?,
    isMyCurrentSeat: Boolean,
    myPlayerId: Int,
    onCellSelected: (Int) -> Unit,
) {
    val isMine = occupant?.id == myPlayerId
    val isOccupiedByOther = occupant != null && !isMine
    val isEnabled = !isOccupiedByOther

    val containerColor = when {
        isMyCurrentSeat -> MaterialTheme.colorScheme.primaryContainer
        occupant != null -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        isMyCurrentSeat -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (isMyCurrentSeat) {
        (CELL_BORDER_WIDTH_DP * OWN_SEAT_BORDER_FACTOR).dp
    } else {
        CELL_BORDER_WIDTH_DP.dp
    }

    val cellDescription = when {
        isMine -> stringResource(R.string.seat_grid_cell_mine, cell + 1)
        occupant != null -> stringResource(R.string.seat_grid_cell_occupied, cell + 1, occupant.name)
        else -> stringResource(R.string.seat_grid_cell_content_description, cell + 1)
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .aspectRatio(1f)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = MaterialTheme.shapes.small,
            )
            .then(
                if (isEnabled) {
                    Modifier.clickable { onCellSelected(cell) }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = cellDescription },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(CELL_PADDING_DP.dp),
        ) {
            val labelText = when {
                isMine -> stringResource(R.string.seat_grid_cell_mine, cell + 1)
                occupant != null -> occupant.name
                else -> (cell + 1).toString()
            }
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
