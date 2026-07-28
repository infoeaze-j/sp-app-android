package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.data.remote.AuthApi
import com.mediplus.spapp.data.remote.OperatorDto
import com.mediplus.spapp.data.remote.ProviderDto
import com.mediplus.spapp.data.remote.SessionPolicyDto
import com.mediplus.spapp.data.remote.SessionResource
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * T018 — AuthRepository maps every outcome to the correct [AppResult] and feeds the SessionManager
 * only on success (FR-003, FR-005, FR-006).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    private val api = mockk<AuthApi>()
    private lateinit var sessionManager: InMemorySessionManager
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: AuthRepositoryImpl

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        repo = AuthRepositoryImpl(api, sessionManager, dispatcher)
    }

    private fun loginResponse() = SessionResource(
        token = "tok",
        expiresAt = "2026-07-20T12:34:56Z",
        operator = OperatorDto(id = "op-1", identifier = "sam", displayName = "Sam", permissions = listOf("verify")),
        policy = SessionPolicyDto(verificationTtlSeconds = 900),
    )

    /**
     * A live session, set directly rather than via `signIn`, so the revalidation tests do not depend
     * on the login path.
     */
    private fun activeSession() = Session(
        token = "tok",
        operator = Operator("op-1", "Sam"),
        expiresAt = null,
        state = SessionState.Active,
    )

    @Test
    fun `success sets session and freshness window`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(loginResponse())

        val result = repo.signIn("sam", "pw")

        assertTrue(result is AppResult.Success)
        assertEquals("tok", sessionManager.session.value?.token)
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
        assertEquals(900.seconds, sessionManager.verificationWindow.value)
    }

    @Test
    fun `422 and 401 both map to invalid credentials and create no session`() = runTest(dispatcher) {
        // The spec reports a bad credential on the unauthenticated login route as a 422 validation
        // failure; a 401 says the same thing and is accepted for a back office that still sends one.
        coEvery { api.login(any()) } returns Response.error(422, "".toResponseBody(null))
        val validation = repo.signIn("sam", "wrong")
        assertEquals(
            BusinessCode.INVALID_CREDENTIALS,
            (validation as AppResult.BusinessRejection).error.code,
        )

        coEvery { api.login(any()) } returns Response.error(401, "".toResponseBody(null))
        val unauthorized = repo.signIn("sam", "wrong")
        assertEquals(
            BusinessCode.INVALID_CREDENTIALS,
            (unauthorized as AppResult.BusinessRejection).error.code,
        )
        assertNull(sessionManager.session.value)
    }

    @Test
    fun `a 201 is a successful sign-in`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(201, loginResponse())

        assertTrue(repo.signIn("sam", "pw") is AppResult.Success)
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `the operator identifier rides onto the session`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(loginResponse())

        repo.signIn("sam", "pw")

        assertEquals("sam", sessionManager.session.value?.operator?.identifier)
    }

    @Test
    fun `a session without a policy leaves no freshness window, which reads as stale`() =
        runTest(dispatcher) {
            coEvery { api.login(any()) } returns Response.success(
                loginResponse().copy(policy = SessionPolicyDto()),
            )

            repo.signIn("sam", "pw")

            assertNull(sessionManager.verificationWindow.value)
        }

    @Test
    fun `423 and 429 map to account locked`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.error(423, "".toResponseBody(null))
        val locked = repo.signIn("sam", "pw")
        assertEquals(
            BusinessCode.ACCOUNT_LOCKED,
            (locked as AppResult.BusinessRejection).error.code,
        )

        coEvery { api.login(any()) } returns Response.error(429, "".toResponseBody(null))
        val throttled = repo.signIn("sam", "pw")
        assertEquals(
            BusinessCode.ACCOUNT_LOCKED,
            (throttled as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `500 maps to a transient server failure`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.error(500, "".toResponseBody(null))

        val result = repo.signIn("sam", "pw")

        assertTrue(result is AppResult.TransientFailure)
        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `IO failure maps to no connectivity`() = runTest(dispatcher) {
        coEvery { api.login(any()) } throws IOException("offline")

        val result = repo.signIn("sam", "pw")

        assertTrue(result is AppResult.TransientFailure)
        assertEquals(TransientKind.NO_CONNECTIVITY, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `socket timeout maps to Timeout`() = runTest(dispatcher) {
        coEvery { api.login(any()) } throws SocketTimeoutException("slow")

        val result = repo.signIn("sam", "pw")

        assertEquals(AppResult.Timeout, result)
    }

    @Test
    fun `login maps a provider name onto the session`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(
            loginResponse().copy(
                provider = ProviderDto(
                    id = "p-1",
                    code = "RIV",
                    name = "Riverside Clinic",
                    timezone = "Africa/Johannesburg",
                ),
            ),
        )

        repo.signIn("sam", "pw")

        val provider = sessionManager.session.value?.provider
        assertEquals("Riverside Clinic", provider?.name)
        assertEquals("RIV", provider?.code)
        assertEquals("Africa/Johannesburg", provider?.timezone)
    }

    @Test
    fun `login with a blank provider name yields no provider`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(
            loginResponse().copy(provider = ProviderDto(name = "   ")),
        )

        repo.signIn("sam", "pw")

        assertNull(sessionManager.session.value?.provider)
    }

    @Test
    fun `login without a provider yields no provider`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(loginResponse())

        repo.signIn("sam", "pw")

        assertNull(sessionManager.session.value?.provider)
    }

    @Test
    fun `sign out clears all session state`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(loginResponse())
        repo.signIn("sam", "pw")
        coEvery { api.logout() } returns Response.success(Unit)

        repo.signOut()

        assertEquals(null, sessionManager.session.value)
        assertEquals(SessionState.None, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - a 200 says the session is valid`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.success(loginResponse())

        assertEquals(SessionCheck.Valid, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - a 401 says the session has ended`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(401, "".toResponseBody(null))

        // The actual invalidation is AuthInterceptor's job, proven end to end in AuthApiContractTest.
        // AuthApi is mocked here, so there is no interceptor in the chain and only the classification
        // is asserted — the repository must not invalidate anything itself.
        assertEquals(SessionCheck.Ended, repo.revalidateSession())
    }

    @Test
    fun `revalidate - a 500 is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(500, "".toResponseBody(null))

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - an unexpected status is unknown, not an ending`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(418, "".toResponseBody(null))

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - an IO failure is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } throws IOException("offline")

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - a socket timeout is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } throws SocketTimeoutException("slow")

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }
}
