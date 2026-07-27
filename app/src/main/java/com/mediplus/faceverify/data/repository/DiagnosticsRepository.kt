package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.DiagnosticsApi
import com.mediplus.faceverify.data.remote.DiagnosticsReport
import com.mediplus.faceverify.data.remote.toDto
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Poll-then-report telemetry access. Callers see only [AppResult] and domain types.
 * `poll()` returns `Success(requestId)` when the back office wants a snapshot, `Success(null)` when
 * it does not — including a 404 from a backend that has not deployed the endpoint yet (fail open).
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
        apiCall(dispatcher, { api.poll() }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.requestId)
                // 204 (no content) and 404 (not deployed) both mean "nothing requested".
                response.code() == HttpURLConnection.HTTP_NO_CONTENT ||
                    response.code() == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(null)
                // A 200 with an empty/malformed body throws in api.poll() and is caught by apiCall as a
                // transient failure — deliberately NOT treated as "nothing requested" (fail-safe).
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        apiCall(dispatcher, { api.report(DiagnosticsReport(requestId, snapshot.toDto())) }) { response ->
            if (response.isSuccessful) {
                AppResult.Success(Unit)
            } else {
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }
}
