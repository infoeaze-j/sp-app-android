package com.mediplus.spapp.dev

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.dev.repository.FakeFaceRepository
import com.mediplus.spapp.domain.model.LivenessResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFaceRepositoryTest {

    private fun frame() = TransientFrame(byteArrayOf(1, 2, 3))

    @Test
    fun `pass returns a passing decision and clears the frame`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.PASS, latencyMillis = 0L))
        val f = frame()

        val result = FakeFaceRepository(store).verify("X123", f)

        assertTrue((result as AppResult.Success).data.decisionPass)
        assertTrue("frame must be cleared after verify (FR-017)", f.isCleared)
    }

    /**
     * The fake journey only reaches the enrollment step if the fake face check issues a verification
     * id, because `AddServiceUseCase` refuses to submit without one. This is that link.
     */
    @Test
    fun `pass issues the verification id enrollment spends`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.PASS, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertEquals(FakeData.verificationId, decision.verificationId)
    }

    @Test
    fun `liveness failure returns a failed decision and issues no verification id`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.FAIL_LIVENESS, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertFalse(decision.decisionPass)
        assertEquals(LivenessResult.FAILED, decision.liveness)
        assertNull(decision.verificationId)
    }

    @Test
    fun `locked out populates the lockout state`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.LOCKED_OUT, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertTrue(decision.lockout.lockedOut)
    }

    @Test
    fun `server error clears the frame too`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.SERVER_ERROR, latencyMillis = 0L))
        val f = frame()

        val result = FakeFaceRepository(store).verify("X123", f)

        assertTrue(result is AppResult.TransientFailure)
        assertTrue(f.isCleared)
    }
}
