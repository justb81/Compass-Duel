package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.Position
import com.justb81.compassduel.game.standard.StandardRules
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.PlayerAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardRuleSetTest {

    private val rules = StandardRuleSet()
    private val now = 1_000_000L

    // Player 1 is north of player 2 — bearing from p1 to p2 is 180°, from p2 to p1 is 0°
    private val setup = listOf(
        EnginePlayerSetup(id = 1, name = "Alice", position = Position(0f, 1f), element = Element.FIRE),
        EnginePlayerSetup(id = 2, name = "Bob", position = Position(0f, 0f), element = Element.EARTH),
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
    // Dodge cooldown enforcement
    // ---------------------------------------------------------------------------

    @Test
    fun `dodge cooldown prevents second dodge within cooldown window`() {
        val state = twoPlayerState()
        val dodgeInputs = TickInputs(
            continuousInputs = mapOf(2 to ContinuousInput(aimDegrees = 0f, isShielding = false)),
            queuedActions = listOf(QueuedAction(2, PlayerAction.DODGE, 0f)),
        )
        // First dodge — sets the active window and cooldown
        val result1 = rules.onTick(state, dodgeInputs, now, setup)
        val p2after1 = (result1.state as EngineState.Standard).players.first { it.id == 2 }
        assertTrue(p2after1.dodgeActiveUntilMillis > now)

        // Try second dodge immediately — should be blocked by cooldown
        val result2 = rules.onTick(result1.state, dodgeInputs, now + 100L, setup)
        val p2after2 = (result2.state as EngineState.Standard).players.first { it.id == 2 }
        // dodgeReadyAtMillis should still be far in the future (not reset)
        assertTrue(p2after2.dodgeReadyAtMillis > now + 100L)
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
    }
}
