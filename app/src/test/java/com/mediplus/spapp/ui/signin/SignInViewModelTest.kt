package com.mediplus.spapp.ui.signin

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.DeviceRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T019 — SignInViewModel state machine: idle → loading → success / error / locked-out, plus the
 * "session ended" notice when routed back by expiry/invalidation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repo = mockk<AuthRepository>()
    private val deviceRepo = mockk<DeviceRepository>()
    private val sessionState = MutableStateFlow(SessionState.None)
    private lateinit var vm: SignInViewModel

    private val session = Session("tok", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active)

    @Before
    fun setUp() {
        every { repo.sessionState() } returns sessionState
        coEvery { deviceRepo.register() } returns AppResult.Success("dev-1")
        vm = SignInViewModel(repo, deviceRepo, DefaultErrorMapper(), CurrentAppVersion(code = 1, name = "1.0"))
    }

    @Test
    fun `initial state is idle`() {
        val s = vm.uiState.value
        assertFalse(s.isLoading)
        assertFalse(s.signedIn)
        assertNull(s.error)
    }

    @Test
    fun `valid credentials sign in and clear the secret`() = runTest {
        coEvery { repo.signIn(any(), any()) } returns AppResult.Success(session)
        vm.onIdentifierChange("op-1")
        vm.onSecretChange("pw")

        vm.submit()

        val s = vm.uiState.value
        assertTrue(s.signedIn)
        assertFalse(s.isLoading)
        assertEquals("", s.secret)
    }

    @Test
    fun `invalid credentials show a non-revealing error and no sign in`() = runTest {
        coEvery { repo.signIn(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
        vm.onIdentifierChange("op-1")
        vm.onSecretChange("wrong")

        vm.submit()

        val s = vm.uiState.value
        assertFalse(s.signedIn)
        assertFalse(s.lockedOut)
        assertNotNull(s.error)
    }

    @Test
    fun `account locked sets lockedOut and blocks submit`() = runTest {
        coEvery { repo.signIn(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.ACCOUNT_LOCKED))
        vm.onIdentifierChange("op-1")
        vm.onSecretChange("pw")

        vm.submit()

        val s = vm.uiState.value
        assertTrue(s.lockedOut)
        assertFalse(s.canSubmit)
    }

    @Test
    fun `timeout is surfaced and never reported as signed in`() = runTest {
        coEvery { repo.signIn(any(), any()) } returns AppResult.Timeout
        vm.onIdentifierChange("op-1")
        vm.onSecretChange("pw")

        vm.submit()

        val s = vm.uiState.value
        assertFalse(s.signedIn)
        assertNotNull(s.error)
    }

    @Test
    fun `expired session raises the session-ended notice`() {
        sessionState.value = SessionState.Expired
        assertTrue(vm.uiState.value.sessionEndedNotice)
    }

    @Test
    fun `injected build version is exposed for display`() {
        vm = SignInViewModel(repo, deviceRepo, DefaultErrorMapper(), CurrentAppVersion(code = 42, name = "2.3"))
        val s = vm.uiState.value
        assertEquals("2.3", s.versionName)
        assertEquals(42, s.versionCode)
    }
}
