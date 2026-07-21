package com.mediplus.faceverify.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Face verification endpoint (FR-012–FR-015). The server owns the match threshold and liveness
 * verdict (Decision 2); the app submits a transient frame and enforces the returned decision.
 */
interface FaceApi {

    @POST("face/verify")
    suspend fun verify(@Body body: FaceVerifyRequest): Response<FaceVerifyResponse>
}

@Serializable
data class FaceVerifyRequest(
    val memberNumber: String,
    val image: String,
    val captureMeta: CaptureMetaDto = CaptureMetaDto(),
)

@Serializable
data class CaptureMetaDto(
    val hasLivenessChallengeResponse: Boolean = false,
)

@Serializable
data class FaceVerifyResponse(
    val decision: String,
    val reason: String? = null,
    val liveness: String,
    val sameSubject: Boolean = false,
    val lockout: LockoutDto = LockoutDto(),
)

@Serializable
data class LockoutDto(
    val lockedOut: Boolean = false,
    val remainingAttempts: Int? = null,
    val cooldownUntil: String? = null,
)
