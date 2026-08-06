package com.mediplus.spapp.core.camera

import com.google.mlkit.vision.face.FaceDetector
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * The ML Kit detector owns native, off-heap resources and implements [java.io.Closeable]. The
 * analyzer is rebuilt on every camera bind — once per face check — so a detector that is never
 * closed leaks once per patient (Principle IV).
 */
class FaceFramingAnalyzerTest {

    @Test
    fun `closing the analyzer closes the detector it owns`() {
        val detector = mockk<FaceDetector>()
        justRun { detector.close() }
        val analyzer = FaceFramingAnalyzer(onGuidance = {}, detector = detector)

        analyzer.close()

        verify(exactly = 1) { detector.close() }
    }
}
