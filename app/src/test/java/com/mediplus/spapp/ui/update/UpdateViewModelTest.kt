package com.mediplus.spapp.ui.update

import com.mediplus.spapp.core.update.Presence
import com.mediplus.spapp.core.update.UpdateAttempt
import com.mediplus.spapp.core.update.UpdateCoordinator
import com.mediplus.spapp.core.update.UpdatePhase
import com.mediplus.spapp.core.update.UpdateTrigger
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ViewModel is now an adapter over [UpdateCoordinator]: it supplies a scope, forwards gestures,
 * and re-exposes the coordinator's flow. The orchestration itself is covered by
 * `UpdateCoordinatorTest`, because the worker runs the same code and must not need a ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val coordinator = mockk<UpdateCoordinator>()
    private val phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)

    @Before
    fun setUp() {
        every { coordinator.phase } returns phase
        coEvery { coordinator.runUpdate(any()) } returns UpdateAttempt.COMPLETED
        coEvery { coordinator.accept() } returns Unit
        coEvery { coordinator.retry() } returns Unit
        coEvery { coordinator.returnedFromSettings() } returns Unit
        justRun { coordinator.dismiss() }
        every { coordinator.needsLegacyWritePermission() } returns false
    }

    @Test
    fun `opening the app runs a foreground update attempt`() = runTest {
        UpdateViewModel(coordinator)
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.runUpdate(Presence.Foreground) }
    }

    @Test
    fun `construction never blocks the first frame`() = runTest {
        UpdateViewModel(coordinator)

        // The launch-time attempt is scheduled, not awaited — nothing has run yet at construction.
        coVerify(exactly = 0) { coordinator.runUpdate(any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { coordinator.runUpdate(Presence.Foreground) }
    }

    @Test
    fun `the rendered phase is the coordinator's, whichever caller produced it`() = runTest {
        val viewModel = UpdateViewModel(coordinator)

        val installing = UpdatePhase.Installing(forced = true, trigger = UpdateTrigger.Background)
        phase.value = installing

        assertEquals(installing, viewModel.phase.value)
    }

    @Test
    fun `accepting forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onUpdateAccepted()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.accept() }
    }

    @Test
    fun `dismissing forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)

        viewModel.onDismissed()

        verify(exactly = 1) { coordinator.dismiss() }
    }

    @Test
    fun `retrying forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onRetry()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.retry() }
    }

    @Test
    fun `returning from settings forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onReturnedFromSettings()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.returnedFromSettings() }
    }

    @Test
    fun `the legacy write question is answered by the coordinator`() = runTest {
        every { coordinator.needsLegacyWritePermission() } returns true
        val viewModel = UpdateViewModel(coordinator)

        assertEquals(true, viewModel.needsLegacyWritePermission())
    }
}
