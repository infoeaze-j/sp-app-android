package com.mediplus.spapp.core.camera

import android.content.Context
import com.google.mlkit.vision.face.FaceDetector
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `release()` used to detach the analyzer from the `ImageAnalysis` use case without closing it, so
 * the native ML Kit detector it wrapped survived every screen exit. The screen takes a fresh camera
 * from the factory on each entry, which made that one leak per face check (Principle IV).
 */
class CameraXFaceCameraTest {

    @Test
    fun `releasing closes the framing analyzer and drops the reference`() {
        val detector = mockk<FaceDetector>()
        justRun { detector.close() }
        val camera = CameraXFaceCamera(mockk<Context>(relaxed = true))
        camera.framingAnalyzer = FaceFramingAnalyzer(onGuidance = {}, detector = detector)

        camera.release()

        verify(exactly = 1) { detector.close() }
        assertNull(camera.framingAnalyzer)
    }

    @Test
    fun `releasing without a bound analyzer is a no-op`() {
        val camera = CameraXFaceCamera(mockk<Context>(relaxed = true))

        camera.release()

        assertNull(camera.framingAnalyzer)
    }
}
