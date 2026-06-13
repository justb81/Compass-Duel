package com.justb81.compassduel.game.standard

import com.justb81.compassduel.game.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardModeTest {

    // ---------------------------------------------------------------------------
    // Helper factories
    // ---------------------------------------------------------------------------

    private fun alivePlayer(
        id: Int = 2,
        element: Element? = Element.WATER,
        hp: Int = StandardRules.MAX_HP,
        isShielding: Boolean = false,
    ) = DuelPlayer(
        id = id,
        element = element,
        hp = hp,
        isShielding = isShielding,
    )

    // ---------------------------------------------------------------------------
    // evaluateAttack — element damage modifiers
    // ---------------------------------------------------------------------------

    @Test
    fun `strong matchup deals 30 damage (base 20 times 1,5)`() {
        // FIRE is strong against EARTH
        val target = alivePlayer(element = Element.EARTH)
        val result = evaluateAttack(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = target,
        )
        assertEquals(AttackResult.Hit(damage = 30), result)
    }

    @Test
    fun `weak matchup deals 10 damage (base 20 times 0,5)`() {
        // FIRE is weak against WATER
        val target = alivePlayer(element = Element.WATER)
        val result = evaluateAttack(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = target,
        )
        assertEquals(AttackResult.Hit(damage = 10), result)
    }

    @Test
    fun `neutral matchup deals 20 damage`() {
        // FIRE vs LIGHTNING — neither strong nor weak
        val target = alivePlayer(element = Element.LIGHTNING)
        val result = evaluateAttack(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = target,
        )
        assertEquals(AttackResult.Hit(damage = 20), result)
    }

    // ---------------------------------------------------------------------------
    // evaluateAttack — shield blocks fully
    // ---------------------------------------------------------------------------

    @Test
    fun `attack on shielding target returns Blocked`() {
        val shielding = alivePlayer(isShielding = true)
        val result = evaluateAttack(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = shielding,
        )
        assertEquals(AttackResult.Blocked, result)
    }

    // ---------------------------------------------------------------------------
    // DuelPlayer — shield budget default
    // ---------------------------------------------------------------------------

    @Test
    fun `a new player starts with the full shield-time budget`() {
        assertEquals(StandardRules.SHIELD_BUDGET_MILLIS, alivePlayer().shieldRemainingMillis)
    }

    // ---------------------------------------------------------------------------
    // evaluateAttack — aim cone miss
    // ---------------------------------------------------------------------------

    @Test
    fun `attack outside 25-degree cone returns Missed`() {
        val target = alivePlayer()
        val result = evaluateAttack(
            aimAzimuth = 30f, // 30° off bearing 0° — outside ±25°
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = target,
        )
        assertEquals(AttackResult.Missed, result)
    }

    // ---------------------------------------------------------------------------
    // evaluateAttack — eliminated target
    // ---------------------------------------------------------------------------

    @Test
    fun `attack on eliminated player returns Missed`() {
        val eliminated = alivePlayer(hp = 0)
        val result = evaluateAttack(
            aimAzimuth = 0f,
            bearingToTarget = 0f,
            attackerElement = Element.FIRE,
            target = eliminated,
        )
        assertEquals(AttackResult.Missed, result)
    }

    // ---------------------------------------------------------------------------
    // applyDamage — hp floor at 0
    // ---------------------------------------------------------------------------

    @Test
    fun `applying damage that exceeds hp floors at zero`() {
        val player = alivePlayer(hp = 10)
        val updated = applyDamage(player, damage = 50)
        assertEquals(0, updated.hp)
        assertTrue(updated.isEliminated)
    }

    @Test
    fun `applying zero damage leaves hp unchanged`() {
        val player = alivePlayer(hp = 80)
        assertEquals(80, applyDamage(player, damage = 0).hp)
    }

    // ---------------------------------------------------------------------------
    // lastSurvivorId — 2, 3, 4 players
    // ---------------------------------------------------------------------------

    @Test
    fun `returns sole survivor id in a two-player game`() {
        val players = listOf(
            alivePlayer(id = 1, hp = 50),
            alivePlayer(id = 2, hp = 0),
        )
        assertEquals(1, lastSurvivorId(players))
    }

    @Test
    fun `returns null when two players are both alive`() {
        val players = listOf(
            alivePlayer(id = 1, hp = 50),
            alivePlayer(id = 2, hp = 30),
        )
        assertNull(lastSurvivorId(players))
    }

    @Test
    fun `returns survivor id when two of three players are eliminated`() {
        val players = listOf(
            alivePlayer(id = 1, hp = 0),
            alivePlayer(id = 2, hp = 70),
            alivePlayer(id = 3, hp = 0),
        )
        assertEquals(2, lastSurvivorId(players))
    }

    @Test
    fun `returns null when all four players are eliminated (draw)`() {
        val players = (1..4).map { id -> alivePlayer(id = id, hp = 0) }
        assertNull(lastSurvivorId(players))
    }

    @Test
    fun `returns survivor id when three of four players are eliminated`() {
        val players = listOf(
            alivePlayer(id = 1, hp = 0),
            alivePlayer(id = 2, hp = 0),
            alivePlayer(id = 3, hp = 40),
            alivePlayer(id = 4, hp = 0),
        )
        assertEquals(3, lastSurvivorId(players))
    }
}
