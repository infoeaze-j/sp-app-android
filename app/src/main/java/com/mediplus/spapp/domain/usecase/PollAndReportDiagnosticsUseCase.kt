package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.diagnostics.DeviceDiagnostics
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.DiagnosticsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of one poll tick. Purely diagnostic — nothing user-facing. */
enum class PollOutcome { NothingRequested, Reported, AlreadyHandled, PollFailed, ReportFailed }

/**
 * One poll-then-report cycle. If the back office has a fresh `requestId` for this device, collect a
 * snapshot and report it, then remember the id so the same request is answered exactly once per
 * process. Any transport failure is reported back as a non-fatal [PollOutcome]; the caller simply
 * retries on the next interval. A failed report is *not* recorded, so it is retried.
 *
 * Holds `lastHandledRequestId` in memory. The `DiagnosticsPoller` is the sole caller and invokes
 * this sequentially on a single dispatcher, so the field needs no synchronization. `@Singleton` so the
 * dedup state is a single process-wide instance — its correctness depends on exactly one existing.
 */
@Singleton
class PollAndReportDiagnosticsUseCase @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val diagnostics: DeviceDiagnostics,
) {
    private var lastHandledRequestId: String? = null

    suspend operator fun invoke(): PollOutcome = when (val poll = repository.poll()) {
        is AppResult.Success -> handle(poll.data)
        else -> PollOutcome.PollFailed
    }

    private suspend fun handle(requestId: String?): PollOutcome = when {
        requestId == null -> PollOutcome.NothingRequested
        requestId == lastHandledRequestId -> PollOutcome.AlreadyHandled
        else -> report(requestId)
    }

    private suspend fun report(requestId: String): PollOutcome =
        when (repository.report(requestId, diagnostics.snapshot())) {
            is AppResult.Success -> {
                lastHandledRequestId = requestId
                PollOutcome.Reported
            }
            else -> PollOutcome.ReportFailed
        }
}
