package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Device diagnostics telemetry — `diagnostics.pending` and `diagnostics.report` in
 * docs/openapi.json (design:
 * docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md).
 *
 * Both calls are authenticated (the bearer token rides via
 * [com.mediplus.spapp.core.network.AuthInterceptor]) and both require the registered device id,
 * which [com.mediplus.spapp.core.network.DeviceIdInterceptor] attaches as `X-Device-Id`. The poll
 * always answers 200: "nothing pending" is the normal answer and must not look like a routing
 * failure, since the client swallows poll failures silently. Reporting is idempotent, so a device
 * that retries because it never saw the 202 creates no second row. DTOs never leave this package.
 */
interface DiagnosticsApi {

    @GET("diagnostics/requests/pending")
    suspend fun pending(): Response<PendingDiagnosticsResponse>

    @POST("diagnostics/requests/{diagnosticsRequest}/report")
    suspend fun report(
        @Path("diagnosticsRequest") requestId: String,
        @Body body: ReportDiagnosticsRequest,
    ): Response<Unit>
}

@Serializable
data class PendingDiagnosticsResponse(val request: DiagnosticsRequestDto? = null)

@Serializable
data class DiagnosticsRequestDto(
    val id: String,
    val reason: String? = null,
    val requestedAt: String? = null,
    val expiresAt: String? = null,
)

/**
 * The report body. `reportedAt` is optional in the spec and deliberately left unset: the only
 * clock the app injects is monotonic (freshness), so the server's own receipt timestamp is the
 * more trustworthy record of when a snapshot arrived.
 */
@Serializable
data class ReportDiagnosticsRequest(val snapshot: DeviceSnapshotDto)

@Serializable
data class DeviceSnapshotDto(
    val battery: BatteryDto,
    val network: NetworkDto,
    val storage: StorageDto,
    val memory: MemoryDto,
    val display: DisplayDto,
    val device: DeviceDto,
    val app: AppDto,
    val environment: EnvironmentDto,
    val thermal: ThermalDto,
    val uptime: UptimeDto,
)

@Serializable
data class BatteryDto(
    val levelPercent: Int,
    val isCharging: Boolean,
    val plug: String,
    val health: String,
    val temperatureDeciC: Int,
    val voltageMv: Int,
    val powerSaveMode: Boolean,
)

@Serializable
data class NetworkDto(val transport: String, val isMetered: Boolean, val isValidated: Boolean)

@Serializable
data class StorageDto(val internalFreeBytes: Long, val internalTotalBytes: Long)

@Serializable
data class MemoryDto(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)

@Serializable
data class DisplayDto(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val rotationDegrees: Int,
)

@Serializable
data class DeviceDto(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
)

@Serializable
data class AppDto(val versionName: String, val versionCode: Int)

@Serializable
data class EnvironmentDto(val locale: String, val timeZoneId: String, val airplaneMode: Boolean)

@Serializable
data class ThermalDto(val headroom: Float?, val status: Int?)

@Serializable
data class UptimeDto(val uptimeMillis: Long, val elapsedRealtimeMillis: Long)

/** Domain snapshot → wire DTO. Kept in this package so DTOs never leak upward. */
fun DeviceStateSnapshot.toDto(): DeviceSnapshotDto = DeviceSnapshotDto(
    battery = BatteryDto(
        battery.levelPercent, battery.isCharging, battery.plug.name, battery.health,
        battery.temperatureDeciC, battery.voltageMv, battery.powerSaveMode,
    ),
    network = NetworkDto(network.transport.name, network.isMetered, network.isValidated),
    storage = StorageDto(storage.internalFreeBytes, storage.internalTotalBytes),
    memory = MemoryDto(memory.availBytes, memory.totalBytes, memory.lowMemory),
    display = DisplayDto(
        display.widthPx, display.heightPx, display.densityDpi, display.refreshRateHz, display.rotationDegrees,
    ),
    device = DeviceDto(
        device.manufacturer, device.model, device.brand, device.device, device.sdkInt, device.release,
    ),
    app = AppDto(app.versionName, app.versionCode),
    environment = EnvironmentDto(environment.locale, environment.timeZoneId, environment.airplaneMode),
    thermal = ThermalDto(thermal.headroom, thermal.status),
    uptime = UptimeDto(uptime.uptimeMillis, uptime.elapsedRealtimeMillis),
)
