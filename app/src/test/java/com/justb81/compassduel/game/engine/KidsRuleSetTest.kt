package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.PlayerAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KidsRuleSetTest {

    private val rules = KidsRuleSet()
    private val now = 1_000_000L

    // Greeting bearings: p1 → p2 is 180°, p2 → p1 is 0°.
    private val setup = listOf(
        EnginePlayerSetup(id = 1, name = "Star", bearings = mapOf(2 to 180f)),
        EnginePlayerSetup(id = 2, name = "Comet", bearings = mapOf(1 to 0f)),
    )

    private fun initialKidsState(): EngineState.Kids =
        rules.initialState(setup) as EngineState.Kids

    // ---------------------------------------------------------------------------
    // initialState
    // ---------------------------------------------------------------------------

    @Test
    fun `initialState creates players with zero stars and no rest window`() {
        val state = initialKidsState()
        assertTrue(state.players.all { it.stars == 0 })
        assertTrue(state.players.all { it.restingUntilMillis == 0L })
    }

    // ---------------------------------------------------------------------------
    // Sparkle toss — successful catch
    // ---------------------------------------------------------------------------

    @Test
    fun `sparkle toss catches a star when on target`() {
        val state = initialKidsState()
        val inputs = TickInputs(
            continuousInputs = mapOf(1 to ContinuousInput(aimDegrees = 180f, isShielding = false)),
            queuedActions = listOf(QueuedAction(playerId = 1, action = PlayerAction.ATTACK, aimDegrees = 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        val catcher = (result.state as EngineState.Kids).players.first { it.id == 1 }
        assertEquals(KidsRules.STARS_PER_CATCH, catcher.stars)
    }

    @Test
    fun `CAUGHT event is emitted on a successful sparkle toss`() {
        val state = initialKidsState()
        val inputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        assertTrue(result.events.any { it.type == GameEventType.CAUGHT && it.actorId == 1 })
    }

    // ---------------------------------------------------------------------------
    // Rest window blocks catches, REST_OVER fires on expiry
    // ---------------------------------------------------------------------------

    @Test
    fun `resting player cannot be caught again`() {
        val state = initialKidsState()
        // First catch — p2 enters rest window
        val inputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val afterFirstCatch = rules.onTick(state, inputs, now, setup)
        val p2Resting = (afterFirstCatch.state as EngineState.Kids).players.first { it.id == 2 }
        assertTrue(p2Resting.restingUntilMillis > now)

        // Immediate second toss — should not catch p2 (resting)
        val result2 = rules.onTick(afterFirstCatch.state, inputs, now + 100L, setup)
        val catcher = (result2.state as EngineState.Kids).players.first { it.id == 1 }
        // Stars should still be 1 (only one successful catch)
        assertEquals(KidsRules.STARS_PER_CATCH, catcher.stars)
    }

    @Test
    fun `REST_OVER event fires when the rest window expires`() {
        val state = initialKidsState()
        val inputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val afterCatch = rules.onTick(state, inputs, now, setup)

        // Advance to just after the rest window
        val afterRestWindow = rules.onTick(
            afterCatch.state,
            TickInputs(emptyMap(), emptyList()),
            now + KidsRules.REST_AFTER_CAUGHT_MILLIS + 1L,
            setup,
        )
        assertTrue(afterRestWindow.events.any { it.type == GameEventType.REST_OVER && it.actorId == 2 })
    }

    // ---------------------------------------------------------------------------
    // Magic bubble block
    // ---------------------------------------------------------------------------

    @Test
    fun `magic bubble blocks sparkle and defender earns a star`() {
        val state = initialKidsState()
        val inputs = TickInputs(
            continuousInputs = mapOf(2 to ContinuousInput(aimDegrees = 0f, isShielding = true)), // p2 bubbling
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        val result = rules.onTick(state, inputs, now, setup)
        val defender = (result.state as EngineState.Kids).players.first { it.id == 2 }
        assertEquals(KidsRules.STARS_PER_BUBBLE_BLOCK, defender.stars)
        assertTrue(result.events.any { it.type == GameEventType.BUBBLED })
    }

    // ---------------------------------------------------------------------------
    // Host-side toss cooldown (Issue #54)
    // ---------------------------------------------------------------------------

    @Test
    fun `second toss within cooldown window is rejected even when target is not resting`() {
        // Use a miss for the first toss so p2 does NOT enter a rest window.
        // This isolates the toss cooldown on the actor side from the target-rest check.
        val state = initialKidsState()
        val missInputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, aimDegrees = 90f)), // aimed away
        )
        val hitInputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, aimDegrees = 180f)),
        )
        // First toss (miss) — sets the cooldown without catching p2
        val afterMiss = rules.onTick(state, missInputs, now, setup)
        val p1AfterMiss = (afterMiss.state as EngineState.Kids).players.first { it.id == 1 }
        assertEquals(0, p1AfterMiss.stars) { "Miss should not award stars" }
        assertTrue(p1AfterMiss.tossReadyAtMillis > now) { "Cooldown must be set even on a miss" }

        // Second toss within the cooldown window, this time aimed at p2 (who is not resting).
        // The host-side cooldown guard must block it — stars must stay at 0.
        val withinCooldown = now + KidsRules.TOSS_COOLDOWN_MILLIS - 1L
        val afterSecond = rules.onTick(afterMiss.state, hitInputs, withinCooldown, setup)
        val p1AfterSecond = (afterSecond.state as EngineState.Kids).players.first { it.id == 1 }
        assertEquals(0, p1AfterSecond.stars) { "Stars must remain 0 — toss rejected by cooldown" }
    }

    @Test
    fun `toss is accepted again after cooldown window expires`() {
        val state = initialKidsState()
        val inputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, 180f)),
        )
        // First toss lands; p2 enters rest window
        val afterFirst = rules.onTick(state, inputs, now, setup)

        // Advance past both toss cooldown AND p2's rest window so the second toss can land
        val afterCooldown = now + KidsRules.TOSS_COOLDOWN_MILLIS +
            KidsRules.REST_AFTER_CAUGHT_MILLIS + 1L
        val afterSecond = rules.onTick(afterFirst.state, inputs, afterCooldown, setup)
        val p1Stars = (afterSecond.state as EngineState.Kids).players.first { it.id == 1 }.stars
        assertEquals(KidsRules.STARS_PER_CATCH * 2, p1Stars) { "Second toss should land after cooldown" }
    }

    // ---------------------------------------------------------------------------
    // Sparkles thrown counter
    // ---------------------------------------------------------------------------

    @Test
    fun `sparkles thrown stat increments for every toss, including misses`() {
        val state = initialKidsState()
        // Toss aimed away from any target — will miss
        val missInputs = TickInputs(
            continuousInputs = emptyMap(),
            queuedActions = listOf(QueuedAction(1, PlayerAction.ATTACK, aimDegrees = 90f)), // 90° = not toward p2 at 180°
        )
        val result = rules.onTick(state, missInputs, now, setup)
        val stats = (result.state as EngineState.Kids).stats
        assertEquals(1, stats[1]?.sparklesThrown)
    }

    // ---------------------------------------------------------------------------
    // Round ends only on timer
    // ---------------------------------------------------------------------------

    @Test
    fun `round is not over before timer expires even if all toss counts are high`() {
        val state = initialKidsState()
        val notExpired = (KidsRules.ROUND_DURATION_SECONDS - 1) * MILLIS_PER_SECOND
        assertFalse(rules.isRoundOver(state, notExpired))
    }

    @Test
    fun `round is over when timer expires`() {
        val state = initialKidsState()
        val expired = KidsRules.ROUND_DURATION_SECONDS.toLong() * MILLIS_PER_SECOND
        assertTrue(rules.isRoundOver(state, expired))
    }

    // ---------------------------------------------------------------------------
    // roundOutcome assigns awards
    // ---------------------------------------------------------------------------

    @Test
    fun `roundOutcome returns KidsOutcome with non-empty awards`() {
        val state = initialKidsState()
        val outcome = rules.roundOutcome(state) as RoundOutcome.KidsOutcome
        assertEquals(2, outcome.awards.size) // one per player
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
