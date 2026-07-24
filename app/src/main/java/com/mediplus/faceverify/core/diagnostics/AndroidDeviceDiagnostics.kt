package com.mediplus.faceverify.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import android.os.StatFs
import android.os.Environment

/**
 * The real [DeviceDiagnostics]. Device-gated like [com.mediplus.faceverify.core.nfc.NdefMemberCardReader]:
 * exercised on hardware/emulator, not in the JVM suite.
 */
class AndroidDeviceDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val currentVersion: CurrentAppVersion,
) : DeviceDiagnostics {

    override suspend fun snapshot(): DeviceStateSnapshot = withContext(dispatcher) {
        DeviceStateSnapshot(
            battery = buildBattery(),
            network = buildNetwork(),
            storage = buildStorage(),
            memory = buildMemory(),
            display = buildDisplay(),
            device = buildDevice(),
            app = AppInfo(currentVersion.name, currentVersion.code),
            environment = buildEnvironment(),
            thermal = buildThermal(),
            uptime = UptimeState(SystemClock.uptimeMillis(), SystemClock.elapsedRealtime()),
        )
    }

    private fun buildBattery(): BatteryState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return BatteryState(
            levelPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            isCharging = bm.isCharging,
            plug = plugOf(plugged),
            health = (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0).toString(),
            temperatureDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            powerSaveMode = pm.isPowerSaveMode,
        )
    }

    private fun plugOf(plugged: Int): BatteryPlug = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryPlug.AC
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryPlug.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPlug.WIRELESS
        else -> BatteryPlug.NONE
    }

    private fun buildNetwork(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return NetworkState(
            transport = transportOf(caps),
            isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    private fun transportOf(caps: NetworkCapabilities?): NetworkTransport = when {
        caps == null -> NetworkTransport.NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.NONE
    }

    private fun buildStorage(): StorageState {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageState(internalFreeBytes = stat.availableBytes, internalTotalBytes = stat.totalBytes)
    }

    private fun buildMemory(): MemoryState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return MemoryState(info.availMem, info.totalMem, info.lowMemory)
    }

    private fun buildDisplay(): DisplayState {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        val display = wm.defaultDisplay
        return DisplayState(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = display?.refreshRate ?: 0f,
            rotationDegrees = (display?.rotation ?: 0) * ROTATION_STEP_DEGREES,
        )
    }

    private fun buildDevice(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        brand = Build.BRAND,
        device = Build.DEVICE,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
    )

    private fun buildEnvironment(): EnvironmentState = EnvironmentState(
        locale = Locale.getDefault().toLanguageTag(),
        timeZoneId = TimeZone.getDefault().id,
        airplaneMode = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0,
        ) != 0,
    )

    // getThermalHeadroom is API 30 (R); currentThermalStatus is API 29 (Q). Guard each to its own
    // floor so Lint's NewApi check stays green (abortOnError=true).
    private fun buildThermal(): ThermalState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else null
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
        } else {
            null
        }
        return ThermalState(headroom = headroom, status = status)
    }

    private companion object {
        const val ROTATION_STEP_DEGREES = 90
        const val THERMAL_FORECAST_SECONDS = 10
    }
}
