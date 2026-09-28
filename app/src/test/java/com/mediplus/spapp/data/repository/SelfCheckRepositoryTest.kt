package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.data.remote.SelfCheckApi
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.ProbeEndpoint
import com.mediplus.spapp.domain.model.ProbeOutcome
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The self check's endpoint probes, run through the real [AuthInterceptor] with a **live session in
 * memory** — the case that would hurt if a probe ever carried the token: a 401 on a request that
 * carried it ends the session.
 *
 * `runBlocking`, not `runTest`: the probe timeout is a coroutine timeout, and under `runTest`'s
 * virtual clock it would fire the moment the test coroutine suspends on real network I/O.
 */
class SelfCheckRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SelfCheckApi
    private val sessionManager = InMemorySessionManager()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        sessionManager.set(Session("live-token", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active))
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionManager)).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SelfCheckApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo(timeout: Duration = 5.seconds) =
        SelfCheckRepositoryImpl(api, CurrentAppVersion(code = 10, name = "1.10"), Dispatchers.IO, timeout)

    @Test
    fun `the release probe asks about the running build and reports the status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"latest":null}"""))

        val outcome = repo().probe(ProbeEndpoint.LATEST_RELEASE)

        assertEquals(ProbeOutcome.Responded(200), outcome)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/app/releases/latest?versionCode=10", request.path)
    }

    @Test
    fun `the session probe carries no token, and its 401 leaves the live session alone`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = repo().probe(ProbeEndpoint.SESSION)

        assertEquals(ProbeOutcome.Responded(401), outcome)
        val request = server.takeRequest()
        assertEquals("/auth/session", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-No-Auth"))
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `the member probe posts the placeholder card number without a token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = repo().probe(ProbeEndpoint.MEMBER_VERIFY)

        assertEquals(ProbeOutcome.Responded(401), outcome)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/members/verify", request.path)
        assertTrue(request.body.readUtf8().contains(SelfCheckRepositoryImpl.PLACEHOLDER_MEMBER_NUMBER))
        assertNull(request.getHeader("Authorization"))
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `a server that never answers is reported as timed out`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(ProbeOutcome.TimedOut, repo(timeout = 300.milliseconds).probe(ProbeEndpoint.SESSION))
    }

    @Test
    fun `a server that drops the connection is reported as unreachable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(ProbeOutcome.Unreachable, repo().probe(ProbeEndpoint.SESSION))
    }
}
