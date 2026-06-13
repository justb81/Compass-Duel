package com.justb81.compassduel.game.standard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MatchScoreTest {

    // ---------------------------------------------------------------------------
    // recordRoundWin
    // ---------------------------------------------------------------------------

    @Test
    fun `recording a win for a new player adds an entry`() {
        val score = MatchScore().recordRoundWin(playerId = 1)
        assertEquals(1, score.roundWins[1])
    }

    @Test
    fun `recording a null win (draw) leaves the score unchanged`() {
        val score = MatchScore(roundWins = mapOf(1 to 1)).recordRoundWin(playerId = null)
        assertEquals(mapOf(1 to 1), score.roundWins)
    }

    @Test
    fun `accumulates wins for multiple players independently`() {
        val score = MatchScore()
            .recordRoundWin(1)
            .recordRoundWin(2)
            .recordRoundWin(1)
        assertEquals(2, score.roundWins[1])
        assertEquals(1, score.roundWins[2])
    }

    // ---------------------------------------------------------------------------
    // matchWinnerId — best-of-3
    // ---------------------------------------------------------------------------

    @Test
    fun `no match winner before anyone reaches ROUNDS_TO_WIN`() {
        val score = MatchScore().recordRoundWin(1)
        assertNull(score.matchWinnerId())
    }

    @Test
    fun `player 1 wins match after two consecutive round wins (2-0)`() {
        val score = MatchScore()
            .recordRoundWin(1)
            .recordRoundWin(1)
        assertEquals(1, score.matchWinnerId())
    }

    @Test
    fun `player 2 wins match 2-1 after both trade a round`() {
        val score = MatchScore()
            .recordRoundWin(1)
            .recordRoundWin(2)
            .recordRoundWin(2)
        assertEquals(2, score.matchWinnerId())
    }

    @Test
    fun `no winner when each player has one round win (1-1 state)`() {
        val score = MatchScore()
            .recordRoundWin(1)
            .recordRoundWin(2)
        assertNull(score.matchWinnerId())
    }

    @Test
    fun `score is immutable — recording a win does not change the original`() {
        val original = MatchScore()
        val updated = original.recordRoundWin(1)
        assertNull(original.roundWins[1])
        assertEquals(1, updated.roundWins[1])
    }

    // ---------------------------------------------------------------------------
    // matchWinnerId — deterministic with >2 players (Issue #55)
    // ---------------------------------------------------------------------------

    @Test
    fun `with three players two reaching threshold returns the one with more wins`() {
        // Player 1 has 3 wins, player 2 has 2 wins (both >= ROUNDS_TO_WIN = 2).
        // Player 1 should win because they have the higher count.
        val score = MatchScore(roundWins = mapOf(1 to 3, 2 to 2, 3 to 0))
        assertEquals(1, score.matchWinnerId())
    }

    @Test
    fun `with three players two tied at threshold lowest id wins deterministically`() {
        // Players 2 and 3 both reach the threshold with the same win count.
        // The lowest player id (2) must win for a deterministic result regardless
        // of map iteration order.
        val score = MatchScore(roundWins = mapOf(1 to 0, 2 to 2, 3 to 2))
        assertEquals(2, score.matchWinnerId())
    }

    @Test
    fun `result is deterministic regardless of map construction order`() {
        // Same logical state, different insertion order — must produce the same winner.
        val scoreA = MatchScore(roundWins = mapOf(3 to 2, 2 to 2, 1 to 0))
        val scoreB = MatchScore(roundWins = mapOf(2 to 2, 3 to 2, 1 to 0))
        val winnerA = scoreA.matchWinnerId()
        val winnerB = scoreB.matchWinnerId()
        assertEquals(winnerA, winnerB)
        assertEquals(2, winnerA)
    }
}
