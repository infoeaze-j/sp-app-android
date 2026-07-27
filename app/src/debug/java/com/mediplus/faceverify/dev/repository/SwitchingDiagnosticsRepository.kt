package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DiagnosticsScenario
import javax.inject.Inject

/** Debug-only router: canned scenario when the master toggle is on, else the real backend. */
class SwitchingDiagnosticsRepository @Inject constructor(
    private val real: DiagnosticsRepositoryImpl,
    private val store: DevSettingsStore,
) : DiagnosticsRepository {

    // The fake is stateful (poll counter), so it must survive across poll()/report() calls. Rebuilt
    // only when the operator switches scenario in the Dev UI — reconstructing per call would reset the
    // counter and defeat REQUESTED_ONCE / ALWAYS_REQUESTED.
    private var fake: FakeDiagnosticsRepository? = null
    private var fakeScenario: DiagnosticsScenario? = null

    override suspend fun poll(): AppResult<String?> = pick().poll()

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        pick().report(requestId, snapshot)

    private suspend fun pick(): DiagnosticsRepository {
        val settings = store.current()
        if (!settings.fakeEnabled) return real
        return fake?.takeIf { fakeScenario == settings.diagnostics }
            ?: FakeDiagnosticsRepository(settings.diagnostics).also {
                fake = it
                fakeScenario = settings.diagnostics
            }
    }
}
