package com.justb81.compassduel.net.protocol

import kotlinx.serialization.json.Json

/**
 * Codec for encoding and decoding [NetMessage] instances to and from raw bytes.
 *
 * Uses [kotlinx.serialization] JSON with a class discriminator field `"type"` to
 * distinguish [NetMessage] subtypes in the sealed hierarchy.
 *
 * Design notes:
 * - `encodeDefaults = false` keeps payloads compact by omitting optional fields
 *   that carry their default value (null, empty lists, zeros).
 * - The JSON format is the v1 wire format; no version negotiation or legacy
 *   fallbacks are included (per repo convention).
 * - If binary encoding becomes necessary (e.g. size budget exceeded at scale),
 *   the escape hatch is to swap the [Json] instance here for a CBOR codec without
 *   touching callers.
 */
object MessageCodec {

    /** Shared [Json] instance. The class discriminator field is `"type"`. */
    val json: Json = Json {
        encodeDefaults = false
        classDiscriminator = "type"
    }

    /**
     * Encodes [message] to a UTF-8 JSON byte array suitable for transmission
     * via Nearby Connections `Payload.fromBytes`.
     */
    fun encode(message: NetMessage): ByteArray =
        json.encodeToString(NetMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    /**
     * Decodes a [NetMessage] from [bytes] produced by [encode].
     *
     * @throws kotlinx.serialization.SerializationException if the bytes do not
     *   represent a valid [NetMessage].
     */
    fun decode(bytes: ByteArray): NetMessage =
        json.decodeFromString(NetMessage.serializer(), bytes.toString(Charsets.UTF_8))
}
