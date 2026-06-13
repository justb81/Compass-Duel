package com.justb81.compassduel.net.protocol

import com.justb81.compassduel.game.Element
import com.justb81.compassduel.game.kids.KidsAward
import com.justb81.compassduel.game.kids.KidsRoundStats
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Supporting types
// ---------------------------------------------------------------------------

/** The game mode chosen by the host in the lobby. */
@Serializable
enum class GameMode {
    /** Standard "Elemental Duel" — elements, HP, attack/shield, best-of-3. */
    STANDARD,

    /** Kids Mode "Star Catchers" — sparkles, stars, magic bubble, awards. */
    KIDS,
}

/**
 * Discrete player action sent from client to host within a [PlayerInput] message.
 *
 * [SPECIAL] is reserved in the schema for the double-shake attack but is not
 * yet implemented — the host treats it as [IDLE] in v1.
 */
@Serializable
enum class PlayerAction {
    IDLE,
    SHIELD,
    ATTACK,

    /** Reserved for future double-shake special attack; treated as [IDLE] in v1. */
    SPECIAL,
}

/**
 * Phase of the current round, broadcast in every [GameSnapshot].
 *
 * - [COUNTDOWN]: short "get ready" window at round start (bearings were already
 *   captured in the lobby via the greeting handshake).
 * - [PLAYING]: active combat / star-catching phase.
 * - [ROUND_OVER]: round has ended; result is available.
 */
@Serializable
enum class RoundPhase {
    COUNTDOWN,
    PLAYING,
    ROUND_OVER,
}

/**
 * Status of a single player as reported in a [PlayerSnapshot].
 *
 * [RESTING] is Kids Mode only; [ELIMINATED] is Standard Mode only.
 */
@Serializable
enum class PlayerStatus {
    IDLE,
    SHIELDING,
    ATTACKING,

    /** Kids Mode: player is in the post-catch rest window. */
    RESTING,

    /** Standard Mode: player HP reached zero. */
    ELIMINATED,

    /** Player forfeited the current round after physically leaving their seat. */
    FORFEITED,
}

/** The type of a discrete in-round event emitted by the host. */
@Serializable
enum class GameEventType {
    /** Standard: an attack landed and dealt damage. */
    HIT,

    /** Standard: an attack was fully blocked by the target's shield. */
    BLOCKED,

    /** Standard or Kids: an attack/toss was outside the aim cone. */
    MISS,

    /** Standard: a player's HP reached zero. */
    ELIMINATED,

    /** Kids: a sparkle toss landed; catcher earns stars. */
    CAUGHT,

    /** Kids: a magic bubble blocked a sparkle; defender earns a star. */
    BUBBLED,

    /** Kids: the post-catch rest window for a player has expired. */
    REST_OVER,

    /** A player moved beyond the in-seat tolerance — non-blocking warning. */
    MOVED_WARNING,

    /** A player moved enough to forfeit the round; their bearings are invalidated. */
    MOVED_FORFEIT,
}

/**
 * A player entry in the lobby state.
 *
 * @param id Player id (1 = host; 2–4 = clients in join order).
 * @param name Display name chosen in the home screen.
 * @param outgoingBearings Absolute bearings (`targetId → degrees [0, 360)`) this player
 *   has captured by bowing at each opponent during the greeting handshake.
 * @param element Chosen element (Standard Mode), or null if not yet chosen / Kids Mode.
 * @param spriteId Chosen sprite index (Kids Mode), or null if not yet chosen / Standard Mode.
 * @param ready True when the player has chosen a character and greeted everyone required.
 */
@Serializable
data class LobbyPlayer(
    val id: Int,
    val name: String,
    val outgoingBearings: Map<Int, Float> = emptyMap(),
    val element: Element? = null,
    val spriteId: Int? = null,
    val ready: Boolean = false,
)

/**
 * Host-side snapshot of one player during a round.
 *
 * @param id Player id.
 * @param hp Current hit points (Standard Mode only; 0 in Kids Mode).
 * @param stars Current star count (Kids Mode only; 0 in Standard Mode).
 * @param status Current player status.
 * @param targetId Id of the opponent this player is currently aiming at,
 *   or null when off-target. Used by clients to display the warning indicator.
 * @param restingUntilMillis Epoch millis until which the player is resting
 *   (Kids Mode only; 0 otherwise).
 * @param shieldRemainingMillis Remaining shield-time budget in millis
 *   (Standard Mode only; 0 otherwise). The per-round budget is
 *   [com.justb81.compassduel.game.standard.StandardRules.SHIELD_BUDGET_MILLIS].
 * @param movementWarning True when the player has moved past the in-seat tolerance but
 *   not yet enough to forfeit (drives a "stay in your seat" warning).
 */
