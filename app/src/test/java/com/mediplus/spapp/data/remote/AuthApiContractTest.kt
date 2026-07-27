package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

/**
 * T017 — Auth API contract against MockWebServer (FR-005, FR-029):
 *  - valid login parses a session; 401 login → refused; logout is callable,
 *  - a 401 on a *protected* call flips the session to Invalidated (via [AuthInterceptor]),
 *  - the session token never appears in logged output (redaction).
 */
class AuthApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi
    private lateinit var sessionManager: InMemorySessionManager
    private val logBuffer = StringBuilder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionManager = InMemorySessionManager()

        val logging = HttpLoggingInterceptor { line -> logBuffer.appendLine(line) }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionManager))
            .addInterceptor(logging)
            .build()

        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `valid login parses a session`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"token":"tok-123","expiresAt":"2026-07-20T12:34:56Z",
                 "operator":{"operatorId":"op-1","displayName":"Sam","permissions":["verify"]},
                 "config":{"verificationWindowSeconds":900}}
                """.trimIndent(),
            ),
        )

        val response = api.login(LoginRequest("sam", "pw"))

        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals("tok-123", body.token)
        assertEquals("op-1", body.operator.operatorId)
        assertEquals(900L, body.config?.verificationWindowSeconds)
    }

    @Test
    fun `invalid login returns 401 and no session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val response = api.login(LoginRequest("sam", "wrong"))

        assertEquals(401, response.code())
        assertEquals(SessionState.None, sessionManager.sessionState.value)
    }

    @Test
    fun `401 on a protected call invalidates the session`() = runTest {
        sessionManager.set(Session("tok-xyz", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active))
        server.enqueue(MockResponse().setResponseCode(401))

        api.session()

        assertEquals(SessionState.Invalidated, sessionManager.sessionState.value)
        val recorded = server.takeRequest()
        assertEquals("Bearer tok-xyz", recorded.getHeader("Authorization"))
    }

    @Test
    fun `session token is never written to logs`() = runTest {
        sessionManager.set(Session("supersecret-token", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active))
        server.enqueue(MockResponse().setResponseCode(200))

        api.session()

        val logs = logBuffer.toString()
        assertFalse("token leaked into logs: $logs", logs.contains("supersecret-token"))
    }
}
