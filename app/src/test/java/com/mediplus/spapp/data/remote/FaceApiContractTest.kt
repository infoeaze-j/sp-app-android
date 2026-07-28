package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.FaceRepositoryImpl
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.LivenessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * T036 — Face verification contract (FR-013, FR-014, FR-015) against `POST /face/verifications`:
 * pass+liveness, no-match, spoof and locked-out each parse into the correct [FaceDecision], and a
 * pass carries the single-use `verificationId` the enrollment step spends.
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

    private suspend fun verify(): AppResult<FaceDecision> =
        repository.verify("1234567", TransientFrame(byteArrayOf(1, 2, 3)))

    @Test
    fun `a 201 pass with liveness and same subject carries the verification id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":"ver-1","decision":"PASS","liveness":"PASS","sameSubject":true,""" +
                    """"expiresAt":"2026-07-20T12:45:00Z",""" +
                    """"lockout":{"lockedOut":false,"remainingAttempts":3,"cooldownUntil":""}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertTrue(decision.decisionPass)
        assertEquals(LivenessResult.PASSED, decision.liveness)
        assertTrue(decision.sameSubject)
        assertFalse(decision.lockout.lockedOut)
        assertEquals("ver-1", decision.verificationId)
    }

    @Test
    fun `the request posts to face verifications with the capture block`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":"ver-1","decision":"PASS","liveness":"PASS","sameSubject":true,""" +
                    """"lockout":{"lockedOut":false,"remainingAttempts":3,"cooldownUntil":""}}""",
            ),
        )

        verify()

        val recorded = server.takeRequest()
        assertEquals("/face/verifications", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("1234567", body.getValue("memberNumber").jsonPrimitive.content)
        assertTrue(
            body.getValue("capture").jsonObject
                .getValue("hasLivenessChallengeResponse").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `no match issues no verification id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":null,"decision":"FAIL","liveness":"PASS","sameSubject":true,""" +
                    """"lockout":{"lockedOut":false,"remainingAttempts":2,"cooldownUntil":""}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertFalse(decision.decisionPass)
        assertNull(decision.verificationId)
    }

    @Test
    fun `spoof fails liveness`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":null,"decision":"PASS","liveness":"FAIL","sameSubject":true,""" +
                    """"lockout":{"lockedOut":false,"remainingAttempts":2,"cooldownUntil":""}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertEquals(LivenessResult.FAILED, decision.liveness)
    }

    @Test
    fun `lockout after limit`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":null,"decision":"FAIL","liveness":"PASS","sameSubject":true,""" +
                    """"lockout":{"lockedOut":true,"remainingAttempts":0,"cooldownUntil":"2026-07-20T13:00:00Z"}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertTrue(decision.lockout.lockedOut)
        assertEquals(0, decision.lockout.remainingAttempts)
        assertEquals(
            java.time.Instant.parse("2026-07-20T13:00:00Z").toEpochMilli(),
            decision.lockout.cooldownUntilMillis,
        )
    }

    @Test
    fun `a blank verification id is dropped so enrollment fails closed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"verificationId":"","decision":"PASS","liveness":"PASS","sameSubject":true,""" +
                    """"lockout":{"lockedOut":false,"remainingAttempts":3,"cooldownUntil":""}}""",
            ),
        )
        val decision = (verify() as AppResult.Success).data
        assertNull(decision.verificationId)
    }
}
