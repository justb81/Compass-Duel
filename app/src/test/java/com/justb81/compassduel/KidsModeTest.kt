package com.justb81.compassduel

import com.justb81.compassduel.game.Bearing
import com.justb81.compassduel.game.kids.CatchResult
import com.justb81.compassduel.game.kids.KidsPlayer
import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.game.kids.evaluateCatch
import com.justb81.compassduel.game.kids.isResting
import com.justb81.compassduel.game.kids.starLeaderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KidsModeTest {

    private val now = 1_000_000L
    private val target = KidsPlayer(id = 2)

    @Test
    fun `catch lands inside the wide kids aim cone`() {
        val result = evaluateCatch(
            aimAzimuth = 30f,
            bearingToTarget = 0f,
            target = target,
            targetIsStarLeader = false,
            nowMillis = now,
        )

        assertEquals(CatchResult.Caught(KidsRules.STARS_PER_CATCH), result)
    }

    @Test
    fun `kids aim cone is more forgiving than the standard one`() {
        // 30° off target: a miss under the standard ±25° rule, a catch in Kids Mode.
        assertFalse(Bearing.isOnTarget(aim = 30f, target = 0f))
        assertTrue(Bearing.isOnTarget(aim = 30f, target = 0f, tolerance = KidsRules.AIM_TOLERANCE_DEGREES))
    }

    @Test
    fun `toss outside the aim cone misses`() {
        val result = evaluateCatch(
            aimAzimuth = 41f,
            bearingToTarget = 0f,
            target = target,
            targetIsStarLeader = false,
            nowMillis = now,
        )

        assertEquals(CatchResult.Missed, result)
    }

    @Test
    fun `magic bubble blocks the sparkle and rewards the defender`() {
        val result = evaluateCatch(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            target = target.copy(inBubble = true),
            targetIsStarLeader = false,
            nowMillis = now,
        )

        assertEquals(CatchResult.Bubbled(KidsRules.STARS_PER_BUBBLE_BLOCK), result)
    }

    @Test
    fun `resting player cannot be caught`() {
        val resting = target.copy(restingUntilMillis = now + 1)

        val result = evaluateCatch(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            target = resting,
            targetIsStarLeader = false,
            nowMillis = now,
        )

        assertEquals(CatchResult.TargetResting, result)
    }

    @Test
    fun `rest window expires`() {
        val player = target.copy(restingUntilMillis = now + KidsRules.REST_AFTER_CAUGHT_MILLIS)

        assertTrue(player.isResting(now))
        assertFalse(player.isResting(now + KidsRules.REST_AFTER_CAUGHT_MILLIS))
    }

    @Test
    fun `catching the star leader awards the catch-up bonus`() {
        val result = evaluateCatch(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            target = target,
            targetIsStarLeader = true,
            nowMillis = now,
        )

        assertEquals(CatchResult.Caught(KidsRules.STARS_PER_LEADER_CATCH), result)
    }

    @Test
    fun `star leader is the player strictly ahead`() {
        val players = listOf(
            KidsPlayer(id = 1, stars = 3),
            KidsPlayer(id = 2, stars = 5),
            KidsPlayer(id = 3, stars = 4),
        )

        assertEquals(2, starLeaderId(players))
    }

    @Test
    fun `no star leader on a shared lead or before the first star`() {
        val tied = listOf(KidsPlayer(id = 1, stars = 4), KidsPlayer(id = 2, stars = 4))
        val fresh = listOf(KidsPlayer(id = 1), KidsPlayer(id = 2))

        assertNull(starLeaderId(tied))
        assertNull(starLeaderId(fresh))
        assertNull(starLeaderId(emptyList()))
    }
}
