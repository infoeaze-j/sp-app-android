package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.network.DeviceIdInterceptor
import com.mediplus.spapp.core.session.InMemorySessionManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The transport rules that hold for every endpoint, pinned on the client [NetworkModule] actually
 * builds rather than on a lookalike assembled in a test.
 *
 * The one under test here is redirects. Every request this app makes is aimed at an origin the
 * client chose — `BuildConfig.BASE_URL`, or an `apkUrl` `CheckForUpdateUseCase` has already checked
 * against it. A 30x hands that choice back to the response, after the check, which is exactly the
 * judgement the same-origin rule exists to keep away from the server.
 */
class NetworkModuleTest {

    private lateinit var origin: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private val deviceIdStore = DeviceIdStore()

    @Before
    fun setUp() {
        origin = MockWebServer()
        origin.start()
        elsewhere = MockWebServer()
        elsewhere.start()
    }

    @After
    fun tearDown() {
        origin.shutdown()
        elsewhere.shutdown()
    }

    private fun client(): OkHttpClient = NetworkModule.provideOkHttpClient(
        AuthInterceptor(InMemorySessionManager()),
        DeviceIdInterceptor(deviceIdStore),
        NetworkModule.provideLoggingInterceptor(),
    )

    private fun redirectToElsewhere() {
        origin.enqueue(
            MockResponse().setResponseCode(HTTP_FOUND)
                .setHeader("Location", elsewhere.url("/anyones.apk").toString()),
        )
        elsewhere.enqueue(MockResponse().setResponseCode(200).setBody("substituted"))
    }

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(origin.url("/app/releases/7/binary")).build()).execute()

    @Test
    fun `the client is configured not to follow redirects`() {
        assertFalse("a redirect moves a request off the origin the client chose", client().followRedirects)
    }

    @Test
    fun `a redirect is surfaced as a plain response instead of being followed`() {
        redirectToElsewhere()

        val response = get(client()).use { it.code }

        assertEquals(HTTP_FOUND, response)
        assertEquals("the redirect target must never be contacted", 0, elsewhere.requestCount)
    }

    @Test
    fun `a redirect cannot carry the device id to another host`() {
        deviceIdStore.set(DEVICE_ID)
        redirectToElsewhere()

        get(client()).use { it.code }

        assertEquals(DEVICE_ID, origin.takeRequest().getHeader(HEADER_DEVICE_ID))
        assertEquals("nothing about this device may leave its own origin", 0, elsewhere.requestCount)
    }

    @Test
    fun `a redirect-following client would hand the device id to the redirect target`() {
        // Not production behaviour — this pins WHY the setting above is load-bearing rather than
        // tidy. OkHttp strips only Authorization across hosts, so X-Device-Id rides along.
        deviceIdStore.set(DEVICE_ID)
        redirectToElsewhere()
        val permissive = OkHttpClient.Builder()
            .addInterceptor(DeviceIdInterceptor(deviceIdStore))
            .build()

        get(permissive).use { it.code }

        origin.takeRequest()
        assertEquals(DEVICE_ID, elsewhere.takeRequest().getHeader(HEADER_DEVICE_ID))
    }

    private companion object {
        const val HTTP_FOUND = 302
        const val DEVICE_ID = "device-abc"
        const val HEADER_DEVICE_ID = "X-Device-Id"
    }
}
