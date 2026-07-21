package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FaceScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake face verify: returns the persisted [FaceScenario]. Always clears the frame (FR-017). */
class FakeFaceRepository @Inject constructor(
    private val store: DevSettingsStore,
) : FaceRepository {

    override suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision> {
        try {
            val settings = store.current()
            delay(settings.latencyMillis)
            return when (settings.face) {
                FaceScenario.PASS -> AppResult.Success(FakeData.faceDecisionPass)
                FaceScenario.FAIL_NO_MATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false, reason = "No match"),
                )
                FaceScenario.FAIL_LIVENESS -> AppResult.Success(
                    fail(liveness = LivenessResult.FAILED, sameSubject = true, reason = "Liveness failed"),
                )
                FaceScenario.SUBJECT_MISMATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false, reason = "Different subject"),
                )
                FaceScenario.LOCKED_OUT -> AppResult.Success(
                    fail(
                        liveness = LivenessResult.PASSED,
                        sameSubject = false,
                        reason = "Locked out",
                        lockout = FaceLockoutState(lockedOut = true, remainingAttempts = 0, cooldownUntilMillis = null),
                    ),
                )
                FaceScenario.SERVER_ERROR ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        } finally {
            frame.clear()
        }
    }

    private fun fail(
        liveness: LivenessResult,
        sameSubject: Boolean,
        reason: String,
        lockout: FaceLockoutState = FaceLockoutState(lockedOut = false, remainingAttempts = 2, cooldownUntilMillis = null),
    ) = FaceDecision(
        decisionPass = false,
        liveness = liveness,
        sameSubject = sameSubject,
        reason = reason,
        lockout = lockout,
    )
}
