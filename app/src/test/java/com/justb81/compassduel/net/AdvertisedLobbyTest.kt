package com.justb81.compassduel.net

import com.justb81.compassduel.net.protocol.GameMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdvertisedLobbyTest {

    @Test
    fun `encode then decode round-trips name, mode and player count`() {
        val encoded = AdvertisedLobby.encode("Alice", GameMode.STANDARD, playerCount = 3)
        val decoded = AdvertisedLobby.decode(encoded)
        assertEquals("Alice", decoded.name)
        assertEquals(GameMode.STANDARD, decoded.mode)
        assertEquals(3, decoded.playerCount)
    }

    @Test
    fun `decode falls back to raw name for a plain (legacy) endpoint name`() {
        val decoded = AdvertisedLobby.decode("Bob")
        assertEquals("Bob", decoded.name)
        assertNull(decoded.mode)
        assertEquals(0, decoded.playerCount)
    }

    @Test
    fun `decode falls back when the mode token is unknown`() {
        val malformed = AdvertisedLobby.encode("Cara", GameMode.KIDS, 2).replace("KIDS", "BOGUS")
        val decoded = AdvertisedLobby.decode(malformed)
        assertNull(decoded.mode)
        assertEquals(0, decoded.playerCount)
    }

    @Test
    fun `kids mode round-trips`() {
        val decoded = AdvertisedLobby.decode(AdvertisedLobby.encode("Dan", GameMode.KIDS, 1))
        assertEquals("Dan", decoded.name)
        assertEquals(GameMode.KIDS, decoded.mode)
        assertEquals(1, decoded.playerCount)
    }
}
