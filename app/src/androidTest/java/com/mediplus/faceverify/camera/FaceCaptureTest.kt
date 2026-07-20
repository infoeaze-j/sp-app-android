package com.mediplus.faceverify.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediplus.faceverify.core.camera.FaceFramingAnalyzer
import com.mediplus.faceverify.core.camera.FramingGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T040 — instrumented camera permission + framing guidance (FR-016). Verifies the CAMERA permission
 * is declared and that the framing analyzer's pure decision reports NO_FACE for an empty frame.
 */
@RunWith(AndroidJUnit4::class)
class FaceCaptureTest {

    @Test
    fun cameraPermissionIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions?.contains(Manifest.permission.CAMERA) == true)
    }

    @Test
    fun emptyFrameReportsNoFace() {
        val analyzer = FaceFramingAnalyzer(onGuidance = {})
        assertEquals(FramingGuidance.NO_FACE, analyzer.evaluate(emptyList(), 1000))
    }
}
