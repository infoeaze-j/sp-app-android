package com.mediplus.spapp.core.device

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The build facts device registration reports. Behind a seam like every other device dependency, so
 * no `android.os.Build` reference reaches a repository — which is also what lets the registration
 * path be unit-tested off-device.
 *
 * Every field here is readable without a runtime permission and none of them identifies a handset:
 * a model name is shared by every unit of that model. The identifier the fleet is keyed on is the
 * app's own [DeviceIdStore] install id, never a hardware one.
 */
data class DeviceBuildInfo(
    val manufacturer: String,
    val model: String,
    val osRelease: String,
    val sdkInt: Int,
)

fun interface DeviceBuildInfoProvider {
    fun get(): DeviceBuildInfo
}

@Singleton
class AndroidDeviceBuildInfoProvider @Inject constructor() : DeviceBuildInfoProvider {
    override fun get(): DeviceBuildInfo = DeviceBuildInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        osRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
    )
}

/**
 * The device id `POST /devices/register` returned, held in memory for the life of the process and
 * read by [com.mediplus.spapp.core.network.DeviceIdInterceptor].
 *
 * Deliberately not persisted: it is derived from the durable install id, registration is idempotent
 * on that id, and re-registering costs one call at sign-in. Until it is set, requests simply go out
 * without the header — which the spec allows everywhere except diagnostics.
 */
@Singleton
class DeviceIdStore @Inject constructor() {

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    fun set(id: String?) {
        _deviceId.value = id?.takeIf { it.isNotBlank() }
    }
}
