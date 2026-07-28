package com.mediplus.spapp.ui.navigation

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.SessionCheck
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Provider
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Operator-initiated log out. The ViewModel only delegates to [AuthRepository.signOut]; the wipe of
 * session-bound state and the return to sign-in are already owned by the repository and the
 * [NavGraph] session guard respectively (FR-004a).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var repo: FakeAuthRepository
    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        sessionManager.set(Session("tok", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active))
        repo = FakeAuthRepository(sessionManager)
        vm = AppViewModel(sessionManager, repo)
    }

    @Test
    fun `session state is exposed for the nav guard`() {
        assertEquals(SessionState.Active, vm.sessionState.value)
    }

    @Test
    fun `logging out signs out through the repository`() = runTest {
        vm.logOut()

        assertEquals(1, repo.signOutCount)
    }

    @Test
    fun `logging out leaves the session no longer active`() = runTest {
        vm.logOut()

        assertNotEquals(SessionState.Active, vm.sessionState.value)
    }

    @Test
    fun `provider name is null when the session has none`() {
        assertEquals(null, vm.providerName.value)
    }

    @Test
    fun `provider name is exposed from the session`() {
        sessionManager.set(
            Session(
                "tok",
                Operator("op-1", "Sam"),
                expiresAt = null,
                state = SessionState.Active,
                provider = Provider("Riverside Clinic"),
            ),
        )
        val freshVm = AppViewModel(sessionManager, repo)

        assertEquals("Riverside Clinic", freshVm.providerName.value)
    }
}

/**
 * Mirrors the real repository's contract: local session-bound state is always cleared, whether or
 * not the server-side invalidation succeeds.
 */
private class FakeAuthRepository(
    private val sessionManager: SessionManager,
) : AuthRepository {

    var signOutCount = 0
        private set

    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> =
        throw UnsupportedOperationException("not exercised by these tests")

    override suspend fun signOut(): AppResult<Unit> {
        signOutCount++
        sessionManager.clearAll()
        return AppResult.Success(Unit)
    }

    override suspend fun revalidateSession(): SessionCheck =
        throw UnsupportedOperationException("not exercised by these tests")

    override fun sessionState(): StateFlow<SessionState> = sessionManager.sessionState
}
