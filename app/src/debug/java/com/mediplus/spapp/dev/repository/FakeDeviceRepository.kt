package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.DeviceRepository
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeData
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Fake device registration: hands back a fixed id and records it, so the rest of the debug journey
 * carries an `X-Device-Id` exactly as a registered device would.
 *
 * No scenario enum: registration is best-effort everywhere it is used, so a fake failure would
 * change nothing observable. Turn the [com.mediplus.spapp.dev.FakeSeam.DEVICE] toggle off to
 * register against the real back office instead.
 */
class FakeDeviceRepository @Inject constructor(
    private val store: DevSettingsStore,
    private val deviceIdStore: DeviceIdStore,
) : DeviceRepository {

    override suspend fun register(): AppResult<String> {
        delay(store.current().latencyMillis)
        deviceIdStore.set(FakeData.deviceId)
        return AppResult.Success(FakeData.deviceId)
    }
}
