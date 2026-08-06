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
 * What one revalidation learned
 * (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md).
 *
 * Only [Ended] means the session is over, and by the time it is returned
 * [com.mediplus.spapp.core.network.AuthInterceptor] has already acted on it. Modelling the
 * third case as its own value rather than folding it into a nullable or a `Boolean` is the point:
 * fail-open stops being a rule someone has to remember and becomes a case the compiler makes you name.
 */
enum class SessionCheck {
    /** A 2xx. The session stands. */
    Valid,

    /** An explicit 401. The session has already been invalidated by the interceptor (FR-004, FR-004a). */
    Ended,

    /** 5xx, an unexpected status, or a transport failure. The session is left completely alone. */
    Unknown,
}

/**
 * The stable auth seam (contracts/client-interfaces.md). Maps back-office responses to
 * [AppResult] and feeds the [SessionManager]; the ViewModel/UI never touch the wire shape.
 */
interface AuthRepository {
    suspend fun signIn(identifier: String, secret: String): AppResult<Session>
    suspend fun signOut(): AppResult<Unit>

    /**
     * Ask the back office whether this session is still live (FR-004). Called on every foregrounding
     * by [com.mediplus.spapp.core.session.SessionRevalidator] so an expiry is discovered before the
     * operator involves a patient, rather than passively at the next protected call.
     *
     * Never mutates session state: a 401 is acted on by
     * [com.mediplus.spapp.core.network.AuthInterceptor] — one rule, one place — and anything that is
     * not a definite answer returns [SessionCheck.Unknown] so a flaky connection can never force a
     * re-login.
     */
    suspend fun revalidateSession(): SessionCheck
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
        // Attempt server-side invalidation, but always clear local session-bound state (FR-004a) —
        // which is what the `finally` guarantees, cancellation included. There is nothing to catch:
        // apiCall already classifies every transport failure into an AppResult. The runCatching that
        // used to wrap this also caught CancellationException, so a cancelled sign-out kept running
        // instead of unwinding.
        try {
            apiCall(dispatcher, { api.logout() }) { AppResult.Success(Unit) }
        } finally {
            sessionManager.clearAll()
        }
        return AppResult.Success(Unit)
    }

    override suspend fun revalidateSession(): SessionCheck {
        val result = apiCall(dispatcher, { api.session() }) { response ->
            when {
                response.isSuccessful -> AppResult.Success(SessionCheck.Valid)
                // AuthInterceptor has already invalidated the session by the time we get here; this
                // value is returned so callers and tests can assert on it, not so anything must act.
                response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    AppResult.Success(SessionCheck.Ended)
                else -> AppResult.Success(SessionCheck.Unknown)
            }
        }
        // apiCall maps a socket timeout to Timeout and any other IO failure to TransientFailure. Both
        // mean "we learned nothing", which is exactly Unknown — the session is left untouched.
        return (result as? AppResult.Success)?.data ?: SessionCheck.Unknown
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
