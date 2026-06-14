package com.justb81.compassduel.net

import com.justb81.compassduel.net.protocol.GameMode

/**
 * Encodes/decodes the small metadata the host puts into its Nearby Connections *endpoint name*
 * so clients can show the game type and connected-player count before connecting (#98).
 *
 * Nearby exposes only the advertised endpoint-name string during discovery, so the host packs
 * `name`, `mode` and `playerCount` into it with a reserved unit-separator delimiter (very
 * unlikely in a typed display name). [decode] falls back to treating the whole string as a plain
 * name (mode unknown, count 0) when the format does not match, so a legacy/plain name never
 * crashes parsing.
 */
object AdvertisedLobby {

    /** Unit Separator (U+001F) — reserved field delimiter, not typeable in a normal name. */
    private const val SEP = '\u001F'
    private const val FIELD_COUNT = 3

    /** Packs the host [name], [mode] and current [playerCount] into one advertised name string. */
    fun encode(name: String, mode: GameMode, playerCount: Int): String =
        "$name$SEP${mode.name}$SEP$playerCount"

    /**
     * Parses an advertised endpoint name produced by [encode]. Returns the decoded fields, or a
     * fallback with the raw string as the name and `mode = null`, `playerCount = 0` when the
     * string is not in the expected format.
     */
    fun decode(raw: String): Decoded {
        val parts = raw.split(SEP)
        if (parts.size != FIELD_COUNT) return Decoded(name = raw)
        val mode = runCatching { GameMode.valueOf(parts[1]) }.getOrNull()
        val count = parts[2].toIntOrNull()
        if (mode == null || count == null) return Decoded(name = raw)
        return Decoded(name = parts[0], mode = mode, playerCount = count)
    }

    /** Decoded advertised-lobby metadata. */
    data class Decoded(
        val name: String,
        val mode: GameMode? = null,
        val playerCount: Int = 0,
    )
}
