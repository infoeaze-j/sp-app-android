package com.mediplus.spapp.dev.camera

import com.mediplus.spapp.core.camera.FaceCamera
import com.mediplus.spapp.core.camera.FaceCameraFactory
import com.mediplus.spapp.core.camera.RealFaceCameraFactory
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeSeam
import javax.inject.Inject
import javax.inject.Provider

/**
 * Debug-only router: emulate the camera while the CAMERA seam is faked, else use real CameraX.
 *
 * The camera seam is switchable on its own, so the operator can pair a real camera with a fake back
 * office (and the reverse). Note the reverse pairing — fake camera, real [FakeSeam.FACE] — sends
 * synthetic frame bytes to a real /face/verify; the Dev UI warns about that combination rather than
 * blocking it, since choosing it deliberately is the point of a per-seam toggle.
 */
class SwitchingFaceCameraFactory @Inject constructor(
    private val real: RealFaceCameraFactory,
    private val fake: Provider<FakeFaceCamera>,
    private val store: DevSettingsStore,
) : FaceCameraFactory {

    override suspend fun create(): FaceCamera =
        if (store.current().isFakeActive(FakeSeam.CAMERA)) fake.get() else real.create()
}
