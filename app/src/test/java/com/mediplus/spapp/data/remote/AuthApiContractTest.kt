package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.network.DeviceIdInterceptor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * T017 — Auth API contract against MockWebServer (FR-005, FR-029), realigned to the
 * `SessionResource` in docs/openapi.json:
 *  - a 201 login parses the whole resource (operator, provider, policy),
 *  - a 422 login is refused with no session; `GET /auth/session` re-reads the same resource,
 *  - a 401 on a *protected* call flips the session to Invalidated (via [AuthInterceptor]),
 *  - the registered device id rides along as `X-Device-Id`,
 *  - the session token never appears in logged output (redaction).
 */
class AuthApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi
    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var deviceIdStore: DeviceIdStore
    private val logBuffer = StringBuilder()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionManager = InMemorySessionManager()
        deviceIdStore = DeviceIdStore()

        val logging = HttpLoggingInterceptor { line -> logBuffer.appendLine(line) }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionManager))
            .addInterceptor(DeviceIdInterceptor(deviceIdStore))
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
    fun `a 201 login parses the whole session resource`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(SESSION_BODY))

        val response = api.login(LoginRequest("sam", "pw"))

        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals("tok-123", body.token)
        assertEquals("op-1", body.operator.id)
        assertEquals("sam", body.operator.identifier)
        assertEquals(listOf("verify"), body.operator.permissions)
        assertEquals("Mercy Hospital", body.provider.name)
        assertEquals("Africa/Johannesburg", body.provider.timezone)
        assertEquals(900L, body.policy.verificationTtlSeconds)
        assertEquals(5, body.policy.face.maxAttempts)
    }

    @Test
    fun `a login response without provider or policy still parses`() = runTest {
        // Both are `required` in the spec, but a sign-in must not fail on their absence: the
        // subtitle simply goes unshown and a missing TTL reads as immediately stale (FR-026).
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"token":"tok-123","operator":{"id":"op-1"}}""",
            ),
        )

        val body = api.login(LoginRequest("sam", "pw")).body()!!

        assertEquals("", body.provider.name)
        assertNull(body.policy.verificationTtlSeconds)
    }

    @Test
    fun `an invalid credential is a 422 and leaves no session`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"message":"These credentials do not match our records.","errors":{"identifier":["Invalid."]}}""",
            ),
        )

        val response = api.login(LoginRequest("sam", "wrong"))

        assertEquals(422, response.code())
        assertEquals(SessionState.None, sessionManager.sessionState.value)
    }

    @Test
    fun `the session endpoint re-reads the same resource`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        val body = api.session().body()!!

        assertEquals("op-1", body.operator.id)
        assertEquals("/auth/session", server.takeRequest().path)
    }

    @Test
    fun `a 200 on the session endpoint leaves the session Active`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        api.session()

        // Paired with `401 on a protected call invalidates the session` below, this is the end-to-end
        // proof that revalidation routes correctly: a healthy session survives, a dead one does not.
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `401 on a protected call invalidates the session`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated."}"""))

        api.session()

        assertEquals(SessionState.Invalidated, sessionManager.sessionState.value)
        val recorded = server.takeRequest()
        assertEquals("Bearer tok-xyz", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a registered device id rides along as X-Device-Id`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        deviceIdStore.set("dev-42")
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        api.session()

        assertEquals("dev-42", server.takeRequest().getHeader("X-Device-Id"))
    }

    @Test
    fun `no device id means no header rather than a blank one`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        api.session()

        assertNull(server.takeRequest().getHeader("X-Device-Id"))
    }

    @Test
    fun `session token is never written to logs`() = runTest {
        sessionManager.set(activeSession("supersecret-token"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        api.session()

        val logs = logBuffer.toString()
        assertFalse("token leaked into logs: $logs", logs.contains("supersecret-token"))
    }

    private fun activeSession(token: String) =
        Session(token, Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active)

    private companion object {
        val SESSION_BODY = """
            {"token":"tok-123","expiresAt":"2026-07-20T12:34:56Z",
             "operator":{"id":"op-1","identifier":"sam","displayName":"Sam","permissions":["verify"]},
             "provider":{"id":"p-1","code":"MERCY","name":"Mercy Hospital","timezone":"Africa/Johannesburg"},
             "policy":{"verificationTtlSeconds":900,"sessionTtlSeconds":28800,
                       "face":{"maxAttempts":5,"lockoutSeconds":300}},
             "serverTime":"2026-07-20T12:00:00Z"}
        """.trimIndent()
    }
}
