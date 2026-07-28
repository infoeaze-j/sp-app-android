package com.mediplus.spapp.data.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mediplus.spapp.core.device.DeviceBuildInfo
import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.local.PrefsDataStore
import com.mediplus.spapp.data.repository.DeviceRepositoryImpl
import com.mediplus.spapp.domain.model.CurrentAppVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * Device registration contract (`devices.register`): the client-generated install id and the
 * permission-free build facts go up, the returned id comes back and is recorded for `X-Device-Id`,
 * and the id is read whether the back office answers with a bare string or an object — the spec
 * says `string`, but its response types are generator output (it calls an APK stream an `object`).
 *
 * Failures stay retryable and leave no device id: registration is best-effort audit-trail plumbing
 * and must never be able to stall the journey.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DeviceRepositoryImpl
    private lateinit var deviceIdStore: DeviceIdStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        deviceIdStore = DeviceIdStore()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val api: DeviceApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(
                Json { ignoreUnknownKeys = true; explicitNulls = false }
                    .asConverterFactory("application/json".toMediaType()),
            )
            .build()
            .create()
        repository = DeviceRepositoryImpl(
            api = api,
            prefs = PrefsDataStore(InMemoryPreferences()),
            deviceIdStore = deviceIdStore,
            buildInfo = { DeviceBuildInfo("Google", "Pixel 8", "14", 34) },
            currentVersion = CurrentAppVersion(code = 5, name = "1.5"),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a bare id string registers the device`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""dev-42""""))

        val result = repository.register()

        assertEquals(AppResult.Success("dev-42"), result)
        assertEquals("dev-42", deviceIdStore.deviceId.value)
        assertEquals("/devices/register", server.takeRequest().path)
    }

    @Test
    fun `an object carrying the id registers the device too`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"deviceId":"dev-43"}"""))

        assertEquals(AppResult.Success("dev-43"), repository.register())
        assertEquals("dev-43", deviceIdStore.deviceId.value)
    }

    @Test
    fun `the request carries a generated install id and the permission-free build facts`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""dev-42""""))

        repository.register()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(36, body.getValue("installId").jsonPrimitive.content.length) // a UUID
        assertEquals("Google", body.getValue("manufacturer").jsonPrimitive.content)
        assertEquals("Pixel 8", body.getValue("model").jsonPrimitive.content)
        assertEquals("14", body.getValue("osRelease").jsonPrimitive.content)
        assertEquals(34, body.getValue("sdkInt").jsonPrimitive.content.toInt())
        assertEquals("1.5", body.getValue("appVersionName").jsonPrimitive.content)
        assertEquals(5, body.getValue("appVersionCode").jsonPrimitive.content.toInt())
    }

    @Test
    fun `the install id is stable across registrations`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""dev-42""""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""dev-42""""))

        repository.register()
        repository.register()

        val first = installIdOf(server.takeRequest().body.readUtf8())
        assertEquals(first, installIdOf(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun `a server error stays retryable and records no device id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.register()

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
        assertNull(deviceIdStore.deviceId.value)
    }

    @Test
    fun `an unreadable response records no device id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"unexpected":true}"""))

        val result = repository.register()

        assertEquals(TransientKind.UNKNOWN, (result as AppResult.TransientFailure).error.kind)
        assertNull(deviceIdStore.deviceId.value)
    }

    private fun installIdOf(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("installId").jsonPrimitive.content

    /** A DataStore that lives in memory, so the install-id round trip needs no files. */
    private class InMemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
