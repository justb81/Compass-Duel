package com.justb81.compassduel.ui.components

import com.justb81.compassduel.net.protocol.GameEvent
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.GameMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Guards the event -> action-effect mapping. A regression here would silently
 * disable the combat visuals (issue #34): the projectile must be driven by the
 * local player's own resolved attack event, not by a `PlayerStatus.ATTACKING`
 * the engine never emits.
 */
class ActionEffectMapperTest {

    private val me = 1
    private val foe = 2

    @Test
    fun `standard - my landed attack flies and bursts`() {
        val event = GameEvent(GameEventType.HIT, actorId = me, targetId = foe, amount = 20)
        assertEquals(ActionEffectKind.ATTACK_LANDED, actionEffectKindFor(event, me, GameMode.STANDARD))
    }

    @Test
    fun `standard - my blocked attack shows a guarded burst`() {
        val event = GameEvent(GameEventType.BLOCKED, actorId = me, targetId = foe)
        assertEquals(ActionEffectKind.ATTACK_BLOCKED, actionEffectKindFor(event, me, GameMode.STANDARD))
    }

    @Test
    fun `standard - my missed attack fizzles`() {
        val event = GameEvent(GameEventType.MISS, actorId = me, targetId = foe)
        assertEquals(ActionEffectKind.ATTACK_MISSED, actionEffectKindFor(event, me, GameMode.STANDARD))
    }

    @Test
    fun `standard - incoming hit is damage taken`() {
        val event = GameEvent(GameEventType.HIT, actorId = foe, targetId = me, amount = 20)
        assertEquals(ActionEffectKind.DAMAGE_TAKEN, actionEffectKindFor(event, me, GameMode.STANDARD))
    }

    @Test
    fun `standard - an opponent's attack on another player shows nothing for me`() {
        val event = GameEvent(GameEventType.HIT, actorId = foe, targetId = 3, amount = 20)
        assertNull(actionEffectKindFor(event, me, GameMode.STANDARD))
    }

    @Test
    fun `kids - my catch flies and bursts, never damage`() {
        val event = GameEvent(GameEventType.CAUGHT, actorId = me, targetId = foe, amount = 1)
        assertEquals(ActionEffectKind.ATTACK_LANDED, actionEffectKindFor(event, me, GameMode.KIDS))
    }

    @Test
    fun `kids - my toss bubbled by the target shows a blocked burst`() {
        val event = GameEvent(GameEventType.BUBBLED, actorId = me, targetId = foe, amount = 1)
        assertEquals(ActionEffectKind.ATTACK_BLOCKED, actionEffectKindFor(event, me, GameMode.KIDS))
    }

    @Test
    fun `kids - bubbling an incoming toss shows a defensive burst`() {
        val event = GameEvent(GameEventType.BUBBLED, actorId = foe, targetId = me, amount = 1)
        assertEquals(ActionEffectKind.DEFENSE_BLOCKED, actionEffectKindFor(event, me, GameMode.KIDS))
    }

    @Test
    fun `kids - never surfaces damage taken on an incoming catch`() {
        val event = GameEvent(GameEventType.CAUGHT, actorId = foe, targetId = me, amount = 1)
        assertNull(actionEffectKindFor(event, me, GameMode.KIDS))
    }
}
