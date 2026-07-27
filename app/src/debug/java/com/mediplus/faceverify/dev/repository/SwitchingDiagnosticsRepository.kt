package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject

/** Debug-only router: canned scenario when the master toggle is on, else the real backend. */
class SwitchingDiagnosticsRepository @Inject constructor(
    private val real: DiagnosticsRepositoryImpl,
    private val store: DevSettingsStore,
) : DiagnosticsRepository {

    override suspend fun poll(): AppResult<String?> = pick().poll()

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        pick().report(requestId, snapshot)

    private suspend fun pick(): DiagnosticsRepository {
        val settings = store.current()
        return if (settings.fakeEnabled) FakeDiagnosticsRepository(settings.diagnostics) else real
    }
}
