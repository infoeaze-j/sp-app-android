package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.remote.AuthApi
import com.mediplus.spapp.data.remote.LoginRequest
import com.mediplus.spapp.data.remote.SessionResource
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Provider
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.net.HttpURLConnection
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The stable auth seam (contracts/client-interfaces.md). Maps back-office responses to
 * [AppResult] and feeds the [SessionManager]; the ViewModel/UI never touch the wire shape.
 */
interface AuthRepository {
    suspend fun signIn(identifier: String, secret: String): AppResult<Session>
    suspend fun signOut(): AppResult<Unit>
    fun sessionState(): StateFlow<SessionState>
}

class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApi,
    private val sessionManager: SessionManager,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AuthRepository {

    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> =
        apiCall(dispatcher, { api.login(LoginRequest(identifier, secret)) }) { response ->
            val body = response.body()
            when {
                // The spec answers a good credential with 201, not 200.
                response.isSuccessful && body != null -> onLoginSuccess(body)
                // Login is unauthenticated, so the spec reports a bad credential as a 422 validation
                // failure; a 401 means the same thing. Either way: non-revealing, no session (FR-005).
                response.code() == UNPROCESSABLE_ENTITY ||
                    response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
                // 423 locked / 429 throttled — server-owned lockout (FR-006).
                response.code() == LOCKED || response.code() == TOO_MANY_REQUESTS ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.ACCOUNT_LOCKED))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC))
            }
        }

    private fun onLoginSuccess(body: SessionResource): AppResult<Session> {
        val session = body.toSession()
        sessionManager.set(session)
        // Capture the back-office-owned freshness window; absent → stale (fail-safe) (FR-026).
        sessionManager.setVerificationWindow(body.policy.verificationTtlSeconds?.seconds)
        return AppResult.Success(session)
    }

    override suspend fun signOut(): AppResult<Unit> {
        // Attempt server-side invalidation, but always clear local session-bound state (FR-004a).
        runCatching { apiCall(dispatcher, { api.logout() }) { AppResult.Success(Unit) } }
        sessionManager.clearAll()
        return AppResult.Success(Unit)
    }

    override fun sessionState(): StateFlow<SessionState> = sessionManager.sessionState

    private companion object {
        const val LOCKED = 423
        const val UNPROCESSABLE_ENTITY = 422
        const val TOO_MANY_REQUESTS = 429
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun SessionResource.toSession(): Session = Session(
    token = token,
    operator = Operator(
        operatorId = operator.id,
        displayName = operator.displayName,
        permissions = operator.permissions.toSet(),
        identifier = operator.identifier,
    ),
    expiresAt = expiresAt?.let { parseEpochMillisOrNull(it) },
    state = SessionState.Active,
    // A provider without a name is not shown rather than shown blank (fail-open).
    provider = provider.name.takeIf { it.isNotBlank() }?.let {
        Provider(name = it, id = provider.id, code = provider.code, timezone = provider.timezone)
    },
)

private fun parseEpochMillisOrNull(iso: String): Long? =
    runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
