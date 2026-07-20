package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.dev.AuthScenario
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
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

    override fun sessionState(): StateFlow<SessionState> = sessionManager.sessionState
}
