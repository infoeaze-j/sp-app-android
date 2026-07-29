package com.mediplus.spapp.core.network

import com.mediplus.spapp.core.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Header name marking a request as belonging to an endpoint the contract leaves unauthenticated. */
internal const val NO_AUTH_HEADER = "X-No-Auth"

/**
 * Value to put on such an endpoint's Retrofit declaration: `@Headers(NO_AUTH_HEADER_LINE)`. The
 * marker is stripped by [AuthInterceptor] and never reaches the wire.
 */
internal const val NO_AUTH_HEADER_LINE = "$NO_AUTH_HEADER: true"

/**
 * Attaches the current session token to every protected request (FR-002) and treats an HTTP 401 as
 * session expiry/invalidation: it clears all verification state and forces re-authentication
 * (FR-004, FR-004a). The token is added as a header only — it is never logged (redaction lives in
 * the logging interceptor).
 *
 * Endpoints the back office declares unauthenticated (`security: []` in docs/openapi.json) opt out
 * with [NO_AUTH_HEADER_LINE] and are passed through untouched — see the note on sign-in below.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Sign-in is the endpoint that makes this matter. It is unauthenticated by contract, and a
        // bearer token on it is answered with 401 — which, being a 401 on a request that carried a
        // token, would then be read below as a session loss. The operator would be told their
        // session expired, and the sign-in attempt they just made would be thrown away, on every
        // attempt made while any session still sat in memory.
        val original = chain.request()
        if (original.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }

        val token = sessionManager.session.value?.token
        val hadToken = !token.isNullOrEmpty()
        val request = if (hadToken) {
            chain.request().newBuilder()
                .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)
        // Only a 401 on a request that actually carried a session token means the session was
        // invalidated. A 401 on sign-in is "invalid credentials", not a session loss (FR-004 vs FR-005).
        //
        // The token is re-read and compared because a 401 can arrive late: the session it was sent
        // for may already have been replaced (the operator re-signed in while a slow request was in
        // flight) or deliberately ended (they logged out). Ending whatever session happens to be
        // current would bounce a freshly signed-in operator back to sign-in, or turn a chosen
        // log out into a "session ended" notice. Only the session that actually got the 401 ends.
        if (hadToken && response.code == HttpURLConnection.HTTP_UNAUTHORIZED &&
            sessionManager.session.value?.token == token
        ) {
            sessionManager.markSessionInvalidated()
        }
        return response
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
