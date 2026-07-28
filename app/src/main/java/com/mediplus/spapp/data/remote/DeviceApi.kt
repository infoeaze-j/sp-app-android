package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Device registration (`devices.register` in docs/openapi.json). Idempotent on the
 * client-generated `installId`, so a reinstall, a token expiry or a crash updates the same record
 * and gets the same device id back rather than littering the fleet with duplicates.
 *
 * The returned id rides on later calls as `X-Device-Id`; diagnostics requires it, and everywhere
 * else it is recorded for the audit trail. Nothing here reads a hardware identifier — the install
 * id is a UUID the app generates and stores itself, which is why the app needs no permission.
 */
interface DeviceApi {

    @POST("devices/register")
    suspend fun register(@Body body: RegisterDeviceRequest): Response<JsonElement>
}

@Serializable
data class RegisterDeviceRequest(
    val installId: String,
    val label: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osRelease: String? = null,
    val sdkInt: Int? = null,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
)

/**
 * Reads the device id out of a registration response.
 *
 * The spec types this response as a bare `string`, but the same generator types the streamed APK
 * download as `object`, so a wrapper object is just as plausible on the wire. Both are read; a
 * shape neither matches yields null, which simply leaves `X-Device-Id` off the next request.
 */
fun JsonElement.asDeviceId(): String? = when {
    this is JsonPrimitive && this !is JsonNull -> content.takeIf { it.isNotBlank() }
    this is JsonObject -> DEVICE_ID_KEYS.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
    else -> null
}

private val DEVICE_ID_KEYS = listOf("deviceId", "id", "device_id")
