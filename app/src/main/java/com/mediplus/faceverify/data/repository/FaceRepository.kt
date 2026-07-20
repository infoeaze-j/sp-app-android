package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.CaptureMetaDto
import com.mediplus.faceverify.data.remote.FaceApi
import com.mediplus.faceverify.data.remote.FaceVerifyRequest
import com.mediplus.faceverify.data.remote.FaceVerifyResponse
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Instant
import javax.inject.Inject

/**
 * Submits a transient live frame for the authoritative face decision (FR-012–FR-015). The frame is
 * held in memory only for this single request and is ALWAYS cleared once the decision returns or the
 * call aborts — nothing biometric is ever persisted (FR-017).
 */
interface FaceRepository {
    suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision>
}

class FaceRepositoryImpl @Inject constructor(
    private val api: FaceApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : FaceRepository {

    override suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision> {
        try {
            val image = frame.asBase64()
                ?: return AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            return apiCall(
                dispatcher,
                { api.verify(FaceVerifyRequest(documentNumber, image, CaptureMetaDto(hasLivenessChallengeResponse = true))) },
            ) { response ->
                val body = response.body()
                when {
                    response.isSuccessful && body != null -> AppResult.Success(body.toDecision())
                    response.code() in SERVER_ERROR_RANGE ->
                        AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                    else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC))
                }
            }
        } finally {
            // The image never outlives a single submission — clear on success, failure, or abort.
            frame.clear()
        }
    }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun FaceVerifyResponse.toDecision() = FaceDecision(
    decisionPass = decision.equals("PASS", ignoreCase = true),
    liveness = if (liveness.equals("PASS", ignoreCase = true)) LivenessResult.PASSED else LivenessResult.FAILED,
    sameSubject = sameSubject,
    reason = reason,
    lockout = FaceLockoutState(
        lockedOut = lockout.lockedOut,
        remainingAttempts = lockout.remainingAttempts,
        cooldownUntilMillis = lockout.cooldownUntil?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    ),
)
