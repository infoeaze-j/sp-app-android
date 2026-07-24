package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.AppVersionResponse
import com.mediplus.faceverify.data.remote.UpdateApi
import com.mediplus.faceverify.domain.model.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Access to the self-update backend: the version check and (later) the APK download. Like every
 * repository, callers see only [AppResult] and domain models.
 */
interface UpdateRepository {

    /**
     * Fetches the newest published build. `Success(null)` means the back office has nothing to
     * offer — including a 404 from a backend that has not deployed the endpoint yet — and is
     * treated as up to date (fail open).
     */
    suspend fun fetchVersionInfo(): AppResult<UpdateInfo?>
}

class UpdateRepositoryImpl @Inject constructor(
    private val api: UpdateApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : UpdateRepository {

    override suspend fun fetchVersionInfo(): AppResult<UpdateInfo?> =
        apiCall(dispatcher, { api.checkVersion() }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.toDomain())
                // Not deployed yet — nothing published is a fact, not a failure (fail open).
                response.code() == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(null)
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                // The check gates nothing critical; anything odd stays retryable.
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            }
        }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun AppVersionResponse.toDomain() = UpdateInfo(
    latestVersionCode = latestVersionCode,
    latestVersionName = latestVersionName,
    apkUrl = apkUrl,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    minSupportedVersionCode = minSupportedVersionCode,
)
