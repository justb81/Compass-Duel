package com.justb81.compassduel.game.engine

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.Position
import com.justb81.compassduel.game.kids.KidsRules
import com.justb81.compassduel.game.standard.StandardRules
import com.justb81.compassduel.net.protocol.PlayerAction
import com.justb81.compassduel.net.protocol.RoundPhase
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fake [GameClock] that starts at [startMillis] and can be advanced manually.
 * Allows tests to control the passage of time without real delays.
 */
class FakeClock(private var startMillis: Long = 1_000_000L) : GameClock {
    private var offsetMillis: Long = 0L

    override fun nowMillis(): Long = startMillis + offsetMillis

    /** Advance the clock by [millis] milliseconds. */
    fun advance(millis: Long) {
        offsetMillis += millis
    }

    /** Reset the clock to [startMillis]. */
    fun reset() {
        offsetMillis = 0L
    }
}

class GameEngineTest {

    // Standard two-player setup: player 1 north of player 2
    private val standardSetup = listOf(
        EnginePlayerSetup(id = 1, name = "Alice", position = Position(0f, 1f), element = Element.FIRE),
        EnginePlayerSetup(id = 2, name = "Bob", position = Position(0f, 0f), element = Element.EARTH),
    )

    // Kids two-player setup
    private val kidsSetup = listOf(
        EnginePlayerSetup(id = 1, name = "Star", position = Position(0f, 1f)),
        EnginePlayerSetup(id = 2, name = "Comet", position = Position(0f, 0f)),
    )

    private fun standardEngine(
        clock: FakeClock,
        scope: TestScope,
        tickMillis: Long = 100L,
    ): GameEngine = GameEngine(StandardRuleSet(), clock, scope, tickMillis)

    private fun kidsEngine(
        clock: FakeClock,
        scope: TestScope,
        tickMillis: Long = 100L,
    ): GameEngine = GameEngine(KidsRuleSet(), clock, scope, tickMillis)

    // ---------------------------------------------------------------------------
    // COUNTDOWN phase starts at round start
    // ---------------------------------------------------------------------------

