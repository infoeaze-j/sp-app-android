package com.mediplus.faceverify.dev.diagnostics

import com.mediplus.faceverify.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject

/** Debug-only router: fake snapshot when the master toggle is on, else the real reader. */
class SwitchingDeviceDiagnostics @Inject constructor(
    private val real: AndroidDeviceDiagnostics,
    private val fake: FakeDeviceDiagnostics,
    private val store: DevSettingsStore,
) : DeviceDiagnostics {
    override suspend fun snapshot(): DeviceStateSnapshot =
        (if (store.current().fakeEnabled) fake else real).snapshot()
}
