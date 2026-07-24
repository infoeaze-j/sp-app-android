package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.domain.model.UpdateInfo

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
