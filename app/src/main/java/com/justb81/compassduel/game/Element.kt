package com.justb81.compassduel.game

/**
 * The four playable elements and their classic strength/weakness relationships.
 *
 * | Attacker  | Strong against | Weak against |
 * |-----------|----------------|--------------|
 * | Fire      | Earth          | Water        |
 * | Water     | Fire           | Lightning    |
 * | Earth     | Lightning      | Fire         |
 * | Lightning | Water          | Earth        |
 */
enum class Element {
    FIRE,
    WATER,
    EARTH,
    LIGHTNING;

    /** Base damage a successful (unmodified) attack of this element deals. */
    val baseDamage: Int
        get() = BASE_DAMAGE

    companion object {
        const val BASE_DAMAGE = 20

        /** Multiplier applied when the attacker's element is effective. */
        const val STRONG_MULTIPLIER = 1.5f

        /** Multiplier applied when the attacker's element is ineffective. */
        const val WEAK_MULTIPLIER = 0.5f

        /** Multiplier applied for neutral matchups. */
        const val NEUTRAL_MULTIPLIER = 1.0f
    }
}

/** Returns the element this one is strong against (deals extra damage to). */
fun Element.strongAgainst(): Element = when (this) {
    Element.FIRE -> Element.EARTH
    Element.WATER -> Element.FIRE
    Element.EARTH -> Element.LIGHTNING
    Element.LIGHTNING -> Element.WATER
}

/** Returns the element this one is weak against (deals reduced damage to). */
fun Element.weakAgainst(): Element = when (this) {
    Element.FIRE -> Element.WATER
    Element.WATER -> Element.LIGHTNING
    Element.EARTH -> Element.FIRE
    Element.LIGHTNING -> Element.EARTH
}

/**
 * Damage multiplier for an [attacker] element hitting a [target] element.
 * Strong matchups amplify, weak matchups dampen, everything else is neutral.
 */
fun elementModifier(attacker: Element, target: Element): Float = when (target) {
    attacker.strongAgainst() -> Element.STRONG_MULTIPLIER
    attacker.weakAgainst() -> Element.WEAK_MULTIPLIER
    else -> Element.NEUTRAL_MULTIPLIER
}
