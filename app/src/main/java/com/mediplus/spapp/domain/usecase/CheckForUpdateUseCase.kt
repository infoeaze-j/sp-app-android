package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import javax.inject.Inject

/**
 * Turns the published version facts into a gated [UpdateStatus] for the running build.
 *
 * Fail-open is the rule everywhere: nothing published, an undeployed endpoint, or a degenerate
 * payload (blank/malformed sha, non-positive size, non-https url) must never leave a device stuck.
 * In particular a build is [UpdateStatus.Forced] only when a newer build actually exists to
 * install — a supported-floor above the latest published build is a server bug, not a reason to
 * block every clinic.
 */
class CheckForUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val currentVersion: CurrentAppVersion,
) {
    suspend operator fun invoke(): AppResult<UpdateStatus> =
        when (val result = updateRepository.fetchVersionInfo()) {
            is AppResult.Success -> gate(result.data)
            is AppResult.BusinessRejection -> result
            is AppResult.TransientFailure -> result
            AppResult.Timeout -> AppResult.Timeout
        }

    private fun gate(info: UpdateInfo?): AppResult<UpdateStatus> = when {
        info == null || info.latestVersionCode <= currentVersion.code ->
            AppResult.Success(UpdateStatus.UpToDate)
        !info.isInstallable() ->
            AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        currentVersion.code < info.minSupportedVersionCode ->
            AppResult.Success(UpdateStatus.Forced(info))
        else ->
            AppResult.Success(UpdateStatus.Optional(info))
    }

    private fun UpdateInfo.isInstallable(): Boolean =
        sha256.matches(SHA256_HEX) && sizeBytes > 0 && apkUrl.startsWith("https://")

    private companion object {
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
