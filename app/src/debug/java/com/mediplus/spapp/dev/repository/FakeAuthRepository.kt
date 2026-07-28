package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.SessionCheck
import com.mediplus.spapp.dev.AuthScenario
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeData
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Fake auth: returns the persisted [AuthScenario], driving the real [SessionManager] on success. */
class FakeAuthRepository @Inject constructor(
    private val store: DevSettingsStore,
    private val sessionManager: SessionManager,
) : AuthRepository {

    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.auth) {
            AuthScenario.SUCCESS -> {
                sessionManager.set(FakeData.session)
                sessionManager.setVerificationWindow(settings.verificationWindowSeconds.seconds)
                AppResult.Success(FakeData.session)
            }
            AuthScenario.INVALID_CREDENTIALS ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
            AuthScenario.ACCOUNT_LOCKED, AuthScenario.THROTTLED ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.ACCOUNT_LOCKED))
            AuthScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        sessionManager.clearAll()
        return AppResult.Success(Unit)
    }

    /**
     * The fake back office never expires a session, so revalidation always says it stands. Session
     * loss is exercised in a debug build through Dev Settings' "force expire" action, which drives
     * [SessionManager] directly.
     */
    override suspend fun revalidateSession(): SessionCheck = SessionCheck.Valid

    override fun sessionState(): StateFlow<SessionState> = sessionManager.sessionState
}
