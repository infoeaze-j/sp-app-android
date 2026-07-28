package com.mediplus.spapp.core.session

import androidx.lifecycle.LifecycleOwner
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.SessionCheck
import com.mediplus.spapp.domain.model.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The trigger rules for revalidation on resume
 * (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md): exactly one call per
 * foregrounding when the app believes it has a live session, and no call at all otherwise.
 *
 * `bind()` — the ProcessLifecycleOwner registration — is a thin untested shim, following the
 * DiagnosticsPoller.bind() precedent. `onStart` is called directly here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRevalidatorTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private val authRepository = mockk<AuthRepository> {
        coEvery { revalidateSession() } returns SessionCheck.Valid
    }

    private fun sessionManager(state: SessionState): SessionManager =
        mockk(relaxed = true) { every { sessionState } returns MutableStateFlow(state) }

    @Test
    fun `foregrounding with an active session revalidates exactly once`() = runTest {
        val session = sessionManager(SessionState.Active)
        val revalidator = SessionRevalidator(
            authRepository,
            session,
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 1) { authRepository.revalidateSession() }
        // The design's other load-bearing invariant: SessionRevalidator itself never mutates session
        // state — only AuthInterceptor acts on a 401. sessionManager is relaxed, so a stray mutator
        // call would otherwise succeed silently.
        verify(exactly = 0) { session.markSessionInvalidated() }
        verify(exactly = 0) { session.markSessionExpired() }
        verify(exactly = 0) { session.clearAll() }
    }

    @Test
    fun `no session means no call - nothing goes out from the sign-in screen`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.None),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `an expired session is not revalidated`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Expired),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `an invalidated session is not revalidated`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Invalidated),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `two foregroundings produce two calls - no once-per-process latch`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Active),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()
        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 2) { authRepository.revalidateSession() }
    }
}
