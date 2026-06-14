package com.justb81.compassduel.game.standard

/**
 * Tracks round wins across a best-of-[roundsToWin × 2 − 1] match.
 *
 * This is a pure value object: [recordRoundWin] returns a new instance rather
 * than mutating the existing one, keeping the engine stateless between ticks.
 *
 * @param roundWins Map of player id → number of rounds won so far.
 * @param roundsToWin Rounds a player must win to take the match. Defaults to
 *   [StandardRules.ROUNDS_TO_WIN] (best-of-3); the host can configure the series length
 *   in the lobby (best of 1 → 1, best of 3 → 2, best of 5 → 3).
 */
data class MatchScore(
    val roundWins: Map<Int, Int> = emptyMap(),
    val roundsToWin: Int = StandardRules.ROUNDS_TO_WIN,
) {

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
     * Returns the id of the player who has reached [roundsToWin] round wins, or null when the
     * match is still ongoing.
     *
     * When multiple players simultaneously reach the threshold the player with the
     * highest win count wins; ties on win count are broken by the lowest player id,
     * making the result fully deterministic regardless of map iteration order.
     */
    fun matchWinnerId(): Int? {
        val candidates = roundWins.entries.filter { (_, wins) -> wins >= roundsToWin }
        if (candidates.isEmpty()) return null
        val maxWins = candidates.maxOf { it.value }
        return candidates
            .filter { it.value == maxWins }
            .minOf { it.key }
    }
}
