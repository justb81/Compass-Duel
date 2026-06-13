package com.justb81.compassduel.game.standard

/**
 * Tracks round wins across a best-of-[StandardRules.ROUNDS_TO_WIN × 2 − 1] match.
 *
 * This is a pure value object: [recordRoundWin] returns a new instance rather
 * than mutating the existing one, keeping the engine stateless between ticks.
 *
 * @param roundWins Map of player id → number of rounds won so far.
 */
data class MatchScore(val roundWins: Map<Int, Int> = emptyMap()) {

    /**
     * Returns a new [MatchScore] that credits one round win to [playerId].
     * A null [playerId] (draw) leaves the score unchanged.
     */
    fun recordRoundWin(playerId: Int?): MatchScore {
        if (playerId == null) return this
        val updated = roundWins.toMutableMap()
        updated[playerId] = (updated[playerId] ?: 0) + 1
        return copy(roundWins = updated)
    }

    /**
     * Returns the id of the player who has reached [StandardRules.ROUNDS_TO_WIN]
     * round wins, or null when the match is still ongoing.
     */
    fun matchWinnerId(): Int? =
        roundWins.entries.firstOrNull { (_, wins) -> wins >= StandardRules.ROUNDS_TO_WIN }?.key
}
