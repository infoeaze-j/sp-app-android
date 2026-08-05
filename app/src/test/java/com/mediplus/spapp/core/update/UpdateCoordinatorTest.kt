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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

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
        // Real bytes, whose digest info() publishes. The install-retry path re-verifies the cached
        // file against that digest before handing it to the installer, so a fixture holding an empty
        // file would silently turn every "reuses the download" test into "re-downloads".
        apkFile = tempFolder.newFile("update-v7.apk").apply { writeBytes(APK_BYTES) }
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
        pipeline = UpdatePipeline(
            repository,
            installer,
            backupStore,
            errorMapper,
            currentVersion,
            mainDispatcherRule.dispatcher,
        ),
        housekeeping = UpdateHousekeeping(backupStore, installer, repository, currentVersion),
    )

    private fun info(latest: Int = 7, minSupported: Int = 1) = UpdateInfo(
        latestVersionCode = latest,
        latestVersionName = "1.6",
        apkUrl = "${BASE_URL}app/releases/$latest/binary",
        sha256 = APK_SHA256,
        sizeBytes = APK_BYTES.size.toLong(),
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

    // The trigger is spelled out at every call site rather than defaulted: which caller produced a
    // progress phase is exactly what decides whether the operator is shown it.
    private fun downloading(soFar: Long, total: Long, trigger: UpdateTrigger) =
        UpdatePhase.Downloading(soFar, total, forced = false, trigger = trigger)

    private fun restarting(trigger: UpdateTrigger) =
        UpdatePhase.Restarting(forced = false, trigger = trigger)

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
    fun `a business rejection on the check is completed work, not retryable`() = runTest {
        serverSays(AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED)))
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.COMPLETED, coordinator.runUpdate(Presence.Headless))
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
    fun `dismissing while downloading does nothing`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            delay(1_000)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        val job = launch { coordinator.accept() }
        runCurrent()
        assertEquals(downloading(50, 100, UpdateTrigger.Operator), coordinator.phase.value)

        coordinator.dismiss()

        // The else -> current branch: a phase with no dismiss rule of its own is left untouched.
        assertEquals(downloading(50, 100, UpdateTrigger.Operator), coordinator.phase.value)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `retrying from a phase with nothing to retry is a no-op`() = runTest {
        val coordinator = coordinator()

        coordinator.retry()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
        coVerify(exactly = 0) { repository.fetchVersionInfo() }
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

        assertEquals(restarting(UpdateTrigger.Background), coordinator.phase.value)
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
            delay(1_000)
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
    fun `a retry tap while a headless attempt is checking queues rather than races it`() = runTest {
        // Reach Failed(retry = DOWNLOAD) first, exactly the phase an operator would see and tap
        // Retry on.
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()

        // Now the worker's periodic runUpdate parks inside checkForUpdate, still holding the phase
        // at Failed, while the operator's tap arrives.
        coEvery { repository.fetchVersionInfo() } coAnswers {
            delay(500)
            AppResult.Success(info())
        }
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed

        val workerJob = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()
        val retryJob = launch { coordinator.retry() }
        runCurrent()
        advanceUntilIdle()
        workerJob.join()
        retryJob.join()

        // Exactly 2 downloads total: the one above that reached Failed, and the worker's own once
        // its check resolves. Without the lock, retry() races the worker the moment it is called
        // (phase is still Failed then) and adds a third download of its own before the worker's
        // check has even returned.
        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
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

        assertEquals(restarting(UpdateTrigger.Operator), coordinator.phase.value)

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
                downloading(50, 100, UpdateTrigger.Operator),
                downloading(100, 100, UpdateTrigger.Operator),
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
        assertEquals(restarting(UpdateTrigger.Operator), coordinator.phase.value)

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
    fun `a failed backup does not block the install`() = runTest {
        // Headless, "no backup, no install, ever" strands a device on a stale build permanently,
        // with nobody present to notice. Rollback is a manual procedure; a stranded device is not
        // recoverable at all (design 2026-08-03 §6).
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        // Wrapped in launch: runUpdate is a suspend entry point, and a direct (unlaunched) call
        // would let runTest auto-advance straight through settleAfterCommit's delay, skipping past
        // the Restarting phase before we ever get to observe it (see the headless-accept test above).
        val job = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()

        assertEquals(restarting(UpdateTrigger.Background), coordinator.phase.value)
        coVerify(exactly = 1) { installer.install(apkFile) }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `a failed backup still walks through the backing up phase`() = runTest {
        // The attempt is still made and still visible; only its power of veto is gone.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)

        coVerifyOrder {
            backupStore.backupCurrentApk(currentVersion)
            installer.install(apkFile)
        }
    }

    @Test
    fun `an install failure after a failed backup still reports the install failure`() = runTest {
        // The backup's own error must not shadow the real one — UPDATE_BACKUP_FAILED no longer
        // reaches the operator at all.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_FAILED), phase.message)
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

        assertEquals(restarting(UpdateTrigger.Operator), coordinator.phase.value)
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

    @Test
    fun `an install awaiting confirmation parks in ConfirmationPending as completed work`() = runTest {
        // The V2s half of the fleet reaches this on every single update: API 30 can never install
        // silently, so the notification path is the primary path there, not a fallback.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.AwaitingConfirmation
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        // Not RETRYABLE: nothing is wrong, and re-running would re-download and re-notify.
        assertEquals(UpdateAttempt.COMPLETED, attempt)
        assertEquals(
            UpdatePhase.ConfirmationPending(info(), forced = false),
            coordinator.phase.value,
        )
    }

    @Test
    fun `retrying from ConfirmationPending re-installs without re-downloading`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.AwaitingConfirmation, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        // Wrapped in launch: retry() is a suspend entry point, and a direct (unlaunched) call would
        // let runTest auto-advance straight through settleAfterCommit's delay, skipping past
        // Restarting before we ever get to observe it (see the headless-accept test above).
        val job = launch { coordinator.retry() }
        runCurrent()

        assertEquals(restarting(UpdateTrigger.Operator), coordinator.phase.value)
        coVerify(exactly = 1) { repository.downloadAndVerify(any(), any()) }
        coVerify(exactly = 2) { installer.install(apkFile) }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `an optional update offers a dismissible prompt`() = runTest {
        // The commonest operator gesture in the whole flow. It lost its test in the coordinator
        // refactor — UpdateViewModelTest used to own it — and nothing replaced the assertion.
        serverSays(AppResult.Success(info()))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        assertEquals(UpdatePhase.UpdateAvailable(info(), forced = false), coordinator.phase.value)

        coordinator.dismiss()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a forced permission stop cannot be dismissed`() = runTest {
        // One of three `if (forced) current else Idle` guards in dismiss(). Deleting this one used
        // to pass the entire suite — and on this fleet a forced update is how a fix nobody can
        // decline gets pushed.
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()
        val stopped = UpdatePhase.PermissionNeeded(info(latest = 7, minSupported = 7), forced = true)
        assertEquals(stopped, coordinator.phase.value)

        coordinator.dismiss()

        assertEquals(stopped, coordinator.phase.value)
    }

    @Test
    fun `an optional permission stop is dismissible`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()
        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)

        coordinator.dismiss()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a forced failure cannot be dismissed`() = runTest {
        // The third guard, and the other one deleting used to pass the suite.
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()
        val failed = coordinator.phase.value as UpdatePhase.Failed
        assertTrue(failed.forced)

        coordinator.dismiss()

        assertEquals(failed, coordinator.phase.value)
    }

    @Test
    fun `an optional failure is dismissible`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()
        assertTrue(coordinator.phase.value is UpdatePhase.Failed)

        coordinator.dismiss()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `the backup and install stages each reach the phase`() = runTest {
        // Read from inside the mocks rather than from a call order: `coVerifyOrder` proves the
        // collaborators ran, not that either phase was ever published.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        val seen = mutableListOf<UpdatePhase>()
        lateinit var coordinator: UpdateCoordinator
        coEvery { backupStore.backupCurrentApk(any()) } coAnswers {
            seen.add(coordinator.phase.value)
            AppResult.Success(Unit)
        }
        coEvery { installer.install(any()) } coAnswers {
            seen.add(coordinator.phase.value)
            InstallOutcome.Committed
        }
        coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(
            listOf<UpdatePhase>(
                UpdatePhase.BackingUp(forced = false, trigger = UpdateTrigger.Background),
                UpdatePhase.Installing(forced = false, trigger = UpdateTrigger.Background),
            ),
            seen,
        )
    }

    @Test
    fun `a forced install awaiting confirmation stays forced and cannot be dismissed`() = runTest {
        // `forced` is not cosmetic here: UpdateHost branches on it to choose an inescapable overlay
        // over a dismissible drawer.
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.AwaitingConfirmation
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        val pending = UpdatePhase.ConfirmationPending(info(latest = 7, minSupported = 7), forced = true)
        assertEquals(pending, coordinator.phase.value)

        // Not a forced-guard branch: the session is live and the notification still carries the
        // confirmation, so dismissing the in-app surface must not discard it either way.
        coordinator.dismiss()
        assertEquals(pending, coordinator.phase.value)
    }

    /** Every phase the pipeline publishes between the check and the install, in order. */
    private suspend fun progressOf(run: suspend (UpdateCoordinator) -> Unit): List<UpdatePhase> {
        val seen = mutableListOf<UpdatePhase>()
        lateinit var coordinator: UpdateCoordinator
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            secondArg<suspend (Long, Long) -> Unit>()(50, 100)
            seen.add(coordinator.phase.value)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        coEvery { backupStore.backupCurrentApk(any()) } coAnswers {
            seen.add(coordinator.phase.value)
            AppResult.Success(Unit)
        }
        coEvery { installer.install(any()) } coAnswers {
            seen.add(coordinator.phase.value)
            InstallOutcome.Committed
        }
        coordinator = coordinator()
        run(coordinator)
        seen.add(coordinator.phase.value)
        return seen
    }

    @Test
    fun `a background attempt keeps its progress off the operator's screen`() = runTest {
        // The regression this branch introduced: before the worker existed, runUpdate only ran from
        // UpdateViewModel.init, at a launch the operator had just performed. UpdateScheduler
        // constrains the periodic work on network alone, so it now routinely overlaps a live
        // journey — and every one of these phases renders as a full-screen overlay above the app's
        // whole chrome, log out included, with no action on it to escape by.
        serverSays(AppResult.Success(info()))
        lateinit var job: Job

        val seen = progressOf { coordinator ->
            job = launch { coordinator.runUpdate(Presence.Headless) }
            runCurrent()
        }

        assertEquals(4, seen.size)
        seen.forEach {
            assertTrue("$it should report work in flight", it is UpdatePhase.Progress)
            assertFalse("$it must not reach the operator", (it as UpdatePhase.Progress).visibleToOperator)
        }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `an attempt the operator started shows its progress`() = runTest {
        serverSays(AppResult.Success(info()))
        lateinit var job: Job

        val seen = progressOf { coordinator ->
            coordinator.runUpdate(Presence.Foreground)
            advanceUntilIdle()
            job = launch { coordinator.accept() }
            runCurrent()
        }

        assertEquals(4, seen.size)
        seen.forEach {
            assertTrue("$it must be shown", (it as UpdatePhase.Progress).visibleToOperator)
        }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `a forced background attempt still takes the screen`() = runTest {
        // The denial path: suppressing a forced update's progress would stop it blocking, and
        // blocking a build the back office has declared unusable is the point of forcing it.
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        lateinit var job: Job

        val seen = progressOf { coordinator ->
            job = launch { coordinator.runUpdate(Presence.Headless) }
            runCurrent()
        }

        assertEquals(4, seen.size)
        seen.forEach {
            assertTrue("$it must block", (it as UpdatePhase.Progress).visibleToOperator)
        }

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `a stopped worker does not park the app on a progress phase`() = runTest {
        // WorkManager stops a CoroutineWorker routinely — a Wi-Fi blip mid-download, the ~10 minute
        // execution limit, doze, quota — and that cancels doWork()'s coroutine. Without a recovery
        // the phase stays on Downloading forever, which the UI renders as an actionless full-screen
        // overlay nobody in a clinic can escape.
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            delay(1_000)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        val coordinator = coordinator()

        val job = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()
        assertTrue(coordinator.phase.value is UpdatePhase.Downloading)

        job.cancelAndJoin()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a cancelled operator gesture does not park the app on a progress phase`() = runTest {
        // The same parking through the other door: accept(), retry() and returnedFromSettings() all
        // reach the pipeline from viewModelScope, which dies with the screen.
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            delay(1_000)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        val job = launch { coordinator.accept() }
        runCurrent()
        assertTrue(coordinator.phase.value is UpdatePhase.Downloading)

        job.cancelAndJoin()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a restarting phase survives an attempt that is merely still running`() = runTest {
        // The trap in the recovery above: Restarting is reached on a *successful* install, so the
        // discriminator has to be cancellation, not "a progress phase is standing".
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        val job = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()
        assertEquals(restarting(UpdateTrigger.Background), coordinator.phase.value)

        advanceTimeBy(RESTARTING_SETTLE_MILLIS - 1)
        assertEquals(restarting(UpdateTrigger.Background), coordinator.phase.value)

        advanceUntilIdle()
        job.join()
        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a reused download is re-verified before it is installed`() = runTest {
        // The one place where "the bytes we verified are the bytes we install" was an assumption
        // rather than a check: the retry path handed the cached file straight to the installer on
        // nothing but a matching version code.
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
        assertEquals(RetryTarget.INSTALL, (coordinator.phase.value as UpdatePhase.Failed).retry)

        // Same name, same length, different bytes.
        apkFile.writeBytes(ByteArray(APK_BYTES.size) { 0 })
        coordinator.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    private companion object {
        const val BASE_URL = "https://backoffice.example.com/api/v1/"
        val APK_BYTES = ByteArray(64) { it.toByte() }
        val APK_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(APK_BYTES)
            .joinToString("") { "%02x".format(it) }
    }
}
