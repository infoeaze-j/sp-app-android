package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Face verification (FR-012–FR-015) — `face.verifications.store` in docs/openapi.json. The server
 * owns the match threshold and the liveness verdict (Decision 2); the app submits a transient frame
 * and enforces what comes back.
 *
 * A FAIL is a successful call reporting a rejected attempt, not an error. A pass issues a
 * `verificationId`, which is what the enrollment step spends — the server checks it belongs to this
 * member, has not expired and has not already been used.
 */
interface FaceApi {

    @POST("face/verifications")
    suspend fun verify(@Body body: VerifyFaceRequest): Response<VerificationResource>
}

@Serializable
data class VerifyFaceRequest(
    val memberNumber: String,
    /** Base64 of a single still frame; the size ceiling is enforced server-side on decoded bytes. */
    val image: String,
    val capture: CaptureDto = CaptureDto(),
)

@Serializable
data class CaptureDto(
    val hasLivenessChallengeResponse: Boolean = false,
)

@Serializable
data class VerificationResource(
    /** Present only on a pass; this is what [StoreEnrollmentRequest.verificationId] spends. */
    val verificationId: String? = null,
    val decision: String,
    val liveness: String = "",
    val sameSubject: Boolean = false,
    val expiresAt: String? = null,
    val lockout: LockoutDto = LockoutDto(),
)

@Serializable
data class LockoutDto(
    val lockedOut: Boolean = false,
    val remainingAttempts: Int? = null,
    val cooldownUntil: String? = null,
)