@Serializable
data class PlayerSnapshot(
    val id: Int,
    val hp: Int = 0,
    val stars: Int = 0,
    val status: PlayerStatus = PlayerStatus.IDLE,
    val targetId: Int? = null,
    val restingUntilMillis: Long = 0L,
    val shieldRemainingMillis: Long = 0L,
    val movementWarning: Boolean = false,
)

/**
 * A discrete in-round event emitted by the host and included in [GameSnapshot].
 *
 * @param type The category of the event.
 * @param actorId The player who initiated the action.
 * @param targetId The player who was affected, or null for events without a target.
 * @param amount Damage dealt or stars awarded, depending on event type; 0 when not applicable.
 */
@Serializable
data class GameEvent(
    val type: GameEventType,
    val actorId: Int,
    val targetId: Int? = null,
    val amount: Int = 0,
)

/**
 * Full authoritative game state broadcast by the host every tick (~100 ms).
 *
 * @param seq Monotonically increasing sequence number; clients may detect dropped frames.
 * @param phase Current round phase.
 * @param remainingMillis Milliseconds remaining in the current round (countdown or playing).
 * @param players Snapshot of every player's current state.
 * @param events Discrete events that occurred since the previous tick.
 */
@Serializable
data class GameSnapshot(
    val seq: Int,
    val phase: RoundPhase,
    val remainingMillis: Long,
    val players: List<PlayerSnapshot>,
    val events: List<GameEvent> = emptyList(),
)

// ---------------------------------------------------------------------------
// Sealed NetMessage hierarchy
// ---------------------------------------------------------------------------

/**
 * Sealed hierarchy of all messages exchanged over the Nearby Connections transport.
 *
 * Each subtype is tagged with a `@SerialName` discriminator that appears in the
 * encoded JSON as the `"type"` field (see [MessageCodec]).
 * No version negotiation or legacy fallbacks — per repo convention.
 */
@Serializable
sealed interface NetMessage {

    // -------------------------------------------------------------------------
    // Lobby: client → host
    // -------------------------------------------------------------------------

    /**
     * First message a client sends after connecting.
     *
     * @param playerName Display name for this player.
     */
    @Serializable
    @SerialName("ClientHello")
    data class ClientHello(val playerName: String) : NetMessage

    /**
     * Client has bowed at another player during the greeting handshake, capturing the
     * absolute bearing from this player toward [toPlayerId].
     *
     * @param fromPlayerId The bowing player's id (informational; the host authoritatively
     *   maps the sending endpoint to a player id).
     * @param toPlayerId The greeted player's id.
     * @param bearingDegrees Raw absolute azimuth in `[0, 360)` captured at bow onset.
     */
    @Serializable
    @SerialName("Greeting")
    data class Greeting(
        val fromPlayerId: Int,
        val toPlayerId: Int,
        val bearingDegrees: Float,
    ) : NetMessage

    /**
     * Client reports physical movement detected during the round (step detector /
     * significant-motion trigger). Sparse — sent only when movement is detected, not on
     * the 100 ms input cadence.
     *
     * @param playerId The moving player's id.
     * @param stepDelta Steps detected since the previous report (0 for a significant-motion
     *   trigger that carries no step count).
     * @param significant True when the platform reported a significant-motion event.
     */
    @Serializable
    @SerialName("PlayerMoved")
    data class PlayerMoved(
        val playerId: Int,
        val stepDelta: Int = 0,
        val significant: Boolean = false,
    ) : NetMessage

    /**
     * Client has picked their character (element or sprite).
     *
     * @param element Chosen element for Standard Mode, or null.
     * @param spriteId Chosen sprite index for Kids Mode, or null.
     */
    @Serializable
    @SerialName("CharacterChosen")
    data class CharacterChosen(
        val element: Element? = null,
        val spriteId: Int? = null,
    ) : NetMessage

    // -------------------------------------------------------------------------
    // Lobby: host → clients
    // -------------------------------------------------------------------------

