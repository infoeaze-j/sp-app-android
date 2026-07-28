package com.mediplus.spapp.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import retrofit2.Response

/**
 * Shared readers for the parts of `docs/openapi.json` whose declared types are generator output
 * rather than a hand-written contract — `GET /app/releases/{release}/binary` is typed `object` for
 * what is an APK byte stream, so a declared type alone is not something the client can bet on.
 *
 * Everything here fails towards the safe answer instead of throwing, so a shape the back office
 * spells differently degrades to "unknown" rather than to a transport failure.
 */

/** Lenient JSON reader for error envelopes; failure bodies are diagnostic, never rendered. */
private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Reads a boolean the back office may spell as a JSON boolean, a string or a number.
 *
 * The spec types three plainly boolean fields as `string` — `capabilities.canVerifyFace` (right
 * beside `canEnroll: boolean`), `updateRequired` and `updateAvailable`. Anything unrecognised reads
 * as `false`, which is the fail-safe answer for all three: no capability, no forced update.
 */
internal object LenientBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val primitive = json.decodeJsonElement() as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: (primitive.content.lowercase() in TRUTHY)
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    private val TRUTHY = setOf("true", "1", "yes")
}

/**
 * The failure envelope. The spec's preamble states that every failure carries a stable
 * `error.code` and that clients branch on it — "never on prose, and never on the HTTP status
 * alone" — while its generated components also describe Laravel's `{message, errors}` validation
 * body. Both are accepted; the code is what callers branch on when it is present.
 */
@Serializable
internal data class ApiErrorEnvelope(
    val error: ApiErrorDto? = null,
    val message: String? = null,
)

@Serializable
internal data class ApiErrorDto(
    val code: String = "",
    val message: String? = null,
)

/**
 * The stable `error.code` from a failure body, or null when the response carries none. Diagnostic
 * only: it selects which fixed [com.mediplus.spapp.core.result.UiMessage] is shown and is never
 * rendered itself (FR-029).
 */
internal fun Response<*>.apiErrorCode(): String? = runCatching {
    errorBody()?.string()
        ?.takeIf { it.isNotBlank() }
        ?.let { wireJson.decodeFromString(ApiErrorEnvelope.serializer(), it) }
        ?.error
        ?.code
        ?.takeIf { it.isNotBlank() }
}.getOrNull()

/** Error codes the spec names explicitly. Anything else falls back to the status-code mapping. */
internal object ApiErrorCodes {
    const val SESSION_INVALID = "SESSION_INVALID"
    const val VERIFICATION_STALE = "VERIFICATION_STALE"
}
