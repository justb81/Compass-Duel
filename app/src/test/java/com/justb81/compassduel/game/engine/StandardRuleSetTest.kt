package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.standard.StandardRules
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.PlayerAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardRuleSetTest {

    private val rules = StandardRuleSet()
    private val now = 1_000_000L

    // Greeting bearings: p1 → p2 is 180°, p2 → p1 is 0° (they point at each other).
    private val setup = listOf(
        EnginePlayerSetup(id = 1, name = "Alice", bearings = mapOf(2 to 180f), element = Element.FIRE),
        EnginePlayerSetup(id = 2, name = "Bob", bearings = mapOf(1 to 0f), element = Element.EARTH),
    )

    private fun twoPlayerState(hp1: Int = 100, hp2: Int = 100): EngineState.Standard =
        rules.initialState(setup).let { state ->
            EngineState.Standard(
                state.players.map { p ->
                    when (p.id) {
                        1 -> p.copy(hp = hp1)
                        2 -> p.copy(hp = hp2)
                        else -> p
                    }
                }
            )
        }

    // ---------------------------------------------------------------------------
    // initialState
    // ---------------------------------------------------------------------------

    @Test
    fun `initialState creates players at MAX_HP`() {
        val state = rules.initialState(setup) as EngineState.Standard
        assertTrue(state.players.all { it.hp == StandardRules.MAX_HP })
    }

    @Test
    fun `default round length yields the default shield budget`() {
        assertEquals(StandardRules.ROUND_DURATION_SECONDS, rules.roundDurationSeconds)
        val state = rules.initialState(setup) as EngineState.Standard
        assertTrue(state.players.all { it.shieldRemainingMillis == StandardRules.SHIELD_BUDGET_MILLIS })
    }

    @Test
    fun `configured round length scales the shield budget to half the round (#101)`() {
        val shortRules = StandardRuleSet(roundDurationSeconds = 30)
        assertEquals(30, shortRules.roundDurationSeconds)
        val state = shortRules.initialState(setup) as EngineState.Standard
        // 30 s round → 15 000 ms shield budget (50 %).
        assertTrue(state.players.all { it.shieldRemainingMillis == 15_000L })
    }

    // ---------------------------------------------------------------------------
    // Attack with element modifier
    // ---------------------------------------------------------------------------

    @Test
    fun `FIRE attack on EARTH (strong matchup) deals 30 damage`() {
        val state = twoPlayerState()
        // Player 2 is south of player 1; player 1 aims south (180°)
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(playerId = 1, action = PlayerAction.ATTACK, aimDegrees = 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        val p2 = (result.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(StandardRules.MAX_HP - 30, p2.hp)
    }

    @Test
    fun `attack with no captured bearing to the target misses`() {
        // Actor has no greeting bearing toward player 2 → no target can be selected.
        val noBearingSetup = listOf(
            EnginePlayerSetup(id = 1, name = "Alice", bearings = emptyMap(), element = Element.FIRE),
            EnginePlayerSetup(id = 2, name = "Bob", bearings = mapOf(1 to 0f), element = Element.EARTH),
        )
        val state = rules.initialState(noBearingSetup) as EngineState.Standard
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, noBearingSetup)
        val p2 = (result.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(StandardRules.MAX_HP, p2.hp)
    }

    @Test
    fun `attack emits HIT event with correct damage`() {
        val state = twoPlayerState()
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        val hitEvent = result.events.firstOrNull { it.type == GameEventType.HIT }
        assertEquals(30, hitEvent?.amount)
    }

    // ---------------------------------------------------------------------------
    // Attack cooldown enforcement
    // ---------------------------------------------------------------------------

    @Test
    fun `attack cooldown prevents second attack within cooldown window`() {
        val state = twoPlayerState()
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        // First attack lands
        val result1 = rules.onTick(state, inputs, now, setup)
        val p2hp1 = (result1.state as EngineState.Standard).players.first { it.id == 2 }.hp
        assertEquals(70, p2hp1)

        // Second attack within cooldown window — should be blocked by cooldown
        val result2 = rules.onTick(result1.state, inputs, now + 100L, setup)
        val p2hp2 = (result2.state as EngineState.Standard).players.first { it.id == 2 }.hp
        assertEquals(70, p2hp2) // HP unchanged — cooldown prevented second hit
    }

    @Test
    fun `attack fires again after cooldown expires`() {
        val state = twoPlayerState()
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result1 = rules.onTick(state, inputs, now, setup)
        val afterCooldown = now + StandardRules.ATTACK_COOLDOWN_MILLIS
        val result2 = rules.onTick(result1.state, inputs, afterCooldown, setup)
        val p2hp = (result2.state as EngineState.Standard).players.first { it.id == 2 }.hp
        assertEquals(StandardRules.MAX_HP - 60, p2hp) // two hits of 30 each
    }

    // ---------------------------------------------------------------------------
    // Shield blocks attack
    // ---------------------------------------------------------------------------

    @Test
    fun `attack on shielding target emits BLOCKED and deals no damage`() {
        val state = twoPlayerState()
        val inputs = TickInputs(
            continuousInputs = mapOf(
                1 to ContinuousInput(aimDegrees = 180f, isShielding = false),
                2 to ContinuousInput(aimDegrees = 0f, isShielding = true), // p2 is shielding
            ),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        val p2 = (result.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(StandardRules.MAX_HP, p2.hp)
        assertTrue(result.events.any { it.type == GameEventType.BLOCKED })
    }

    // ---------------------------------------------------------------------------
    // Shield-time budget
    // ---------------------------------------------------------------------------

    @Test
    fun `shielding consumes the budget across ticks`() {
        val state = twoPlayerState()
        val shieldInputs = TickInputs(
            continuousInputs = mapOf(2 to ContinuousInput(aimDegrees = 0f, isShielding = true)),
            queuedActions = emptyList(),
        )
        // First tick sets lastTickMillis (delta = 0); the second consumes 1 s.
        val tick1 = rules.onTick(state, shieldInputs, now, setup)
        val tick2 = rules.onTick(tick1.state, shieldInputs, now + MILLIS_PER_SECOND, setup)
        val p2 = (tick2.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(StandardRules.SHIELD_BUDGET_MILLIS - MILLIS_PER_SECOND, p2.shieldRemainingMillis)
        assertTrue(p2.isShielding)
    }

    @Test
    fun `shield is force-dropped once the budget is exhausted`() {
        // Seed player 2 with almost no budget left.
        val seeded = twoPlayerState().let { s ->
            EngineState.Standard(
                s.players.map { if (it.id == 2) it.copy(shieldRemainingMillis = 100L) else it },
                lastTickMillis = now,
            )
        }
        val shieldInputs = TickInputs(
            continuousInputs = mapOf(2 to ContinuousInput(aimDegrees = 0f, isShielding = true)),
            queuedActions = emptyList(),
        )
        val result = rules.onTick(seeded, shieldInputs, now + MILLIS_PER_SECOND, setup)
        val p2 = (result.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(0L, p2.shieldRemainingMillis)
        assertFalse(p2.isShielding)
    }

    @Test
    fun `budget is not consumed when the player is not shielding`() {
        val state = twoPlayerState()
        val idleInputs = TickInputs(
            continuousInputs = mapOf(2 to ContinuousInput(aimDegrees = 0f, isShielding = false)),
            queuedActions = emptyList(),
        )
        val tick1 = rules.onTick(state, idleInputs, now, setup)
        val tick2 = rules.onTick(tick1.state, idleInputs, now + FIVE_SECONDS_MILLIS, setup)
        val p2 = (tick2.state as EngineState.Standard).players.first { it.id == 2 }
        assertEquals(StandardRules.SHIELD_BUDGET_MILLIS, p2.shieldRemainingMillis)
    }

    // ---------------------------------------------------------------------------
    // isRoundOver — elimination and timer
    // ---------------------------------------------------------------------------

    @Test
    fun `round is over when only one player survives`() {
        val state = twoPlayerState(hp1 = 50, hp2 = 0)
        assertTrue(rules.isRoundOver(state, elapsedMillis = 0L))
    }

    @Test
    fun `round is over when timer expires`() {
        val state = twoPlayerState()
        val elapsed = StandardRules.ROUND_DURATION_SECONDS * MILLIS_PER_SECOND
        assertTrue(rules.isRoundOver(state, elapsed))
    }

    @Test
    fun `round is not over when both players are alive and timer has not expired`() {
        val state = twoPlayerState()
        val partialElapsed = (StandardRules.ROUND_DURATION_SECONDS - 1) * MILLIS_PER_SECOND
        assertEquals(false, rules.isRoundOver(state, partialElapsed))
    }

    // ---------------------------------------------------------------------------
    // roundOutcome — highest HP on timeout, tie = draw
    // ---------------------------------------------------------------------------

    @Test
    fun `timeout outcome gives round win to player with higher HP`() {
        val state = twoPlayerState(hp1 = 80, hp2 = 60)
        val outcome = rules.roundOutcome(state) as RoundOutcome.StandardWinner
        assertEquals(1, outcome.winnerId)
    }

    @Test
    fun `timeout outcome is draw when HP is exactly tied`() {
        val state = twoPlayerState(hp1 = 50, hp2 = 50)
        val outcome = rules.roundOutcome(state) as RoundOutcome.StandardWinner
        assertNull(outcome.winnerId)
    }

    @Test
    fun `elimination outcome gives win to sole survivor`() {
        val state = twoPlayerState(hp1 = 40, hp2 = 0)
        val outcome = rules.roundOutcome(state) as RoundOutcome.StandardWinner
        assertEquals(1, outcome.winnerId)
    }

    // ---------------------------------------------------------------------------
    // ELIMINATED event
    // ---------------------------------------------------------------------------

    @Test
    fun `ELIMINATED event fires when HP reaches zero`() {
        val state = twoPlayerState(hp1 = 100, hp2 = 5) // p2 will be KO'd by a 30-damage hit
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        assertTrue(result.events.any { it.type == GameEventType.ELIMINATED && it.actorId == 2 })
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val FIVE_SECONDS_MILLIS = 5_000L
    }
}
