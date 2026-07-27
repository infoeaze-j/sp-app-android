package com.mediplus.faceverify.dev.diagnostics

import com.mediplus.faceverify.core.diagnostics.AppInfo
import com.mediplus.faceverify.core.diagnostics.BatteryPlug
import com.mediplus.faceverify.core.diagnostics.BatteryState
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceInfo
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.diagnostics.DisplayState
import com.mediplus.faceverify.core.diagnostics.EnvironmentState
import com.mediplus.faceverify.core.diagnostics.MemoryState
import com.mediplus.faceverify.core.diagnostics.NetworkState
import com.mediplus.faceverify.core.diagnostics.NetworkTransport
import com.mediplus.faceverify.core.diagnostics.StorageState
import com.mediplus.faceverify.core.diagnostics.ThermalState
import com.mediplus.faceverify.core.diagnostics.UptimeState
import javax.inject.Inject

/** A fixed, unmistakably-fake snapshot for the dev stack. */
class FakeDeviceDiagnostics @Inject constructor() : DeviceDiagnostics {
    override suspend fun snapshot(): DeviceStateSnapshot = DeviceStateSnapshot(
        battery = BatteryState(
            levelPercent = 42,
            isCharging = false,
            plug = BatteryPlug.NONE,
            health = "GOOD",
            temperatureDeciC = 300,
            voltageMv = 3900,
            powerSaveMode = false,
        ),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(internalFreeBytes = 8_000_000_000L, internalTotalBytes = 64_000_000_000L),
        memory = MemoryState(availBytes = 2_000_000_000L, totalBytes = 6_000_000_000L, lowMemory = false),
        display = DisplayState(
            widthPx = 1080,
            heightPx = 2340,
            densityDpi = 440,
            refreshRateHz = 60f,
            rotationDegrees = 0,
        ),
        device = DeviceInfo("FakeCo", "Emulator", "generic", "emu", 34, "14"),
        app = AppInfo(versionName = "dev", versionCode = 0),
        environment = EnvironmentState("en-ZA", "Africa/Johannesburg", airplaneMode = false),
        thermal = ThermalState(headroom = 0.1f, status = 0),
        uptime = UptimeState(uptimeMillis = 123_456L, elapsedRealtimeMillis = 234_567L),
    )
}
