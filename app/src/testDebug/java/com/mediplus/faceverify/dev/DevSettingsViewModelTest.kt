package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.dev.ui.DevSettingsViewModel
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.model.Operator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevSettingsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `setAuth persists to the store`() = runTest {
        val store = TestDevSettingsStore()
        val vm = DevSettingsViewModel(store, InMemorySessionManager())

        vm.setAuth(AuthScenario.ACCOUNT_LOCKED)
        advanceUntilIdle()

        assertEquals(AuthScenario.ACCOUNT_LOCKED, store.current().auth)
    }

    @Test
    fun `forceSessionExpired marks the session expired`() = runTest {
        val session = InMemorySessionManager().apply {
            set(Session("t", Operator("op-001", "Demo"), expiresAt = null, state = SessionState.Active))
        }
        val vm = DevSettingsViewModel(TestDevSettingsStore(), session)

        vm.forceSessionExpired()

        assertEquals(SessionState.Expired, session.sessionState.value)
    }

    @Test
    fun `setCamera persists to the store`() = runTest {
        val store = TestDevSettingsStore()
        val vm = DevSettingsViewModel(store, InMemorySessionManager())

        vm.setCamera(CameraScenario.NO_CAMERA_HARDWARE)
        advanceUntilIdle()

        assertEquals(CameraScenario.NO_CAMERA_HARDWARE, store.current().camera)
    }
}
