package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.DiagnosticsRepository
import com.mediplus.spapp.dev.DiagnosticsScenario

/** Canned poll/report outcomes for the dev stack, driven by [DiagnosticsScenario]. */
class FakeDiagnosticsRepository(private val scenario: DiagnosticsScenario) : DiagnosticsRepository {

    private var counter = 0

    override suspend fun poll(): AppResult<String?> = when (scenario) {
        DiagnosticsScenario.OFF -> AppResult.Success(null)
        DiagnosticsScenario.REQUESTED_ONCE ->
            if (counter++ == 0) AppResult.Success("dev-req-1") else AppResult.Success(null)
        DiagnosticsScenario.ALWAYS_REQUESTED -> AppResult.Success("dev-req-${counter++}")
        DiagnosticsScenario.POLL_FAILS ->
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        DiagnosticsScenario.REPORT_FAILS -> AppResult.Success("dev-req-1")
    }

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        if (scenario == DiagnosticsScenario.REPORT_FAILS) {
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        } else {
            AppResult.Success(Unit)
        }
}
