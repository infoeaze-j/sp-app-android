package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.di.UpdateCacheDir
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.remote.LatestReleaseResponse
import com.mediplus.spapp.data.remote.UpdateApi
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * Access to the self-update backend: the version check and the APK download. Like every repository,
 * callers see only [AppResult] and domain models.
 */
interface UpdateRepository {

    /**
     * Fetches the newest published build, telling the back office which build is asking so it can
     * compute the "must update" verdict itself. `Success(null)` means there is nothing to offer —
     * `{"latest": null}`, or a 404 from a backend that has not deployed the endpoint yet — and is
     * treated as up to date (fail open).
     */
    suspend fun fetchVersionInfo(): AppResult<UpdateInfo?>

    /**
     * Streams the APK behind [info] to app-private cache, digesting as it goes. Success is only
     * returned for a file whose SHA-256 and byte count both match [info]; any mismatch deletes the
     * file and reports [com.mediplus.spapp.core.result.BusinessCode.UPDATE_CORRUPTED].
     * [onProgress] receives (bytesSoFar, totalBytes), throttled to whole-percent changes.
     *
     * A download interrupted part-way through **keeps** what it wrote, and a later call resumes from
     * there with a range request. Resuming is opportunistic: a server that answers 200 instead of
     * 206 has ignored the range, and the transfer silently restarts from zero. Nothing about this is
     * trusted — the digest over the whole finished file is what decides, so a prefix that turns out
     * to belong to different bytes can only ever fail verification, never install.
     */
    suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk>

    /**
     * Deletes downloads that can never be installed — anything at or below the running build, plus
     * any file whose name does not parse. Called once at launch, and deliberately narrower than
     * "delete everything": a partial download for a build still on offer is the thing resume exists
     * to reuse, so it has to survive a process restart. Discarding partials for *other* pending
     * builds is left to [downloadAndVerify], which is the only place that knows which build is
     * actually being fetched.
     */
    suspend fun pruneObsoleteDownloads()
}

class UpdateRepositoryImpl @Inject constructor(
    private val api: UpdateApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    @param:UpdateCacheDir private val cacheDir: File,
    private val currentVersion: CurrentAppVersion,
) : UpdateRepository {

    override suspend fun pruneObsoleteDownloads(): Unit = withContext(dispatcher) {
        cacheDir.listFiles()?.forEach { file ->
            val code = versionCodeOf(file.name)
            if (code == null || code <= currentVersion.code) file.delete()
        }
    }

    // The streaming body escapes apiCall's map lambda, so this hand-rolls the same transport
    // classification — except that a dropped download is not ambiguous the way a dropped request is.
    // Nothing was installed and the bytes on disk may still be useful, so it is a plain transient
    // failure rather than AppResult.Timeout's "outcome unknown".
    override suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk> = withContext(dispatcher) {
        val target = File(cacheDir, "update-v${info.latestVersionCode}.apk")
        // Partials for a build that is no longer the one on offer. This is the only place that
        // knows which build is actually being fetched, which is why the launch-time prune can't.
        cacheDir.listFiles()?.forEach { if (it != target) it.delete() }
        try {
            // A server that refuses our offset can still serve the whole file, so spend a second
            // request here rather than making the operator tap Retry for something we can fix.
            attempt(info, target, resumableBytes(target, info.sizeBytes), onProgress)
                ?: attempt(info, target, 0L, onProgress)
                ?: AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        } catch (e: SocketTimeoutException) {
            interrupted(e)
        } catch (e: IOException) {
            interrupted(e)
        }
    }

    /**
     * One request. Returns null to mean "the server would not serve that offset" — only possible
     * when [resumeFrom] is positive, so the caller's retry from zero cannot recurse.
     */
    private suspend fun attempt(
        info: UpdateInfo,
        target: File,
        resumeFrom: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): AppResult<DownloadedApk>? {
        val response = api.downloadApk(info.apkUrl, if (resumeFrom > 0) "bytes=$resumeFrom-" else null)
        if (resumeFrom > 0 && response.code() == HTTP_RANGE_NOT_SATISFIABLE) return null
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            return AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
        // 200 in answer to a range request means the server ignored it and is sending the whole
        // file; anything we already had is not part of this body, so start over at zero.
        val startAt = if (servesRangeFrom(response, resumeFrom)) resumeFrom else 0L
        val plan = TransferPlan(totalBytes = info.sizeBytes, startAt = startAt, onProgress = onProgress)
        return verified(info, target, body.use { streamTo(target, it, plan) })
    }

    override suspend fun fetchVersionInfo(): AppResult<UpdateInfo?> =
        apiCall(dispatcher, { api.latestRelease(currentVersion.code) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.toDomain())
                // The spec always answers 200, so a 404 means the endpoint is not deployed yet.
                // Either way, nothing published is a fact, not a failure (fail open).
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

/** `{"latest": null}` — nothing published for this fleet — is the normal empty answer, not an error. */
private fun LatestReleaseResponse.toDomain(): UpdateInfo? = latest?.let {
    UpdateInfo(
        latestVersionCode = it.versionCode,
        latestVersionName = it.versionName,
        apkUrl = it.url,
        sha256 = it.sha256,
        sizeBytes = it.sizeBytes,
        minSupportedVersionCode = it.minSupportedVersionCode,
        updateRequired = updateRequired,
        updateAvailable = updateAvailable,
        releaseNotes = it.releaseNotes,
    )
}
