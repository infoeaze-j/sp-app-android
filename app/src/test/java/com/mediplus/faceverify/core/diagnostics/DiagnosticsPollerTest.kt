package com.mediplus.faceverify.core.diagnostics

import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCase
import com.mediplus.faceverify.domain.usecase.PollOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsPollerTest {

    private val calls = AtomicInteger(0)
    private val useCase = mockk<PollAndReportDiagnosticsUseCase> {
        coEvery { this@mockk.invoke() } answers { calls.incrementAndGet(); PollOutcome.NothingRequested }
    }

    private fun sessionManager(state: MutableStateFlow<SessionState>): SessionManager =
        mockk(relaxed = true) { every { sessionState } returns state }

    @Test
    fun `polls immediately when session becomes active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()
        assertEquals(1, calls.get())
    }

    @Test
    fun `repeats every interval while active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()                                   // immediate poll -> 1
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS + 1)   // -> 2
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS + 1)   // -> 3
        assertEquals(3, calls.get())
    }

    @Test
    fun `stops polling when session leaves active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()                                   // -> 1
        state.value = SessionState.None                // collectLatest cancels the inner loop
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS * 3)
        assertEquals(1, calls.get())
    }

    @Test
    fun `resumes when session becomes active again`() = runTest {
        val state = MutableStateFlow(SessionState.None)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()
        assertEquals(0, calls.get())
        state.value = SessionState.Active
        runCurrent()
        assertEquals(1, calls.get())
    }
}
