package com.mediplus.faceverify.dev.camera

import com.mediplus.faceverify.core.camera.FaceCamera
import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject
import javax.inject.Provider

/**
 * Debug-only router: emulate the camera when the master fake toggle is on, else use real CameraX.
 *
 * Follows `fakeEnabled` rather than a dedicated switch — the camera fake and the fake back office
 * are used together, so synthetic frame bytes can never reach a real /face/verify. If real camera
 * plus fake back office is ever needed on a device, add the override here.
 */
class SwitchingFaceCameraFactory @Inject constructor(
    private val real: RealFaceCameraFactory,
    private val fake: Provider<FakeFaceCamera>,
    private val store: DevSettingsStore,
) : FaceCameraFactory {

    override suspend fun create(): FaceCamera =
        if (store.current().fakeEnabled) fake.get() else real.create()
}
