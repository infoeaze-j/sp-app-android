package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.dev.repository.FakeAuthRepository
import com.mediplus.faceverify.domain.model.SessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class FakeAuthRepositoryTest {

    private fun repo(store: TestDevSettingsStore, session: InMemorySessionManager) =
        FakeAuthRepository(store, session)

    @Test
    fun `success sets the session and verification window`() = runTest {
        val session = InMemorySessionManager()
        val store = TestDevSettingsStore(DevSettings(latencyMillis = 0L, verificationWindowSeconds = 120L))

        val result = repo(store, session).signIn("demo", "demo")

        assertTrue(result is AppResult.Success)
        assertEquals(SessionState.Active, session.sessionState.value)
        assertEquals(120.seconds, session.verificationWindow.value)
    }

    @Test
    fun `invalid credentials scenario rejects without a session`() = runTest {
        val session = InMemorySessionManager()
        val store = TestDevSettingsStore(DevSettings(auth = AuthScenario.INVALID_CREDENTIALS, latencyMillis = 0L))

        val result = repo(store, session).signIn("demo", "demo")

        val error = (result as AppResult.BusinessRejection).error
        assertEquals(BusinessCode.INVALID_CREDENTIALS, error.code)
        assertEquals(SessionState.None, session.sessionState.value)
    }

    @Test
    fun `server error scenario is a transient failure`() = runTest {
        val store = TestDevSettingsStore(DevSettings(auth = AuthScenario.SERVER_ERROR, latencyMillis = 0L))

        val result = repo(store, InMemorySessionManager()).signIn("demo", "demo")

        assertTrue((result as AppResult.TransientFailure).error is AppError.Transient)
    }
}
