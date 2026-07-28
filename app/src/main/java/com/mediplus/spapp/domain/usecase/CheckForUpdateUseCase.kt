package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.di.BaseUrl
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import java.net.URI
import javax.inject.Inject

/**
 * Turns the published version facts into a gated [UpdateStatus] for the running build.
 *
 * The back office now computes `updateRequired` / `updateAvailable` itself, so the "must update"
 * rule lives in one place; the client's own version comparison stays as the floor beneath it, which
 * is what keeps a server that omits the verdicts working.
 *
 * Fail-open is the rule everywhere: nothing published, an undeployed endpoint, or a degenerate
 * payload (blank/malformed sha, non-positive size, off-origin url) must never leave a device stuck.
 * In particular a build is [UpdateStatus.Forced] only when a newer build actually exists to
 * install — a supported-floor above the latest published build is a server bug, not a reason to
 * block every clinic.
 */
class CheckForUpdateUseCase @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val currentVersion: CurrentAppVersion,
    @param:BaseUrl private val baseUrl: String,
) {
    suspend operator fun invoke(): AppResult<UpdateStatus> =
        when (val result = updateRepository.fetchVersionInfo()) {
            is AppResult.Success -> gate(result.data)
            is AppResult.BusinessRejection -> result
            is AppResult.TransientFailure -> result
            AppResult.Timeout -> AppResult.Timeout
        }

    private fun gate(info: UpdateInfo?): AppResult<UpdateStatus> = when {
        info == null || !info.updateAvailable || info.latestVersionCode <= currentVersion.code ->
            AppResult.Success(UpdateStatus.UpToDate)
        !info.isInstallable() ->
            AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        info.updateRequired || currentVersion.code < info.minSupportedVersionCode ->
            AppResult.Success(UpdateStatus.Forced(info))
        else ->
            AppResult.Success(UpdateStatus.Optional(info))
    }

    private fun UpdateInfo.isInstallable(): Boolean =
        sha256.matches(SHA256_HEX) && sizeBytes > 0 && isSameOriginAsApi(apkUrl)

    /**
     * The APK must come from the API's own origin, which the spec states it does by construction.
     * This is not a TLS preference: the client attaches its bearer token to the download, so any
     * other host — https or not — would be handed the operator's session token.
     */
    private fun isSameOriginAsApi(url: String): Boolean {
        val apiOrigin = originOf(baseUrl) ?: return false
        return originOf(url) == apiOrigin
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        uri.host?.let { host -> "${uri.scheme}://$host:${uri.port}" }
    }.getOrNull()

    private companion object {
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
