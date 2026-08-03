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
 * The back office computes `updateRequired` / `updateAvailable` itself, so the "must update" rule
 * lives in one place. `minSupportedVersionCode` has since been dropped from the published payload,
 * which makes the floor comparison below inert against the live contract (an absent field parses to
 * 0, and no build is below 0) — it is kept as the fallback that keeps a server which omits the
 * verdicts working, not as a second opinion on one that supplies them.
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
     * This is not a TLS preference. The download is unauthenticated now, so no session token rides
     * along, but the rule still earns its place: the URL is named by the very response we are
     * deciding whether to trust, and honouring an arbitrary host would let that response point the
     * device at anyone's binary. Same-origin means the client never has to make that judgement.
     */
    private fun isSameOriginAsApi(url: String): Boolean {
        val apiOrigin = originOf(baseUrl) ?: return false
        return originOf(url) == apiOrigin
    }

    /**
     * Scheme + host + port, with an omitted port resolved to the scheme's default so that
     * `https://host/` and `https://host:443/` compare equal — they name the same origin. This is
     * load-bearing rather than tidy: `BASE_URL` names no port, while the back office is free to emit
     * either form in `apkUrl`. Comparing the raw ports would refuse every update, and the refusal
     * surfaces as [TransientKind.UNKNOWN] — an opaque "try again" the operator can never resolve.
     */
    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        uri.host?.let { host -> "${uri.scheme}://$host:${effectivePort(uri)}" }
    }.getOrNull()

    private fun effectivePort(uri: URI): Int =
        if (uri.port != NO_PORT) uri.port else uri.scheme?.let(DEFAULT_PORTS::get) ?: NO_PORT

    private companion object {
        val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        const val NO_PORT = -1
        val DEFAULT_PORTS = mapOf("https" to 443, "http" to 80)
    }
}
