package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.di.UpdateCacheDir
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.UpdateRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.UpdateScenario
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import com.mediplus.faceverify.domain.model.DownloadedApk
import com.mediplus.faceverify.domain.model.UpdateInfo
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

/**
 * Fake self-update backend: publishes a build one versionCode above the running one (forced when
 * the scenario says so), and simulates the download in paced chunks so the progress UI is visible
 * on a bare emulator. The published payload deliberately passes the real gating in
 * CheckForUpdateUseCase (valid sha shape, https url, positive size).
 */
class FakeUpdateRepository @Inject constructor(
    private val store: DevSettingsStore,
    private val currentVersion: CurrentAppVersion,
    @param:UpdateCacheDir private val cacheDir: File,
) : UpdateRepository {

    override suspend fun fetchVersionInfo(): AppResult<UpdateInfo?> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.update) {
            UpdateScenario.UP_TO_DATE -> AppResult.Success(null)
            UpdateScenario.CHECK_FAILS ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            UpdateScenario.FORCED_UPDATE ->
                AppResult.Success(published(minSupported = currentVersion.code + 1))
            UpdateScenario.OPTIONAL_UPDATE,
            UpdateScenario.DOWNLOAD_FAILS,
            UpdateScenario.HASH_MISMATCH,
            UpdateScenario.INSTALL_FAILS,
            -> AppResult.Success(published(minSupported = 1))
        }
    }

    override suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk> {
        val settings = store.current()
        for (step in 1..STEPS) {
            delay(settings.latencyMillis / STEPS)
            if (settings.update == UpdateScenario.DOWNLOAD_FAILS && step > STEPS / 2) {
                return AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY))
            }
            onProgress(info.sizeBytes * step / STEPS, info.sizeBytes)
        }
        return if (settings.update == UpdateScenario.HASH_MISMATCH) {
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        } else {
            AppResult.Success(DownloadedApk(writeDummyApk(info), info.latestVersionCode))
        }
    }

    override suspend fun clearDownloads() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun published(minSupported: Int) = UpdateInfo(
        latestVersionCode = currentVersion.code + 1,
        latestVersionName = "${currentVersion.name}-next",
        apkUrl = "https://fake.backoffice.dev/app/faceverify-next.apk",
        sha256 = FAKE_SHA,
        sizeBytes = FAKE_SIZE_BYTES,
        minSupportedVersionCode = minSupported,
    )

    private fun writeDummyApk(info: UpdateInfo): File {
        cacheDir.mkdirs()
        return File(cacheDir, "update-v${info.latestVersionCode}.apk").apply {
            writeBytes(ByteArray(DUMMY_APK_BYTES))
        }
    }

    private companion object {
        const val STEPS = 10
        const val FAKE_SIZE_BYTES = 4_200_000L
        const val DUMMY_APK_BYTES = 1024
        // Any 64 hex chars: the fake never actually digests, but the shape must pass gating.
        const val FAKE_SHA = "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0"
    }
}
