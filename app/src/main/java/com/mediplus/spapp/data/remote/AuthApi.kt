package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.network.NO_AUTH_HEADER_LINE
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * Authentication & session endpoints (FR-001–FR-006) — `auth.login`, `auth.logout` and
 * `auth.session` in docs/openapi.json.
 *
 * Login is unauthenticated and answers 201 with a `SessionResource`; every other call carries the
 * returned bearer token. `GET /auth/session` re-reads the same resource, so a client resuming from
 * the background can ask about expiry outright instead of inferring it from the next 401.
 */
interface AuthApi {

    /** `security: []` in the spec — the one call that must go out with no bearer token. */
    @Headers(NO_AUTH_HEADER_LINE)
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<SessionResource>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/session")
    suspend fun session(): Response<SessionResource>
}

@Serializable
data class LoginRequest(
    val identifier: String,
    val secret: String,
    /** Optional label for the issued token, so a lost tablet's session can be told apart. */
    val deviceName: String? = null,
)

/**
 * Everything the back office decides about a session in one resource: who is signed in, which
 * provider they act for, and the policy the client must enforce until it signs out again.
 */
@Serializable
data class SessionResource(
    val token: String,
    val expiresAt: String? = null,
    val operator: OperatorDto,
    /**
     * Required by the spec, defaulted here so a provider the back office omits leaves the header
     * subtitle blank rather than failing the whole sign-in.
     */
    val provider: ProviderDto = ProviderDto(),
    /**
     * Required by the spec, defaulted here because an absent policy must degrade to "no freshness
     * window" — which [com.mediplus.spapp.core.session.SessionManager] treats as immediately stale
     * (FR-026) — rather than to a failed sign-in.
     */
    val policy: SessionPolicyDto = SessionPolicyDto(),
    val serverTime: String? = null,
)

@Serializable
data class OperatorDto(
    val id: String,
    /** The credential the operator signs in with; display-only, never a security input. */
    val identifier: String = "",
    val displayName: String? = null,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class ProviderDto(
    val id: String = "",
    val code: String = "",
    val name: String = "",
    val timezone: String = "",
)

@Serializable
data class SessionPolicyDto(
    /** Back-office-owned verification freshness window; absent means immediately stale (FR-026). */
    val verificationTtlSeconds: Long? = null,
    val sessionTtlSeconds: Long? = null,
    val face: FacePolicyDto = FacePolicyDto(),
)

@Serializable
data class FacePolicyDto(
    val maxAttempts: Int? = null,
    val lockoutSeconds: Long? = null,
)
