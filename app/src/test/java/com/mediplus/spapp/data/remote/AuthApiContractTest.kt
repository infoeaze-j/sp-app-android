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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
    fun `sign-in goes out unauthenticated even with a session still in memory`() = runTest {
        // docs/openapi.json declares `auth.login` with `security: []` — it is the one endpoint that
        // must carry no bearer token. Sending one makes the back office answer 401, and a 401 on a
        // request that carried a token reads as a session loss (see the test below).
        sessionManager.set(activeSession("tok-stale"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(SESSION_BODY))

        api.login(LoginRequest("sam", "pw"))

        val recorded = server.takeRequest()
        assertNull("sign-in must not be authenticated", recorded.getHeader("Authorization"))
        assertNull("the no-auth marker is internal and must not reach the wire", recorded.getHeader("X-No-Auth"))
    }

    @Test
    fun `a 401 on sign-in is a credential rejection, not a session loss`() = runTest {
        // FR-005, not FR-004. A stale session in memory must not turn a rejected credential into a
        // "session ended" notice that also throws away the operator's sign-in attempt.
        sessionManager.set(activeSession("tok-stale"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated."}"""))

        api.login(LoginRequest("sam", "wrong"))

        assertEquals(SessionState.Active, sessionManager.sessionState.value)
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
    fun `a late 401 from a stale token does not kill a newer session`() = runTest {
        // The operator re-signs in while a request carrying the old token is still in flight — the
        // slow revalidation on resume is the realistic case. The dispatcher stands in for that race:
        // it swaps the session before answering, so the 401 arrives against a session that is no
        // longer the one it was sent for.
        sessionManager.set(activeSession("tok-old"))
        server.dispatcher = swapSessionThen(activeSession("tok-new"), MockResponse().setResponseCode(401))

        api.session()

        assertEquals(SessionState.Active, sessionManager.sessionState.value)
        assertEquals("tok-new", sessionManager.session.value?.token)
    }

    @Test
    fun `a late 401 after sign-out does not raise a session-ended notice`() = runTest {
        // Same race, deliberate ending: clearAll() leaves None. Flipping that to Invalidated would
        // show the operator "session ended" after they chose to log out (FR-004a).
        sessionManager.set(activeSession("tok-old"))
        server.dispatcher = clearSessionThen(MockResponse().setResponseCode(401))

        api.session()

        assertEquals(SessionState.None, sessionManager.sessionState.value)
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

    /** Replaces the live session mid-flight, then answers — simulating a re-sign-in during a request. */
    private fun swapSessionThen(next: Session, response: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            sessionManager.set(next)
            return response
        }
    }

    /** Signs out mid-flight, then answers — simulating a log out during a request. */
    private fun clearSessionThen(response: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            sessionManager.clearAll()
            return response
        }
    }

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
