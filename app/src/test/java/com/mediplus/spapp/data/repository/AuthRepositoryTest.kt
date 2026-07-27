package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.data.remote.AuthApi
import com.mediplus.spapp.data.remote.LoginResponse
import com.mediplus.spapp.data.remote.OperatorDto
import com.mediplus.spapp.data.remote.SessionConfigDto
import com.mediplus.spapp.domain.model.SessionState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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

    private fun loginResponse() = LoginResponse(
        token = "tok",
        expiresAt = "2026-07-20T12:34:56Z",
        operator = OperatorDto("op-1", "Sam", listOf("verify")),
        config = SessionConfigDto(verificationWindowSeconds = 900),
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
    fun `401 maps to invalid credentials and creates no session`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.error(401, "".toResponseBody(null))

        val result = repo.signIn("sam", "wrong")

        assertTrue(result is AppResult.BusinessRejection)
        assertEquals(BusinessCode.INVALID_CREDENTIALS, (result as AppResult.BusinessRejection).error.code)
        assertEquals(null, sessionManager.session.value)
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
    fun `sign out clears all session state`() = runTest(dispatcher) {
        coEvery { api.login(any()) } returns Response.success(loginResponse())
        repo.signIn("sam", "pw")
        coEvery { api.logout() } returns Response.success(Unit)

        repo.signOut()

        assertEquals(null, sessionManager.session.value)
        assertEquals(SessionState.None, sessionManager.sessionState.value)
    }
}
