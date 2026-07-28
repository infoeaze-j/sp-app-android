package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.remote.DiagnosticsApi
import com.mediplus.spapp.data.remote.ReportDiagnosticsRequest
import com.mediplus.spapp.data.remote.toDto
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Poll-then-report telemetry access. Callers see only [AppResult] and domain types.
 * `poll()` returns `Success(requestId)` when the back office wants a snapshot, `Success(null)` when
 * it does not — `{"request": null}`, a 204, or a 404 from a backend that has not deployed the
 * endpoint yet (fail open).
 */
interface DiagnosticsRepository {
    suspend fun poll(): AppResult<String?>
    suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit>
}

class DiagnosticsRepositoryImpl @Inject constructor(
    private val api: DiagnosticsApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : DiagnosticsRepository {

    override suspend fun poll(): AppResult<String?> =
        apiCall(dispatcher, { api.pending() }) { response ->
            val body = response.body()
            when {
                // `{"request": null}` is the normal answer, and reads as "nothing requested".
                response.isSuccessful && body != null ->
                    AppResult.Success(body.request?.id?.takeIf { it.isNotBlank() })
                // 204 (no content) and 404 (not deployed) both mean "nothing requested" too.
                response.code() == HttpURLConnection.HTTP_NO_CONTENT ||
                    response.code() == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(null)
                // A 200 with a malformed body throws in api.pending() and is caught by apiCall as a
                // transient failure — deliberately NOT treated as "nothing requested" (fail-safe).
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        apiCall(dispatcher, { api.report(requestId, ReportDiagnosticsRequest(snapshot.toDto())) }) { response ->
            if (response.isSuccessful) {
                AppResult.Success(Unit)
            } else {
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }
}
