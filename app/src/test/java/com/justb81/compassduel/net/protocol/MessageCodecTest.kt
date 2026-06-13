package com.justb81.compassduel.net.protocol

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageCodecTest {

    // ---------------------------------------------------------------------------
    // Round-trip helpers
    // ---------------------------------------------------------------------------

    private fun assertRoundTrip(message: NetMessage): ByteArray {
        val encoded = MessageCodec.encode(message)
        val decoded = MessageCodec.decode(encoded)
        assertEquals(message, decoded, "Round-trip mismatch for ${message::class.simpleName}")
        return encoded
    }

    // ---------------------------------------------------------------------------
    // Lobby: client → host
    // ---------------------------------------------------------------------------

    @Test
    fun `ClientHello round-trips correctly`() {
        assertRoundTrip(NetMessage.ClientHello(playerName = "Alice"))
    }

    @Test
    fun `SeatChosen round-trips correctly`() {
        assertRoundTrip(NetMessage.SeatChosen(cell = 4))
    }

    @Test
    fun `CharacterChosen with element round-trips correctly`() {
        assertRoundTrip(NetMessage.CharacterChosen(element = Element.FIRE))
    }

    @Test
    fun `CharacterChosen with spriteId round-trips correctly`() {
        assertRoundTrip(NetMessage.CharacterChosen(spriteId = 2))
    }

    @Test
    fun `CharacterChosen with no selection round-trips correctly`() {
        assertRoundTrip(NetMessage.CharacterChosen())
    }

    // ---------------------------------------------------------------------------
    // Lobby: host → clients
    // ---------------------------------------------------------------------------

    @Test
    fun `LobbyState round-trips correctly`() {
        val message = NetMessage.LobbyState(
            mode = GameMode.STANDARD,
            players = listOf(
                LobbyPlayer(id = 1, name = "Host", seatCell = 0, element = Element.FIRE, ready = true),
                LobbyPlayer(id = 2, name = "Client", ready = false),
            ),
            yourPlayerId = 2,
        )
        assertRoundTrip(message)
    }

    @Test
    fun `RoundStart round-trips correctly`() {
        val message = NetMessage.RoundStart(
            mode = GameMode.KIDS,
            roundIndex = 0,
            roundDurationSeconds = 60,
            players = listOf(LobbyPlayer(id = 1, name = "Host", seatCell = 1, ready = true)),
            facingCaptureSeconds = 3,
        )
        assertRoundTrip(message)
    }

    // ---------------------------------------------------------------------------
    // In-round: client → host
    // ---------------------------------------------------------------------------

    @Test
    fun `PlayerInput round-trips correctly`() {
        val message = NetMessage.PlayerInput(
            playerId = 2,
            aimDegrees = 187.3f,
            pitchDegrees = 32.1f,
            action = PlayerAction.ATTACK,
            clientTimeMillis = 1_718_124_523_445L,
        )
        assertRoundTrip(message)
    }

    @Test
    fun `PlayerInput IDLE with defaults round-trips correctly`() {
        assertRoundTrip(
            NetMessage.PlayerInput(
                playerId = 1,
                aimDegrees = 0f,
                pitchDegrees = 5f,
            )
        )
    }

    // ---------------------------------------------------------------------------
    // In-round: host → clients
    // ---------------------------------------------------------------------------

    @Test
    fun `StateBroadcast round-trips correctly`() {
        val snapshot = GameSnapshot(
            seq = 4821,
            phase = RoundPhase.PLAYING,
            remainingMillis = 60_000L,
            players = listOf(
                PlayerSnapshot(id = 1, hp = 85, status = PlayerStatus.SHIELDING, shieldRemainingMillis = 22_500L),
                PlayerSnapshot(id = 2, hp = 60, status = PlayerStatus.ATTACKING, targetId = 3),
                PlayerSnapshot(id = 3, hp = 100, status = PlayerStatus.IDLE),
            ),
            events = listOf(
                GameEvent(type = GameEventType.HIT, actorId = 2, targetId = 3, amount = 15),
                GameEvent(type = GameEventType.MISS, actorId = 1, targetId = 2),
            ),
        )
        assertRoundTrip(NetMessage.StateBroadcast(snapshot))
    }

    // ---------------------------------------------------------------------------
    // End of round
    // ---------------------------------------------------------------------------

    @Test
    fun `RoundEnd standard with winner round-trips correctly`() {
        assertRoundTrip(
            NetMessage.RoundEnd(
                roundWinnerId = 1,
                matchScore = mapOf(1 to 2),
                matchWinnerId = 1,
            )
        )
    }

    @Test
    fun `RoundEnd kids with awards round-trips correctly`() {
        assertRoundTrip(
            NetMessage.RoundEnd(
                kidsAwards = mapOf(
                    1 to KidsAward.STAR_CHAMPION,
                    2 to KidsAward.BUBBLE_HERO,
                    3 to KidsAward.BUSY_BEE,
                    4 to KidsAward.SUPER_SPARKLER,
                ),
                kidsStats = listOf(
                    KidsRoundStats(playerId = 1, stars = 5, bubbleBlocks = 0, sparklesThrown = 8),
                    KidsRoundStats(playerId = 2, stars = 2, bubbleBlocks = 3, sparklesThrown = 6),
                ),
            )
        )
    }

    @Test
    fun `RoundEnd draw (null winner) round-trips correctly`() {
        assertRoundTrip(NetMessage.RoundEnd(roundWinnerId = null, matchScore = mapOf(1 to 1, 2 to 1)))
    }

    @Test
    fun `Rematch round-trips correctly`() {
        assertRoundTrip(NetMessage.Rematch)
    }

    // ---------------------------------------------------------------------------
    // Size budget assertions
    // ---------------------------------------------------------------------------

    @Test
    fun `PlayerInput encoded size is under 200 bytes`() {
        val message = NetMessage.PlayerInput(
            playerId = 2,
            aimDegrees = 187.3f,
            pitchDegrees = 32.1f,
            action = PlayerAction.ATTACK,
            clientTimeMillis = 1_718_124_523_445L,
        )
        val encoded = MessageCodec.encode(message)
        assertTrue(encoded.size < 200) {
            "PlayerInput encoded to ${encoded.size} bytes, expected < 200"
        }
    }

    @Test
    fun `four-player StateBroadcast encoded size is under 1000 bytes`() {
        val snapshot = GameSnapshot(
            seq = 9999,
            phase = RoundPhase.PLAYING,
            remainingMillis = 45_000L,
            players = listOf(
                PlayerSnapshot(id = 1, hp = 100, status = PlayerStatus.IDLE),
                PlayerSnapshot(id = 2, hp = 75, status = PlayerStatus.ATTACKING, targetId = 3),
                PlayerSnapshot(id = 3, hp = 50, status = PlayerStatus.IDLE),
                PlayerSnapshot(id = 4, hp = 25, status = PlayerStatus.SHIELDING, shieldRemainingMillis = 30_000L),
            ),
            events = listOf(
                GameEvent(type = GameEventType.HIT, actorId = 2, targetId = 3, amount = 20),
                GameEvent(type = GameEventType.BLOCKED, actorId = 1, targetId = 4),
            ),
        )
        val encoded = MessageCodec.encode(NetMessage.StateBroadcast(snapshot))
        assertTrue(encoded.size < 1000) {
            "4-player StateBroadcast encoded to ${encoded.size} bytes, expected < 1000"
        }
    }
}
