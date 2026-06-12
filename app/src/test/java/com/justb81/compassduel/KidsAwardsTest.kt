package com.justb81.compassduel

import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
import com.justb81.compassduel.game.kids.assignAwards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KidsAwardsTest {

    @Test
    fun `every player receives exactly one award`() {
        val stats = listOf(
            KidsRoundStats(playerId = 1, stars = 5, bubbleBlocks = 0, sparklesThrown = 8),
            KidsRoundStats(playerId = 2, stars = 2, bubbleBlocks = 3, sparklesThrown = 4),
            KidsRoundStats(playerId = 3, stars = 1, bubbleBlocks = 1, sparklesThrown = 9),
            KidsRoundStats(playerId = 4, stars = 0, bubbleBlocks = 0, sparklesThrown = 2),
        )

        val awards = assignAwards(stats)

        assertEquals(setOf(1, 2, 3, 4), awards.keys)
        assertEquals(KidsAward.STAR_CHAMPION, awards[1])
        assertEquals(KidsAward.BUBBLE_HERO, awards[2])
        assertEquals(KidsAward.BUSY_BEE, awards[3])
        assertEquals(KidsAward.SUPER_SPARKLER, awards[4])
    }

    @Test
    fun `a single player never collects two awards`() {
        // Player 1 leads every metric; the category awards cascade to the others.
        val stats = listOf(
            KidsRoundStats(playerId = 1, stars = 5, bubbleBlocks = 4, sparklesThrown = 9),
            KidsRoundStats(playerId = 2, stars = 1, bubbleBlocks = 2, sparklesThrown = 3),
            KidsRoundStats(playerId = 3, stars = 0, bubbleBlocks = 1, sparklesThrown = 5),
        )

        val awards = assignAwards(stats)

        assertEquals(KidsAward.STAR_CHAMPION, awards[1])
        assertEquals(KidsAward.BUBBLE_HERO, awards[2])
        assertEquals(KidsAward.BUSY_BEE, awards[3])
    }

    @Test
    fun `unearned categories are skipped`() {
        val stats = listOf(
            KidsRoundStats(playerId = 1, stars = 0, bubbleBlocks = 0, sparklesThrown = 0),
            KidsRoundStats(playerId = 2, stars = 0, bubbleBlocks = 0, sparklesThrown = 0),
        )

        val awards = assignAwards(stats)

        assertEquals(KidsAward.SUPER_SPARKLER, awards[1])
        assertEquals(KidsAward.SUPER_SPARKLER, awards[2])
    }

    @Test
    fun `ties go to the lower player id`() {
        val stats = listOf(
            KidsRoundStats(playerId = 3, stars = 4, bubbleBlocks = 0, sparklesThrown = 0),
            KidsRoundStats(playerId = 2, stars = 4, bubbleBlocks = 0, sparklesThrown = 0),
        )

        val awards = assignAwards(stats)

        assertEquals(KidsAward.STAR_CHAMPION, awards[2])
        assertEquals(KidsAward.SUPER_SPARKLER, awards[3])
    }
}
