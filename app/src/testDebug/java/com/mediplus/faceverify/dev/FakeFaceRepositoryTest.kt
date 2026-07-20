package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.repository.FakeFaceRepository
import com.mediplus.faceverify.domain.model.LivenessResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `liveness failure returns a failed decision`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.FAIL_LIVENESS, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertFalse(decision.decisionPass)
        assertEquals(LivenessResult.FAILED, decision.liveness)
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
