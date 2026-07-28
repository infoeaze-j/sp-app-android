package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.device.DeviceBuildInfoProvider
import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.local.PrefsDataStore
import com.mediplus.spapp.data.remote.DeviceApi
import com.mediplus.spapp.data.remote.RegisterDeviceRequest
import com.mediplus.spapp.data.remote.asDeviceId
import com.mediplus.spapp.domain.model.CurrentAppVersion
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/**
 * Registers this install with the back office (`devices.register`) and remembers the id it returns,
 * so later calls can carry it as `X-Device-Id`.
 *
 * Best-effort by design: the id is an audit-trail convenience everywhere except diagnostics, so a
 * failure here must never block sign-in or the patient journey. Registration is idempotent on the
 * durable install id, which makes calling it again on every sign-in the cheapest correct policy —
 * it also refreshes the OS and app version the fleet has on record.
 */
interface DeviceRepository {
    suspend fun register(): AppResult<String>
}

class DeviceRepositoryImpl @Inject constructor(
    private val api: DeviceApi,
    private val prefs: PrefsDataStore,
    private val deviceIdStore: DeviceIdStore,
    private val buildInfo: DeviceBuildInfoProvider,
    private val currentVersion: CurrentAppVersion,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : DeviceRepository {

    override suspend fun register(): AppResult<String> =
        apiCall(dispatcher, { api.register(buildRequest()) }) { response ->
            val deviceId = response.body()?.asDeviceId()
            when {
                response.isSuccessful && deviceId != null -> {
                    deviceIdStore.set(deviceId)
                    AppResult.Success(deviceId)
                }
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                // Nothing here gates the journey, so every other outcome stays retryable.
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            }
        }

    private suspend fun buildRequest(): RegisterDeviceRequest {
        val build = buildInfo.get()
        return RegisterDeviceRequest(
            installId = prefs.installId(),
            manufacturer = build.manufacturer,
            model = build.model,
            osRelease = build.osRelease,
            sdkInt = build.sdkInt,
            appVersionName = currentVersion.name,
            appVersionCode = currentVersion.code,
        )
    }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}