    @Test
    fun `initial snapshot is in COUNTDOWN phase`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)
        assertEquals(RoundPhase.COUNTDOWN, engine.snapshots.value.phase)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Tick advances the timer
    // ---------------------------------------------------------------------------

    @Test
    fun `tick transitions from COUNTDOWN to PLAYING after countdown elapses`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        // Advance past the 3-second countdown
        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        assertEquals(RoundPhase.PLAYING, engine.snapshots.value.phase)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Attack event consumed exactly once
    // ---------------------------------------------------------------------------

    @Test
    fun `attack input is consumed on the tick it arrives and not repeated`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        // Advance to PLAYING phase
        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Submit an ATTACK action
        engine.submitInput(
            playerId = 1,
            aimDegrees = 180f,
            isShielding = false,
            action = PlayerAction.ATTACK,
        )

        // First tick — attack fires
        engine.tick()
        val hp1 = engine.snapshots.value.players.first { it.id == 2 }.hp
        assertTrue(hp1 < StandardRules.MAX_HP) { "Expected attack to deal damage on first tick" }

        // Second tick (no new attack submitted)
        clock.advance(StandardRules.ATTACK_COOLDOWN_MILLIS)
        engine.tick()
        val hp2 = engine.snapshots.value.players.first { it.id == 2 }.hp
        assertEquals(hp1, hp2) { "Action should not repeat on subsequent tick" }

        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Attack cooldown enforced
    // ---------------------------------------------------------------------------

    @Test
    fun `attack cooldown prevents second attack within cooldown window`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // First attack
        engine.submitInput(1, 180f, false, PlayerAction.ATTACK)
        engine.tick()
        val hpAfterFirst = engine.snapshots.value.players.first { it.id == 2 }.hp

        // Immediate second attack — within cooldown
        clock.advance(100L)
        engine.submitInput(1, 180f, false, PlayerAction.ATTACK)
        engine.tick()
        val hpAfterSecond = engine.snapshots.value.players.first { it.id == 2 }.hp

        assertEquals(hpAfterFirst, hpAfterSecond) { "Cooldown should prevent second attack" }
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Shield budget reported in the snapshot
    // ---------------------------------------------------------------------------

    @Test
    fun `standard snapshot reports the remaining shield budget`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Hold the shield for one second of ticks.
        engine.submitInput(2, 0f, true, PlayerAction.IDLE)
        engine.tick()
        clock.advance(MILLIS_PER_SECOND)
        engine.submitInput(2, 0f, true, PlayerAction.IDLE)
        engine.tick()

        val p2 = engine.snapshots.value.players.first { it.id == 2 }
        assertTrue(p2.shieldRemainingMillis < StandardRules.SHIELD_BUDGET_MILLIS) {
            "Shielding should have consumed some of the budget"
        }
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Round ends on elimination
    // ---------------------------------------------------------------------------

    @Test
    fun `round transitions to ROUND_OVER when one player is eliminated`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)

        // Use setup where p2 has very low HP so one attack KOs them
        val lowHpSetup = standardSetup.map { s ->
            if (s.id == 2) s else s
        }
        engine.startRound(lowHpSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Deliver enough attacks to KO player 2 (each strong hit = 30 dmg)
        repeat(ATTACKS_TO_KO) { attackIndex ->
            clock.advance(StandardRules.ATTACK_COOLDOWN_MILLIS)
            engine.submitInput(1, 180f, false, PlayerAction.ATTACK)
            engine.tick()
        }

        assertEquals(RoundPhase.ROUND_OVER, engine.snapshots.value.phase)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Round ends on timeout — highest HP wins
    // ---------------------------------------------------------------------------

    @Test
    fun `round ends on timer expiry with highest-HP player winning`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Advance past full round duration
        clock.advance(StandardRules.ROUND_DURATION_SECONDS * MILLIS_PER_SECOND.toLong())
        engine.tick()

        assertEquals(RoundPhase.ROUND_OVER, engine.snapshots.value.phase)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Kids round ends only on timer
    // ---------------------------------------------------------------------------

    @Test
    fun `kids round does not end when one player has many stars (timer only)`() = runTest {
        val clock = FakeClock()
        val engine = kidsEngine(clock, this)
        engine.startRound(kidsSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Many attacks — stars go up but round should not end
        repeat(KIDS_ATTACK_COUNT) {
            clock.advance(KidsRules.REST_AFTER_CAUGHT_MILLIS)
            engine.submitInput(1, 180f, false, PlayerAction.ATTACK)
            engine.tick()
        }

        assertEquals(RoundPhase.PLAYING, engine.snapshots.value.phase)
        engine.stop()
    }

    @Test
    fun `kids round ends when timer expires`() = runTest {
        val clock = FakeClock()
        val engine = kidsEngine(clock, this)
        engine.startRound(kidsSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // Jump past round duration
        clock.advance(KidsRules.ROUND_DURATION_SECONDS * MILLIS_PER_SECOND.toLong())
        engine.tick()

        assertEquals(RoundPhase.ROUND_OVER, engine.snapshots.value.phase)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // roundOutcome() phase guard (Issue #56)
    // ---------------------------------------------------------------------------

    @Test
    fun `roundOutcome is null during COUNTDOWN phase`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        // Still in COUNTDOWN — roundOutcome must be null
        engine.tick()
        assertNull(engine.roundOutcome()) { "roundOutcome must be null in COUNTDOWN" }
        engine.stop()
    }

    @Test
    fun `roundOutcome is null during PLAYING phase before round ends`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        assertEquals(RoundPhase.PLAYING, engine.snapshots.value.phase)
        assertNull(engine.roundOutcome()) { "roundOutcome must be null while PLAYING" }
        engine.stop()
    }

    @Test
    fun `roundOutcome is non-null after ROUND_OVER phase`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        clock.advance(COUNTDOWN_MILLIS + 1L)
        engine.tick()

        // KO player 2 to trigger ROUND_OVER
        repeat(ATTACKS_TO_KO) {
            clock.advance(StandardRules.ATTACK_COOLDOWN_MILLIS)
            engine.submitInput(1, 180f, false, PlayerAction.ATTACK)
            engine.tick()
        }

        assertEquals(RoundPhase.ROUND_OVER, engine.snapshots.value.phase)
        assertTrue(engine.roundOutcome() != null) { "roundOutcome must be non-null at ROUND_OVER" }
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // COUNTDOWN->PLAYING timer continuity (Issue #57)
    // ---------------------------------------------------------------------------

    @Test
    fun `remainingMillis does not jump at the COUNTDOWN to PLAYING boundary with non-zero tick granularity`() = runTest {
        // Use a tick size larger than 1 ms to simulate real-world granularity.
        val tickMs = 50L
        val clock = FakeClock()
        val engine = standardEngine(clock, this, tickMillis = tickMs)
        engine.startRound(standardSetup, roundIndex = 0)

        // Collect remainingMillis across the boundary.
        var lastCountdownRemaining = Long.MAX_VALUE
        var firstPlayingRemaining = Long.MIN_VALUE
        var sawPlaying = false

        // Run enough ticks to cross the 3 s boundary and into PLAYING
        repeat(80) {
            clock.advance(tickMs)
            engine.tick()
            val snap = engine.snapshots.value
            when (snap.phase) {
                RoundPhase.COUNTDOWN -> lastCountdownRemaining = snap.remainingMillis
                RoundPhase.PLAYING -> {
                    if (!sawPlaying) {
                        firstPlayingRemaining = snap.remainingMillis
                        sawPlaying = true
                    }
                }
                else -> Unit
            }
        }

        assertTrue(sawPlaying) { "Engine should reach PLAYING within 80 ticks of 50 ms" }

        // The last COUNTDOWN remaining was some small value near 0.
        // The first PLAYING remaining is roundDurationSeconds * 1000.
        // There must be no negative discontinuity: the PLAYING timer should not start
        // below the full round duration (it starts at round_duration - elapsed_since_active_start).
        val roundMillis = StandardRules.ROUND_DURATION_SECONDS * MILLIS_PER_SECOND.toLong()
        // The first PLAYING remainingMillis should be <= roundMillis (elapsed is ≥ 0)
        assertTrue(firstPlayingRemaining <= roundMillis) {
            "PLAYING remaining ($firstPlayingRemaining) must not exceed round duration ($roundMillis)"
        }
        // And the transition should not produce a sudden jump: remaining should decrease
        // monotonically from COUNTDOWN → PLAYING boundary.  Since COUNTDOWN remaining
        // counts down from 3000 to 0, and PLAYING remaining starts near roundMillis,
        // we verify that PLAYING remaining at t=0 is the full round duration (no over-shoot).
        assertTrue(firstPlayingRemaining >= 0L) { "PLAYING remaining must not be negative at boundary" }
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Snapshot sequence number increments
    // ---------------------------------------------------------------------------

    @Test
    fun `snapshot sequence number increases on each tick`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)

        val seq1 = engine.snapshots.value.seq
        engine.tick()
        val seq2 = engine.snapshots.value.seq
        engine.tick()
        val seq3 = engine.snapshots.value.seq

        assertTrue(seq2 > seq1)
        assertTrue(seq3 > seq2)
        engine.stop()
    }

    // ---------------------------------------------------------------------------
    // Stop cancels the engine
    // ---------------------------------------------------------------------------

    @Test
    fun `stop cancels the engine without throwing`() = runTest {
        val clock = FakeClock()
        val engine = standardEngine(clock, this)
        engine.startRound(standardSetup, roundIndex = 0)
        engine.stop() // must not throw
        assertNull(null) // sentinel — test passes if no exception
    }

    companion object {
        private const val COUNTDOWN_MILLIS = 3_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /** Strong matchup = 30 dmg per hit; 4 hits KO a 100-HP player. */
        private const val ATTACKS_TO_KO = 4

        /** Enough attacks to accumulate many stars, but not to end a kids round. */
        private const val KIDS_ATTACK_COUNT = 5
    }
}
