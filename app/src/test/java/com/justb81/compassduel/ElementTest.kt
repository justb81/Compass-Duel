package com.justb81.compassduel

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.elementModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ElementTest {

    @Test
    fun `strong matchup amplifies damage`() {
        assertEquals(Element.STRONG_MULTIPLIER, elementModifier(Element.FIRE, Element.EARTH))
        assertEquals(Element.STRONG_MULTIPLIER, elementModifier(Element.WATER, Element.FIRE))
        assertEquals(Element.STRONG_MULTIPLIER, elementModifier(Element.EARTH, Element.LIGHTNING))
        assertEquals(Element.STRONG_MULTIPLIER, elementModifier(Element.LIGHTNING, Element.WATER))
    }

    @Test
    fun `weak matchup dampens damage`() {
        assertEquals(Element.WEAK_MULTIPLIER, elementModifier(Element.FIRE, Element.WATER))
        assertEquals(Element.WEAK_MULTIPLIER, elementModifier(Element.WATER, Element.LIGHTNING))
        assertEquals(Element.WEAK_MULTIPLIER, elementModifier(Element.EARTH, Element.FIRE))
        assertEquals(Element.WEAK_MULTIPLIER, elementModifier(Element.LIGHTNING, Element.EARTH))
    }

    @Test
    fun `mirror matchup is neutral`() {
        Element.entries.forEach { element ->
            assertEquals(Element.NEUTRAL_MULTIPLIER, elementModifier(element, element))
        }
    }
}
