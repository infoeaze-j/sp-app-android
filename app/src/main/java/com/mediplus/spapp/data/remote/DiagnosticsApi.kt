package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Device diagnostics telemetry: poll for a request, then report a snapshot
 * (docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md).
 *
 * App-invented placeholder — reconcile with the back office when it publishes its shape. Both calls
 * are authenticated; the bearer token rides via AuthInterceptor. DTOs never leave this package.
 */
interface DiagnosticsApi {

    @GET("diagnostics/poll")
    suspend fun poll(): Response<DiagnosticsPollResponse>

    @POST("diagnostics")
    suspend fun report(@Body body: DiagnosticsReport): Response<Unit>
}

@Serializable
data class DiagnosticsPollResponse(val requestId: String)

@Serializable
data class DiagnosticsReport(val requestId: String, val snapshot: DeviceSnapshotDto)

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
