package com.mediplus.faceverify.core.diagnostics

/**
 * A point-in-time, non-identifying snapshot of device state, collected only when the back office
 * asks for it (docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md).
 *
 * Every field here is readable without a runtime-permission grant. Deliberately excludes anything
 * identifying or permission-gated (IMEI/serial/MAC/ANDROID_ID, location, cellular generation) — this
 * is low-entropy device *state*, never a device fingerprint, per the constitution's non-revealing rule.
 */
data class DeviceStateSnapshot(
    val battery: BatteryState,
    val network: NetworkState,
    val storage: StorageState,
    val memory: MemoryState,
    val display: DisplayState,
    val device: DeviceInfo,
    val app: AppInfo,
    val environment: EnvironmentState,
    val thermal: ThermalState,
    val uptime: UptimeState,
)

data class BatteryState(
    val levelPercent: Int,
    val isCharging: Boolean,
    val plug: BatteryPlug,
    val health: String,
    val temperatureDeciC: Int,
    val voltageMv: Int,
    val powerSaveMode: Boolean,
)

enum class BatteryPlug { AC, USB, WIRELESS, NONE }

data class NetworkState(
    val transport: NetworkTransport,
    val isMetered: Boolean,
    val isValidated: Boolean,
)

enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, VPN, NONE }

data class StorageState(val internalFreeBytes: Long, val internalTotalBytes: Long)

data class MemoryState(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)

data class DisplayState(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val rotationDegrees: Int,
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
)

data class AppInfo(val versionName: String, val versionCode: Int)

data class EnvironmentState(val locale: String, val timeZoneId: String, val airplaneMode: Boolean)

/** [headroom] is null below API 30, [status] below API 29 (each unavailable there). */
data class ThermalState(val headroom: Float?, val status: Int?)

data class UptimeState(val uptimeMillis: Long, val elapsedRealtimeMillis: Long)
