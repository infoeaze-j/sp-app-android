package com.mediplus.spapp.dev

import com.mediplus.spapp.core.camera.CameraAvailability
import com.mediplus.spapp.dev.camera.FakeFaceCamera
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FakeFaceCameraTest {

    private fun camera(scenario: CameraScenario) =
        FakeFaceCamera(TestDevSettingsStore(DevSettings(camera = scenario, latencyMillis = 0L)))

    @Test
    fun `success reports an available camera`() = runTest {
        assertEquals(CameraAvailability.AVAILABLE, camera(CameraScenario.SUCCESS).isAvailable())
    }

    @Test
    fun `no camera hardware reports unavailable`() = runTest {
        assertEquals(
            CameraAvailability.NO_CAMERA,
            camera(CameraScenario.NO_CAMERA_HARDWARE).isAvailable(),
        )
    }

    @Test
    fun `success captures a non-empty frame`() = runTest {
        val frame = camera(CameraScenario.SUCCESS).capture()

        assertNotNull(frame)
        assertFalse(frame!!.isCleared)
    }

    @Test
    fun `capture error returns no frame`() = runTest {
        assertNull(camera(CameraScenario.CAPTURE_ERROR).capture())
    }

    /**
     * TransientFrame.clear() zeroes its array in place. If the fake handed out the shared constant,
     * clearing the first frame would blank every later capture in the process.
     */
    @Test
    fun `each capture returns independent bytes`() = runTest {
        val cam = camera(CameraScenario.SUCCESS)
        val expected = FakeData.faceFrameBytes.copyOf()

        val first = cam.capture()!!
        first.clear()
        val second = cam.capture()!!

        assertArrayEquals(expected, FakeData.faceFrameBytes)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(expected),
            second.asBase64(),
        )
    }
}
