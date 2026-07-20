package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.domain.model.LivenessResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * T036 — Face verify contract (FR-013, FR-014, FR-015): pass+liveness, no-match, spoof, and
 * locked-out responses each parse into the correct [com.mediplus.faceverify.domain.model.FaceDecision].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceApiContractTest {

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

    private suspend fun verify(): AppResult<com.mediplus.faceverify.domain.model.FaceDecision> =
        repository.verify("P1", TransientFrame(byteArrayOf(1, 2, 3)))

    @Test
    fun `pass with liveness and same subject`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"decision":"PASS","liveness":"PASS","sameSubject":true,"lockout":{"lockedOut":false,"remainingAttempts":3}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertTrue(decision.decisionPass)
        assertEquals(LivenessResult.PASSED, decision.liveness)
        assertTrue(decision.sameSubject)
        assertFalse(decision.lockout.lockedOut)
    }

    @Test
    fun `no match`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"decision":"FAIL","liveness":"PASS","sameSubject":true,"lockout":{"lockedOut":false}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertFalse(decision.decisionPass)
    }

    @Test
    fun `spoof fails liveness`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"decision":"PASS","liveness":"FAIL","sameSubject":true,"lockout":{"lockedOut":false}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertEquals(LivenessResult.FAILED, decision.liveness)
    }

    @Test
    fun `lockout after limit`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"decision":"FAIL","liveness":"PASS","sameSubject":true,"lockout":{"lockedOut":true,"remainingAttempts":0}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertTrue(decision.lockout.lockedOut)
        assertEquals(0, decision.lockout.remainingAttempts)
    }
}
