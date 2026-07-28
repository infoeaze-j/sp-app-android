package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FaceScenario
import com.mediplus.spapp.dev.FakeData
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake face verify: returns the persisted [FaceScenario]. Always clears the frame (FR-017). */
class FakeFaceRepository @Inject constructor(
    private val store: DevSettingsStore,
) : FaceRepository {

    override suspend fun verify(memberNumber: String, frame: TransientFrame): AppResult<FaceDecision> {
        try {
            val settings = store.current()
            delay(settings.latencyMillis)
            return when (settings.face) {
                FaceScenario.PASS -> AppResult.Success(FakeData.faceDecisionPass)
                FaceScenario.FAIL_NO_MATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false),
                )
                FaceScenario.FAIL_LIVENESS -> AppResult.Success(
                    fail(liveness = LivenessResult.FAILED, sameSubject = true),
                )
                FaceScenario.SUBJECT_MISMATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false),
                )
                FaceScenario.LOCKED_OUT -> AppResult.Success(
                    fail(
                        liveness = LivenessResult.PASSED,
                        sameSubject = false,
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

    /** A rejected attempt issues no verification id — there is nothing for enrollment to spend. */
    private fun fail(
        liveness: LivenessResult,
        sameSubject: Boolean,
        lockout: FaceLockoutState = FaceLockoutState(lockedOut = false, remainingAttempts = 2, cooldownUntilMillis = null),
    ) = FaceDecision(
        decisionPass = false,
        liveness = liveness,
        sameSubject = sameSubject,
        lockout = lockout,
        verificationId = null,
    )
}
