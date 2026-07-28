package com.mediplus.spapp.core.network

import com.mediplus.spapp.core.device.DeviceIdStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the registered device id as `X-Device-Id`. Diagnostics requires it; everywhere else the
 * back office records it for the audit trail when present, so a request made before registration
 * completes simply goes out without it rather than failing.
 *
 * The id is not an identity claim — authentication is the bearer token's job — and it carries no
 * hardware identifier, so it is safe to leave on every request.
 */
@Singleton
class DeviceIdInterceptor @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val deviceId = deviceIdStore.deviceId.value ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder().header(HEADER_DEVICE_ID, deviceId).build(),
        )
    }

    private companion object {
        const val HEADER_DEVICE_ID = "X-Device-Id"
    }
}
