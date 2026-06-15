package com.justb81.compassduel.ui.components

import com.justb81.compassduel.net.protocol.GameEvent
import com.justb81.compassduel.net.protocol.GameEventType
import com.justb81.compassduel.net.protocol.GameMode

/**
 * Pure mapping from a host [GameEvent] to the [ActionEffectKind] the local player
 * (id [myId]) should see, branching on [mode].
 *
 * The host resolves every attack within the tick it is submitted, so there is no
 * separate "attack started" signal — the projectile is therefore driven off the
 * actor's *own* resolved attack event (HIT / BLOCKED / MISS in Standard, CAUGHT /
 * BUBBLED in Kids) rather than a transient `ATTACKING` status (which the engine
 * never emits). The overlay sequences the flight and the impact from that single
 * event, so no new network payload is introduced.
 *
 * Kids Mode never returns [ActionEffectKind.DAMAGE_TAKEN]; incoming sparkles read
 * as friendly catches/bubbles only.
 */
fun actionEffectKindFor(event: GameEvent, myId: Int, mode: GameMode): ActionEffectKind? = when (mode) {
    GameMode.STANDARD -> standardEffectKind(event, myId)
    GameMode.KIDS -> kidsEffectKind(event, myId)
}

private fun standardEffectKind(event: GameEvent, myId: Int): ActionEffectKind? = when {
    event.actorId == myId && event.type == GameEventType.HIT -> ActionEffectKind.ATTACK_LANDED
    event.actorId == myId && event.type == GameEventType.BLOCKED -> ActionEffectKind.ATTACK_BLOCKED
    event.actorId == myId && event.type == GameEventType.MISS -> ActionEffectKind.ATTACK_MISSED
    event.type == GameEventType.HIT && event.targetId == myId -> ActionEffectKind.DAMAGE_TAKEN
    else -> null
}

private fun kidsEffectKind(event: GameEvent, myId: Int): ActionEffectKind? = when {
    event.actorId == myId && event.type == GameEventType.CAUGHT -> ActionEffectKind.ATTACK_LANDED
    event.actorId == myId && event.type == GameEventType.BUBBLED -> ActionEffectKind.ATTACK_BLOCKED
    event.type == GameEventType.BUBBLED && event.targetId == myId -> ActionEffectKind.DEFENSE_BLOCKED
    else -> null
}
