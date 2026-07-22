package com.mediplus.faceverify.core.camera

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Whether this device can run the live face check at all. */
enum class CameraAvailability { AVAILABLE, NO_CAMERA }

/**
 * The camera the face-check screen drives (FR-016, FR-017). The implementation owns CameraX setup
 * and teardown, so no `androidx.camera` type ever reaches the ViewModel — the same containment
 * [com.mediplus.faceverify.core.nfc.MemberCardReader] gives the NFC path.
 *
 * The preview surface is created by the camera itself rather than the screen, so an alternative
 * implementation (the debug fake) can supply a placeholder that needs no hardware.
 */
interface FaceCamera {

    /** Whether a usable camera exists. Checked before binding; NO_CAMERA halts the step. */
    suspend fun isAvailable(): CameraAvailability

    /** The view to place in the layout. Must be passed back to [bind] unchanged. */
    fun createPreviewView(context: Context): View

    /** Start the preview and the framing analysis, reporting guidance until [release]. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    )

    /**
     * Capture one frame. The bytes live only in the returned [TransientFrame] (FR-017).
     * Returns null when the capture fails — the caller must surface that, not ignore it.
     */
    suspend fun capture(): TransientFrame?

    /** Stop analysis and drop hardware handles. Idempotent. */
    fun release()
}

/**
 * Resolves which [FaceCamera] to use, once per screen entry.
 *
 * A factory rather than a switching decorator (unlike [com.mediplus.faceverify.core.nfc.MemberCardReader]):
 * [FaceCamera.createPreviewView] and [FaceCamera.bind] are synchronous, so they cannot read the
 * suspending dev-settings store per call. Deciding once also means the implementation cannot swap
 * mid-capture, and each entry gets a fresh instance with no leftover CameraX state.
 */
interface FaceCameraFactory {
    suspend fun create(): FaceCamera
}

/**
 * The camera is a UI-layer concern the ViewModel never calls, so the screen resolves the factory
 * directly rather than having it injected into [com.mediplus.faceverify.ui.facecheck.FaceCheckViewModel].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaceCameraEntryPoint {
    fun faceCameraFactory(): FaceCameraFactory
}
