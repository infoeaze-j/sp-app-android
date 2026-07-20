package com.mediplus.faceverify.domain.model

/**
 * The authenticated staff user operating the app (assisted mode, FR-031). Operator identity is
 * always derived from the session — never entered — and is never used as a security decision on
 * its own. Consent for biometrics is obtained from the *patient*, not the operator.
 */
data class Operator(
    val operatorId: String,
    val displayName: String?,
    val permissions: Set<String> = emptySet(),
)

/**
 * Proof of authenticated access attached to every protected request (FR-002).
 *
 * @param token opaque bearer credential — held in memory only and redacted from all logs (FR-029)
 * @param operator the owner of the session
 * @param expiresAt client-visible expiry hint in epoch millis; the server remains authoritative
 * @param state current [SessionState]
 */
data class Session(
    val token: String,
    val operator: Operator,
    val expiresAt: Long?,
    val state: SessionState = SessionState.Active,
) {
    /** Never leak the token via toString() (FR-029). */
    override fun toString(): String =
        "Session(operator=${operator.operatorId}, expiresAt=$expiresAt, state=$state, token=<redacted>)"
}

/**
 * Lifecycle of a session. No protected action is permitted unless [Active] (FR-003); any transition
 * away from [Active] forces re-authentication and discards all verification state (FR-004, FR-004a).
 */
enum class SessionState {
    /** Signed in with a valid session. */
    Active,

    /** The session timed out; re-authentication is required. */
    Expired,

    /** The session was invalidated (e.g. server 401 mid-flow); re-authentication is required. */
    Invalidated,

    /** No session exists. */
    None,
}
