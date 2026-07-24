package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.UpdateRepositoryImpl
import com.mediplus.faceverify.domain.model.UpdateInfo
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
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * Version-check contract (self-update design): a published build maps to [UpdateInfo]; an
 * undeployed endpoint (404) is "nothing published", never an error; 5xx and timeout classify like
 * every other endpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val api: UpdateApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = UpdateRepositoryImpl(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a published build maps to UpdateInfo`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latestVersionCode":7,"latestVersionName":"1.6",""" +
                    """"apkUrl":"https://backoffice.example.com/app/faceverify-7.apk",""" +
                    """"sha256":"$SHA","sizeBytes":12345678,"minSupportedVersionCode":5}""",
            ),
        )

        val result = repository.fetchVersionInfo()

        assertEquals(
            AppResult.Success(
                UpdateInfo(
                    latestVersionCode = 7,
                    latestVersionName = "1.6",
                    apkUrl = "https://backoffice.example.com/app/faceverify-7.apk",
                    sha256 = SHA,
                    sizeBytes = 12_345_678,
                    minSupportedVersionCode = 5,
                ),
            ),
            result,
        )
    }

    @Test
    fun `unknown keys in the response are ignored`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latestVersionCode":7,"releaseNotes":"surprise field","channel":"stable"}""",
            ),
        )

        val info = (repository.fetchVersionInfo() as AppResult.Success).data

        assertEquals(7, info?.latestVersionCode)
    }

    @Test
    fun `an undeployed endpoint means nothing published, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(AppResult.Success(null), repository.fetchVersionInfo())
    }

    @Test
    fun `server error maps to transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository.fetchVersionInfo()

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `an unexpected status stays retryable rather than failing hard`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.fetchVersionInfo()

        assertEquals(TransientKind.UNKNOWN, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `no response within the timeout maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        assertEquals(AppResult.Timeout, repository.fetchVersionInfo())
    }

    private companion object {
        const val SHA = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6"
    }
}
