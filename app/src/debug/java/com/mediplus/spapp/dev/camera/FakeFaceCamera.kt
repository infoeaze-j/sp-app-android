package com.mediplus.spapp.dev.camera

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mediplus.spapp.core.camera.CameraAvailability
import com.mediplus.spapp.core.camera.FaceCamera
import com.mediplus.spapp.core.camera.FramingGuidance
import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.dev.CameraScenario
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Emulated camera: lets the whole face-check step run on an emulator or a camera-less device.
 * The preview is a flat placeholder and the framing guidance is scripted, so the screen still moves
 * through no-face → good → capture exactly as on device.
 *
 * It never renders text of its own: the screen already shows the live guidance string directly
 * beneath the preview.
 */
class FakeFaceCamera @Inject constructor(
    private val store: DevSettingsStore,
) : FaceCamera {

    private var guidanceJob: Job? = null

    override suspend fun isAvailable(): CameraAvailability = when (store.current().camera) {
        CameraScenario.NO_CAMERA_HARDWARE -> CameraAvailability.NO_CAMERA
        else -> CameraAvailability.AVAILABLE
    }

    override fun createPreviewView(context: Context): View =
        View(context).apply { setBackgroundColor(PLACEHOLDER_COLOR) }

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    ) {
        guidanceJob?.cancel()
        guidanceJob = lifecycleOwner.lifecycleScope.launch {
            val settings = store.current()
            onGuidance(FramingGuidance.NO_FACE) // operator is still positioning the patient
            delay(settings.latencyMillis)
            onGuidance(
                when (settings.camera) {
                    CameraScenario.NEVER_GOOD -> FramingGuidance.FACE_TOO_SMALL
                    else -> FramingGuidance.GOOD
                },
            )
        }
    }

    override suspend fun capture(): TransientFrame? {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.camera) {
            CameraScenario.CAPTURE_ERROR -> null
            // copyOf: TransientFrame.clear() zeroes the array, which would blank the shared constant.
            else -> TransientFrame(FakeData.faceFrameBytes.copyOf())
        }
    }

    override fun release() {
        guidanceJob?.cancel()
        guidanceJob = null
    }

    private companion object {
        const val PLACEHOLDER_COLOR = Color.DKGRAY
    }
}
