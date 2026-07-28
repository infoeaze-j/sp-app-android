package com.mediplus.spapp.core.network

import com.mediplus.spapp.core.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the current session token to every protected request (FR-002) and treats an HTTP 401 as
 * session expiry/invalidation: it clears all verification state and forces re-authentication
 * (FR-004, FR-004a). The token is added as a header only — it is never logged (redaction lives in
 * the logging interceptor).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
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
