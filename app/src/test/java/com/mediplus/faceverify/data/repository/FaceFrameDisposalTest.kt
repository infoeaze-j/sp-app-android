package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.data.remote.FaceApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * T039 — the captured [TransientFrame] is ALWAYS cleared after FaceRepository.verify returns,
 * whether the decision succeeds, the server errors, or the call times out (FR-017).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceFrameDisposalTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: FaceRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val api: FaceApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = FaceRepositoryImpl(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun frame() = TransientFrame(byteArrayOf(1, 2, 3, 4))

    @Test
    fun `frame is cleared after a successful decision`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"decision":"PASS","liveness":"PASS","sameSubject":true,"lockout":{"lockedOut":false}}""",
            ),
        )
        val frame = frame()

        repository.verify("P1", frame)

        assertTrue(frame.isCleared)
    }

    @Test
    fun `frame is cleared after a server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val frame = frame()

        repository.verify("P1", frame)

        assertTrue(frame.isCleared)
    }

    @Test
    fun `frame is cleared after a timeout`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))
        val frame = frame()

        repository.verify("P1", frame)

        assertTrue(frame.isCleared)
    }
}
