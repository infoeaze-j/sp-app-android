package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The self-update orchestration, now shared by the UI and the background worker
 * (design: 2026-08-03-unattended-self-update-design.md). Inherits the rules the ViewModel used to
 * own — housekeeping runs once, the check fails open, forced updates cannot be dismissed, retries
 * re-enter at the right stage — and adds the two the coordinator introduces: only one attempt runs
 * at a time, and a headless attempt accepts on the operator's behalf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = mockk<UpdateRepository>()
    private val installer = mockk<ApkInstaller>()
    private val backupStore = mockk<ApkBackupStore>()
    private val errorMapper = DefaultErrorMapper()
    private val currentVersion = CurrentAppVersion(code = 5, name = "1.4")

    private lateinit var apkFile: File

    @Before
    fun setUp() {
        apkFile = tempFolder.newFile("update-v7.apk")
        coJustRun { repository.pruneObsoleteDownloads() }
        coJustRun { installer.abandonStaleSessions() }
        coJustRun { backupStore.pruneStaleBackups(any()) }
        coEvery { installer.canRequestInstalls() } returns true
        every { backupStore.needsLegacyWritePermission() } returns false
    }

    private fun coordinator() = UpdateCoordinator(
        checkForUpdate = CheckForUpdateUseCase(repository, currentVersion, BASE_URL),
        installer = installer,
        backupStore = backupStore,
        errorMapper = errorMapper,
        pipeline = UpdatePipeline(repository, installer, backupStore, errorMapper, currentVersion),
        housekeeping = UpdateHousekeeping(backupStore, installer, repository, currentVersion),
    )

    private fun info(latest: Int = 7, minSupported: Int = 1) = UpdateInfo(
        latestVersionCode = latest,
        latestVersionName = "1.6",
        apkUrl = "${BASE_URL}app/releases/$latest/binary",
        sha256 = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
        sizeBytes = 100,
        minSupportedVersionCode = minSupported,
    )

    private fun serverSays(vararg results: AppResult<UpdateInfo?>) {
        coEvery { repository.fetchVersionInfo() } returnsMany results.toList()
    }

    private fun downloadSucceeds() {
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            onProgress(100, 100)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
    }

    private fun backupSucceeds() {
        coEvery { backupStore.backupCurrentApk(any()) } returns AppResult.Success(Unit)
    }

    private fun businessMessage(code: BusinessCode) =
        errorMapper.toUserMessage(AppError.Business(code))

    @Test
    fun `up to date stays idle and runs launch housekeeping once`() = runTest {
        serverSays(AppResult.Success(null), AppResult.Success(null))
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Foreground)
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
        coVerify(exactly = 1) { backupStore.pruneStaleBackups(5) }
        coVerify(exactly = 1) { installer.abandonStaleSessions() }
        coVerify(exactly = 1) { repository.pruneObsoleteDownloads() }
    }

    @Test
    fun `a failed check surfaces a dismissible notice and is retryable work`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(UpdateAttempt.RETRYABLE, attempt)
        val phase = coordinator.phase.value as UpdatePhase.CheckFailed
        assertEquals(
            errorMapper.toUserMessage(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            phase.message,
        )
    }

    @Test
    fun `a timed out check is retryable work`() = runTest {
        serverSays(AppResult.Timeout)
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.RETRYABLE, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `nothing to install is completed work, not retryable`() = runTest {
        serverSays(AppResult.Success(null))
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.COMPLETED, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `dismissing the check notice returns to idle`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.dismiss()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `retrying a failed check re-checks`() = runTest {
        serverSays(
            AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            AppResult.Success(null),
        )
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.retry()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
        coVerify(exactly = 2) { repository.fetchVersionInfo() }
    }

    @Test
    fun `a foreground attempt stops at the offer and waits for the operator`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpdateAvailable(info(), forced = false), coordinator.phase.value)
        coVerify(exactly = 0) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `a headless attempt accepts on the operator's behalf`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        // Wrapped in launch: runUpdate is a suspend entry point, and a direct (unlaunched) call
        // would let runTest auto-advance straight through settleAfterCommit's delay, skipping past
        // the Restarting phase before we ever get to observe it.
        val job = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerifyOrder {
            repository.downloadAndVerify(info(), any())
            backupStore.backupCurrentApk(currentVersion)
            installer.install(apkFile)
        }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `a second attempt is skipped while one is already running`() = runTest {
        val coordinator = coordinator()
        coEvery { repository.fetchVersionInfo() } coAnswers {
            kotlinx.coroutines.delay(1_000)
            AppResult.Success(null)
        }

        val first = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()
        val second = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()
        first.join()

        // The overlapping call returns immediately rather than queueing a duplicate attempt.
        assertEquals(UpdateAttempt.COMPLETED, second)
        coVerify(exactly = 1) { repository.fetchVersionInfo() }
    }

    @Test
    fun `a forced update cannot be dismissed`() = runTest {
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.dismiss()

        assertEquals(
            UpdatePhase.UpdateAvailable(info(latest = 7, minSupported = 7), forced = true),
            coordinator.phase.value,
        )
    }

    @Test
    fun `accepting without install permission routes through settings and resumes`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()
        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)

        coEvery { installer.canRequestInstalls() } returns true
        // Wrapped in launch so the Restarting phase is observable before runTest auto-advances
        // through settleAfterCommit's delay (see the headless-accept test above).
        val job = launch { coordinator.returnedFromSettings() }
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `returning from settings without the grant stays put`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()

        coordinator.returnedFromSettings()
        advanceUntilIdle()

        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)
    }

    @Test
    fun `download progress reaches the phase as it streams`() = runTest {
        serverSays(AppResult.Success(info()))
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val seen = mutableListOf<UpdatePhase>()
        lateinit var coordinator: UpdateCoordinator
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            seen.add(coordinator.phase.value)
            onProgress(100, 100)
            seen.add(coordinator.phase.value)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        assertEquals(
            listOf<UpdatePhase>(
                UpdatePhase.Downloading(50, 100, forced = false),
                UpdatePhase.Downloading(100, 100, forced = false),
            ),
            seen,
        )
    }

    @Test
    fun `a committed install shows restarting then settles back to idle`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        val job = launch { coordinator.accept() }
        runCurrent()
        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)

        advanceUntilIdle()
        job.join()
        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a corrupted download fails with a fresh-download retry and is completed work`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(UpdateAttempt.COMPLETED, attempt)
        assertEquals(
            UpdatePhase.Failed(
                message = businessMessage(BusinessCode.UPDATE_CORRUPTED),
                info = info(),
                forced = false,
                retry = RetryTarget.DOWNLOAD,
            ),
            coordinator.phase.value,
        )

        coordinator.retry()
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `an interrupted download is retryable work`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.DOWNLOAD_INTERRUPTED))
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.RETRYABLE, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `a backup failure blocks the install`() = runTest {
        // Task 4 removes this gate; it is asserted here so the extraction is provably behaviour-free.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_BACKUP_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
        coVerify(exactly = 0) { installer.install(any()) }
    }

    @Test
    fun `an aborted install keeps the download and retries without re-downloading`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.Aborted, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_ABORTED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)

        // Wrapped in launch so the Restarting phase is observable before runTest auto-advances
        // through settleAfterCommit's delay (see the headless-accept test above).
        val job = launch { coordinator.retry() }
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerify(exactly = 1) { repository.downloadAndVerify(any(), any()) }
        coVerify(exactly = 2) { installer.install(apkFile) }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `retrying an install after cache eviction re-downloads`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.Aborted, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()

        assertTrue(apkFile.delete())
        coordinator.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `any other install failure is retryable at the install stage`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
    }

    private companion object {
        const val BASE_URL = "https://backoffice.example.com/api/v1/"
    }
}
