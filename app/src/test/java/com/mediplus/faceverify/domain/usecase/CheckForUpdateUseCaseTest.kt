package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.UpdateRepository
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import com.mediplus.faceverify.domain.model.UpdateInfo
import com.mediplus.faceverify.domain.model.UpdateStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gating rules for the self-update check (design: 2026-07-24-self-update-design.md):
 *  - a build below `minSupportedVersionCode` is forced ONLY when a newer installable build exists,
 *  - boundaries are inclusive-supported (`current == minSupported` is fine, `current == latest`
 *    is up to date),
 *  - degenerate server payloads fail OPEN as a check failure — they can never brick a device,
 *  - non-success results pass through unchanged.
 */
class CheckForUpdateUseCaseTest {

    private val repository = mockk<UpdateRepository>()
    private val useCase = CheckForUpdateUseCase(repository, CurrentAppVersion(code = 5, name = "1.4"))

    private fun info(
        latest: Int = 6,
        minSupported: Int = 1,
        sha256: String = VALID_SHA,
        sizeBytes: Long = 10_000_000,
        apkUrl: String = "https://backoffice.example.com/app/faceverify-6.apk",
    ) = UpdateInfo(
        latestVersionCode = latest,
        latestVersionName = "9.9",
        apkUrl = apkUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        minSupportedVersionCode = minSupported,
    )

    private fun serverSays(result: AppResult<UpdateInfo?>) {
        coEvery { repository.fetchVersionInfo() } returns result
    }

    @Test
    fun `nothing published means up to date`() = runTest {
        serverSays(AppResult.Success(null))

        assertEquals(AppResult.Success(UpdateStatus.UpToDate), useCase())
    }

    @Test
    fun `running the latest build is up to date`() = runTest {
        serverSays(AppResult.Success(info(latest = 5)))

        assertEquals(AppResult.Success(UpdateStatus.UpToDate), useCase())
    }

    @Test
    fun `a newer build with the floor at or below current is optional`() = runTest {
        val published = info(latest = 6, minSupported = 5)
        serverSays(AppResult.Success(published))

        assertEquals(AppResult.Success(UpdateStatus.Optional(published)), useCase())
    }

    @Test
    fun `sitting exactly on the supported floor is still supported`() = runTest {
        // current == minSupported must never force: the floor is inclusive.
        val published = info(latest = 7, minSupported = 5)
        serverSays(AppResult.Success(published))

        assertEquals(AppResult.Success(UpdateStatus.Optional(published)), useCase())
    }

    @Test
    fun `a build below the supported floor is forced when a newer build exists`() = runTest {
        val published = info(latest = 7, minSupported = 6)
        serverSays(AppResult.Success(published))

        assertEquals(AppResult.Success(UpdateStatus.Forced(published)), useCase())
    }

    @Test
    fun `a floor above the latest build can never force without an installable update`() = runTest {
        // Server bug: minSupported > latest while we already run latest. Forcing here would brick
        // the device with nothing to install — must clamp to up to date.
        serverSays(AppResult.Success(info(latest = 5, minSupported = 9)))

        assertEquals(AppResult.Success(UpdateStatus.UpToDate), useCase())
    }

    @Test
    fun `a blank sha fails open as a check failure`() = runTest {
        serverSays(AppResult.Success(info(sha256 = "")))

        assertTrue(useCase() is AppResult.TransientFailure)
    }

    @Test
    fun `a malformed sha fails open as a check failure`() = runTest {
        serverSays(AppResult.Success(info(sha256 = "not-hex-at-all")))

        assertTrue(useCase() is AppResult.TransientFailure)
    }

    @Test
    fun `a non-positive size fails open as a check failure`() = runTest {
        serverSays(AppResult.Success(info(sizeBytes = 0)))

        assertTrue(useCase() is AppResult.TransientFailure)
    }

    @Test
    fun `a non-https url fails open as a check failure`() = runTest {
        serverSays(AppResult.Success(info(apkUrl = "http://backoffice.example.com/app.apk")))

        assertTrue(useCase() is AppResult.TransientFailure)
    }

    @Test
    fun `an uppercase sha is accepted`() = runTest {
        val published = info(sha256 = VALID_SHA.uppercase())
        serverSays(AppResult.Success(published))

        assertEquals(AppResult.Success(UpdateStatus.Optional(published)), useCase())
    }

    @Test
    fun `transient failures pass through unchanged`() = runTest {
        val failure = AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY))
        serverSays(failure)

        assertEquals(failure, useCase())
    }

    @Test
    fun `timeouts pass through unchanged`() = runTest {
        serverSays(AppResult.Timeout)

        assertEquals(AppResult.Timeout, useCase())
    }

    private companion object {
        const val VALID_SHA = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6"
    }
}
