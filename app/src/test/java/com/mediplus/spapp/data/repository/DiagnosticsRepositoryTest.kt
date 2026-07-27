package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.diagnostics.BatteryPlug
import com.mediplus.spapp.core.diagnostics.BatteryState
import com.mediplus.spapp.core.diagnostics.DeviceInfo
import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.core.diagnostics.DisplayState
import com.mediplus.spapp.core.diagnostics.EnvironmentState
import com.mediplus.spapp.core.diagnostics.MemoryState
import com.mediplus.spapp.core.diagnostics.NetworkState
import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.core.diagnostics.AppInfo
import com.mediplus.spapp.core.diagnostics.StorageState
import com.mediplus.spapp.core.diagnostics.ThermalState
import com.mediplus.spapp.core.diagnostics.UptimeState
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.remote.DiagnosticsApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DiagnosticsApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DiagnosticsApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo() = DiagnosticsRepositoryImpl(api, UnconfinedTestDispatcher())

    private fun snapshot() = DeviceStateSnapshot(
        battery = BatteryState(80, true, BatteryPlug.USB, "2", 250, 4100, false),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(1_000L, 2_000L),
        memory = MemoryState(500L, 4_000L, lowMemory = false),
        display = DisplayState(1080, 2400, 420, 60f, 0),
        device = DeviceInfo("Google", "Pixel", "google", "raven", 34, "14"),
        app = AppInfo("1.0", 1),
        environment = EnvironmentState("en-ZA", "Africa/Johannesburg", airplaneMode = false),
        thermal = ThermalState(0.3f, 0),
        uptime = UptimeState(1_000L, 2_000L),
    )

    @Test
    fun `poll 200 yields the request id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"requestId":"req-7"}"""))
        val result = repo().poll()
        assertEquals(AppResult.Success("req-7"), result)
    }

    @Test
    fun `poll 204 yields null - nothing requested`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AppResult.Success(null), repo().poll())
    }

    @Test
    fun `poll 404 yields null - endpoint not deployed, fail open`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AppResult.Success(null), repo().poll())
    }

    @Test
    fun `poll 500 is a transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo().poll() is AppResult.TransientFailure)
    }

    @Test
    fun `report posts requestId and snapshot and succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = repo().report("req-7", snapshot())
        assertEquals(AppResult.Success(Unit), result)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"requestId\":\"req-7\""))
        assertTrue(body.contains("\"transport\":\"WIFI\""))
        assertTrue(body.contains("\"levelPercent\":80"))
    }

    @Test
    fun `report 500 is a transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo().report("req-7", snapshot()) is AppResult.TransientFailure)
    }
}
