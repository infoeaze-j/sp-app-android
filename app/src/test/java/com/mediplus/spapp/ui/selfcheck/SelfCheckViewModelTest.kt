package com.mediplus.spapp.ui.selfcheck

import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.SelfCheckItem
import com.mediplus.spapp.domain.model.TouchTestStatus
import com.mediplus.spapp.domain.usecase.ReadPermissionStatusUseCase
import com.mediplus.spapp.domain.usecase.ReadTouchTestStatusUseCase
import com.mediplus.spapp.domain.usecase.RecordTouchTestUseCase
import com.mediplus.spapp.domain.usecase.RunNetworkSelfCheckUseCase
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * SelfCheckViewModel: runs the checks on open, guards re-runs, and drives the touch test — showing
 * the last recorded result on open and recording each finished test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfCheckViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val runNetwork = mockk<RunNetworkSelfCheckUseCase>()
    private val readPermissions = mockk<ReadPermissionStatusUseCase>()
    private val readTouchTest = mockk<ReadTouchTestStatusUseCase>()
    private val recordTouchTest = mockk<RecordTouchTestUseCase>()

    private val lastWeek = TouchTestStatus.Passed(Instant.parse("2026-09-22T09:00:00Z"))
    private val justNow = Instant.parse("2026-09-28T12:32:00Z")

    private val running = mapOf<SelfCheckItem, CheckStatus>(SelfCheckItem.API to CheckStatus.Running)
    private val done = mapOf<SelfCheckItem, CheckStatus>(SelfCheckItem.API to CheckStatus.Passed(httpStatus = 200))
    private val cameraGranted = mapOf<SelfCheckItem, CheckStatus>(SelfCheckItem.PERM_CAMERA to CheckStatus.Passed())
    private val cameraDenied = mapOf<SelfCheckItem, CheckStatus>(
        SelfCheckItem.PERM_CAMERA to CheckStatus.Failed(CheckFailure.DENIED),
    )
    private var gate = CompletableDeferred<Unit>()

    private lateinit var vm: SelfCheckViewModel

    @Before
    fun setUp() {
        every { runNetwork() } answers {
            val thisRun = gate
            flow {
                emit(running)
                thisRun.await()
                emit(done)
            }
        }
        every { readPermissions() } returns cameraDenied
        coEvery { readTouchTest() } returns lastWeek
        coEvery { recordTouchTest(true) } returns TouchTestStatus.Passed(justNow)
        coEvery { recordTouchTest(false) } returns TouchTestStatus.Failed(justNow)
        vm = newViewModel()
    }

    private fun newViewModel() = SelfCheckViewModel(runNetwork, readPermissions, readTouchTest, recordTouchTest)

    @Test
    fun `the checks start as soon as the screen opens`() {
        assertEquals(running, vm.uiState.value.network)
        assertTrue(vm.uiState.value.isRunning)
        assertEquals(cameraDenied, vm.uiState.value.permissions)

        gate.complete(Unit)

        assertEquals(done, vm.uiState.value.network)
        assertFalse(vm.uiState.value.isRunning)
    }

    @Test
    fun `run again is ignored while a run is in progress`() {
        vm.rerun()
        verify(exactly = 1) { runNetwork() }

        gate.complete(Unit)
        gate = CompletableDeferred()
        vm.rerun()

        verify(exactly = 2) { runNetwork() }
        assertTrue(vm.uiState.value.isRunning)
    }

    @Test
    fun `run again re-reads permissions too`() {
        gate.complete(Unit)
        every { readPermissions() } returns cameraGranted

        vm.rerun()

        assertEquals(cameraGranted, vm.uiState.value.permissions)
    }

    @Test
    fun `permissions are re-read on refresh`() {
        every { readPermissions() } returns cameraGranted

        vm.refreshPermissions()

        assertEquals(cameraGranted, vm.uiState.value.permissions)
    }

    @Test
    fun `the last recorded touch test shows as soon as the screen opens`() {
        assertEquals(TouchTestPhase.Idle(lastWeek), vm.uiState.value.touch)
    }

    @Test
    fun `the touch row is loading until the history has been read`() {
        val history = CompletableDeferred<TouchTestStatus>()
        coEvery { readTouchTest() } coAnswers { history.await() }

        val vm = newViewModel()
        assertEquals(TouchTestPhase.Loading, vm.uiState.value.touch)

        history.complete(TouchTestStatus.NeverRun)
        assertEquals(TouchTestPhase.Idle(TouchTestStatus.NeverRun), vm.uiState.value.touch)
    }

    @Test
    fun `a test started while the history loads is not overwritten by it`() {
        val history = CompletableDeferred<TouchTestStatus>()
        coEvery { readTouchTest() } coAnswers { history.await() }
        val vm = newViewModel()

        vm.startTouchTest()
        history.complete(lastWeek)

        assertEquals(TouchTestPhase.InProgress(emptySet()), vm.uiState.value.touch)
        vm.closeTouchTest()
        assertEquals(TouchTestPhase.Idle(lastWeek), vm.uiState.value.touch)
    }

    @Test
    fun `closing a test before the history arrives falls back to it once it does`() {
        val history = CompletableDeferred<TouchTestStatus>()
        coEvery { readTouchTest() } coAnswers { history.await() }
        val vm = newViewModel()

        vm.startTouchTest()
        vm.closeTouchTest()
        assertEquals(TouchTestPhase.Loading, vm.uiState.value.touch)

        history.complete(lastWeek)
        assertEquals(TouchTestPhase.Idle(lastWeek), vm.uiState.value.touch)
    }

    @Test
    fun `a slow history read never replaces a result recorded meanwhile`() {
        val history = CompletableDeferred<TouchTestStatus>()
        coEvery { readTouchTest() } coAnswers { history.await() }
        val vm = newViewModel()

        vm.startTouchTest()
        TouchZone.entries.forEach(vm::onZoneTapped)
        history.complete(lastWeek)
        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Passed(justNow)), vm.uiState.value.touch)

        vm.startTouchTest()
        vm.closeTouchTest()
        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Passed(justNow)), vm.uiState.value.touch)
    }

    @Test
    fun `a save that lands after a retest has started leaves the retest running`() {
        val saved = CompletableDeferred<TouchTestStatus>()
        coEvery { recordTouchTest(true) } coAnswers { saved.await() }
        vm.startTouchTest()
        TouchZone.entries.forEach(vm::onZoneTapped)

        vm.startTouchTest()
        saved.complete(TouchTestStatus.Passed(justNow))
        assertEquals(TouchTestPhase.InProgress(emptySet()), vm.uiState.value.touch)

        vm.closeTouchTest()
        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Passed(justNow)), vm.uiState.value.touch)
    }

    @Test
    fun `the touch test passes and is recorded once every zone has been tapped`() {
        vm.startTouchTest()
        assertEquals(TouchTestPhase.InProgress(emptySet()), vm.uiState.value.touch)

        TouchZone.entries.dropLast(1).forEach(vm::onZoneTapped)
        assertEquals(TouchZone.entries.size - 1, (vm.uiState.value.touch as TouchTestPhase.InProgress).tapped.size)
        coVerify(exactly = 0) { recordTouchTest(any()) }

        vm.onZoneTapped(TouchZone.entries.last())
        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Passed(justNow)), vm.uiState.value.touch)
        coVerify(exactly = 1) { recordTouchTest(true) }
    }

    @Test
    fun `a finished test is recorded once, however many taps follow it`() {
        val saved = CompletableDeferred<TouchTestStatus>()
        coEvery { recordTouchTest(true) } coAnswers { saved.await() }
        vm.startTouchTest()

        TouchZone.entries.forEach(vm::onZoneTapped)
        assertEquals(TouchTestPhase.Saving, vm.uiState.value.touch)
        vm.onZoneTapped(TouchZone.KEY_1)
        vm.reportTouchProblem()
        vm.closeTouchTest()
        saved.complete(TouchTestStatus.Passed(justNow))

        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Passed(justNow)), vm.uiState.value.touch)
        coVerify(exactly = 1) { recordTouchTest(any()) }
    }

    @Test
    fun `tapping a zone twice counts once`() {
        vm.startTouchTest()

        vm.onZoneTapped(TouchZone.KEY_5)
        vm.onZoneTapped(TouchZone.KEY_5)

        assertEquals(TouchTestPhase.InProgress(setOf(TouchZone.KEY_5)), vm.uiState.value.touch)
    }

    @Test
    fun `reporting unresponsive areas fails the touch test and records it`() {
        vm.startTouchTest()
        vm.onZoneTapped(TouchZone.LOG_OUT)

        vm.reportTouchProblem()

        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Failed(justNow)), vm.uiState.value.touch)
        coVerify(exactly = 1) { recordTouchTest(false) }
    }

    @Test
    fun `closing an unfinished touch test discards it and keeps the last result`() {
        vm.startTouchTest()
        vm.onZoneTapped(TouchZone.IDENTIFIER)

        vm.closeTouchTest()
        assertEquals(TouchTestPhase.Idle(lastWeek), vm.uiState.value.touch)
        coVerify(exactly = 0) { recordTouchTest(any()) }

        vm.startTouchTest()
        assertEquals(TouchTestPhase.InProgress(emptySet()), vm.uiState.value.touch)
    }

    @Test
    fun `closing an abandoned retest keeps the result that was just recorded`() {
        vm.startTouchTest()
        vm.reportTouchProblem()
        vm.startTouchTest()

        vm.closeTouchTest()

        assertEquals(TouchTestPhase.Idle(TouchTestStatus.Failed(justNow)), vm.uiState.value.touch)
    }

    @Test
    fun `taps outside a running touch test are ignored`() {
        vm.onZoneTapped(TouchZone.PRIMARY_BUTTON)
        vm.reportTouchProblem()

        assertEquals(TouchTestPhase.Idle(lastWeek), vm.uiState.value.touch)
        coVerify(exactly = 0) { recordTouchTest(any()) }
    }
}
