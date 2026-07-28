package com.mediplus.spapp.dev.diagnostics

import com.mediplus.spapp.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.spapp.core.diagnostics.DeviceDiagnostics
import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeSeam
import javax.inject.Inject

/** Debug-only router: fake snapshot while the DEVICE_STATE seam is faked, else the real reader. */
class SwitchingDeviceDiagnostics @Inject constructor(
    private val real: AndroidDeviceDiagnostics,
    private val fake: FakeDeviceDiagnostics,
    private val store: DevSettingsStore,
) : DeviceDiagnostics {
    override suspend fun snapshot(): DeviceStateSnapshot =
        (if (store.current().isFakeActive(FakeSeam.DEVICE_STATE)) fake else real).snapshot()
}
