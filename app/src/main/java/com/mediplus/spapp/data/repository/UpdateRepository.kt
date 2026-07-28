package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.di.UpdateCacheDir
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.remote.LatestReleaseResponse
import com.mediplus.spapp.data.remote.UpdateApi
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Access to the self-update backend: the version check and (later) the APK download. Like every
 * repository, callers see only [AppResult] and domain models.
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
     */
    suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk>

    /**
     * Deletes leftover downloads from earlier runs. Called once at launch: nothing is resumable
     * across a process restart (a fresh attempt re-downloads), so leftovers are only waste.
     */
    suspend fun clearDownloads()
}

class UpdateRepositoryImpl @Inject constructor(
    private val api: UpdateApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    @param:UpdateCacheDir private val cacheDir: File,
    private val currentVersion: CurrentAppVersion,
) : UpdateRepository {

    override suspend fun clearDownloads(): Unit = withContext(dispatcher) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // The streaming body escapes apiCall's map lambda, so this hand-rolls the same transport
    // classification (SocketTimeoutException -> Timeout, IOException -> NO_CONNECTIVITY).
    override suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk> = withContext(dispatcher) {
        val target = File(cacheDir, "update-v${info.latestVersionCode}.apk")
        try {
            val response = api.downloadApk(info.apkUrl)
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            } else {
                verified(info, target, body.use { streamTo(target, it, info.sizeBytes, onProgress) })
            }
        } catch (_: SocketTimeoutException) {
            target.delete()
            AppResult.Timeout
        } catch (e: IOException) {
            target.delete()
            AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY, e))
        }
    }

    private suspend fun streamTo(
        target: File,
        body: ResponseBody,
        totalBytes: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): StreamedApk {
        val digest = MessageDigest.getInstance("SHA-256")
        cacheDir.mkdirs()
        val written = body.byteStream().use { input ->
            target.outputStream().use { output ->
                copyChunks(input, output, digest, totalBytes, onProgress)
            }
        }
        onProgress(written, totalBytes)
        return StreamedApk(bytes = written, shaHex = digest.digest().toHex())
    }

    private suspend fun copyChunks(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        totalBytes: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Long {
        val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
        var written = 0L
        var lastPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return written
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            written += read
            val percent = percentOf(written, totalBytes)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(written, totalBytes)
            }
        }
    }

    private fun verified(info: UpdateInfo, target: File, streamed: StreamedApk): AppResult<DownloadedApk> =
        if (streamed.bytes == info.sizeBytes && streamed.shaHex.equals(info.sha256, ignoreCase = true)) {
            AppResult.Success(DownloadedApk(target, info.latestVersionCode))
        } else {
            target.delete()
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
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
        const val DOWNLOAD_CHUNK_BYTES = 64 * 1024
        const val PERCENT = 100

        fun percentOf(written: Long, total: Long): Int =
            if (total > 0) ((written * PERCENT) / total).toInt() else 0

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/** The raw outcome of streaming a body to disk, before verification. */
private data class StreamedApk(val bytes: Long, val shaHex: String)

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