    /**
     * Current lobby state broadcast by the host whenever anything changes.
     *
     * @param mode The game mode the host has selected.
     * @param players All players currently in the lobby.
     * @param yourPlayerId The recipient's own player id (assigned by the host).
     * @param yourBearings The recipient's own captured bearings (`targetId → degrees`),
     *   so the client can render opponent dots on its compass ring.
     */
    @Serializable
    @SerialName("LobbyState")
    data class LobbyState(
        val mode: GameMode,
        val players: List<LobbyPlayer>,
        val yourPlayerId: Int,
        val yourBearings: Map<Int, Float> = emptyMap(),
    ) : NetMessage

    /**
     * Host has pressed "Start" — clients should transition to the game screen.
     *
     * @param mode The chosen game mode for this match.
     * @param roundIndex Zero-based round index (0 = first round).
     * @param roundDurationSeconds How long the active phase lasts.
     * @param players Final player list.
     * @param bearings The full greeting bearing matrix (`actorId → (targetId → degrees)`).
     *   Each client looks up its own row by its [LobbyState.yourPlayerId] to render
     *   opponent dots.
     */
    @Serializable
    @SerialName("RoundStart")
    data class RoundStart(
        val mode: GameMode,
        val roundIndex: Int,
        val roundDurationSeconds: Int,
        val players: List<LobbyPlayer>,
        val bearings: Map<Int, Map<Int, Float>> = emptyMap(),
    ) : NetMessage

    // -------------------------------------------------------------------------
    // In-round: client → host
    // -------------------------------------------------------------------------

    /**
     * Continuous input payload sent by each client every 100 ms (and immediately on
     * an ATTACK action).
     *
     * @param playerId The sender's player id.
     * @param aimDegrees Raw absolute aim azimuth (magnetic north) in degrees [0, 360).
     * @param pitchDegrees Current device pitch in degrees.
     * @param action The player's current discrete action.
     * @param clientTimeMillis Client-side epoch millis at capture time (diagnostics only —
     *   host does not use this for timing decisions; see CLAUDE.md deviations).
     */
    @Serializable
    @SerialName("PlayerInput")
    data class PlayerInput(
        val playerId: Int,
        val aimDegrees: Float,
        val pitchDegrees: Float,
        val action: PlayerAction = PlayerAction.IDLE,
        val clientTimeMillis: Long = 0L,
    ) : NetMessage

    // -------------------------------------------------------------------------
    // In-round: host → clients
    // -------------------------------------------------------------------------

    /**
     * Authoritative game state broadcast by the host each tick (~100 ms).
     *
     * @param snapshot The complete round state at this tick.
     */
    @Serializable
    @SerialName("StateBroadcast")
    data class StateBroadcast(val snapshot: GameSnapshot) : NetMessage

    // -------------------------------------------------------------------------
    // End of round: host → clients
    // -------------------------------------------------------------------------

    /**
     * Sent by the host when the round phase transitions to [RoundPhase.ROUND_OVER].
     *
     * @param roundWinnerId Id of the round winner, or null for a draw.
     * @param matchScore Current match-level round-win counts (player id → wins).
     * @param matchWinnerId Id of the match winner once someone reaches [com.justb81.compassduel.game.standard.StandardRules.ROUNDS_TO_WIN], or null.
     * @param kidsAwards End-of-round award per player (Kids Mode only).
     * @param kidsStats Per-player counters for the round (Kids Mode only).
     */
    @Serializable
    @SerialName("RoundEnd")
    data class RoundEnd(
        val roundWinnerId: Int? = null,
        val matchScore: Map<Int, Int> = emptyMap(),
        val matchWinnerId: Int? = null,
        val kidsAwards: Map<Int, KidsAward>? = null,
        val kidsStats: List<KidsRoundStats>? = null,
    ) : NetMessage

    /**
     * Sent by the host to signal a rematch is starting; clients reset their UI.
     */
    @Serializable
    @SerialName("Rematch")
    data object Rematch : NetMessage

    /**
     * Sent by the host mid-match when a player forfeited by leaving their seat: clients
     * return to the lobby greeting view to re-bow before the next round. Match score is
     * preserved (unlike [Rematch]).
     */
    @Serializable
    @SerialName("Regreet")
    data object Regreet : NetMessage
}
