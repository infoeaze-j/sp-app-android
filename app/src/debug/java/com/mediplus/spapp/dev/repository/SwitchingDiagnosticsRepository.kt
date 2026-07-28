package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.DiagnosticsRepository
import com.mediplus.spapp.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.DiagnosticsScenario
import com.mediplus.spapp.dev.FakeSeam
import javax.inject.Inject

/** Debug-only router: canned scenario while the DIAGNOSTICS seam is faked, else the real backend. */
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
        if (!settings.isFakeActive(FakeSeam.DIAGNOSTICS)) return real
        return fake?.takeIf { fakeScenario == settings.diagnostics }
            ?: FakeDiagnosticsRepository(settings.diagnostics).also {
                fake = it
                fakeScenario = settings.diagnostics
            }
    }
}
