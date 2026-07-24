package com.mediplus.faceverify.ui.update

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.DefaultErrorMapper
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.update.ApkBackupStore
import com.mediplus.faceverify.core.update.ApkInstaller
import com.mediplus.faceverify.core.update.InstallOutcome
import com.mediplus.faceverify.data.repository.UpdateRepository
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import com.mediplus.faceverify.domain.model.DownloadedApk
import com.mediplus.faceverify.domain.model.UpdateInfo
import com.mediplus.faceverify.domain.usecase.CheckForUpdateUseCase
import com.mediplus.faceverify.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The self-update orchestration (design: 2026-07-24-self-update-design.md): launch housekeeping
 * never blocks the first frame, the check fails open, forced updates cannot be dismissed, a
 * missing backup blocks the install, and retries re-enter at the right stage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

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
        coJustRun { repository.clearDownloads() }
        coJustRun { installer.abandonStaleSessions() }
        coJustRun { backupStore.pruneStaleBackups(any()) }
        every { installer.canRequestInstalls() } returns true
        every { backupStore.needsLegacyWritePermission() } returns false
    }

    private fun viewModel() = UpdateViewModel(
        checkForUpdate = CheckForUpdateUseCase(repository, currentVersion),
        updateRepository = repository,
        installer = installer,
        backupStore = backupStore,
        errorMapper = errorMapper,
        currentVersion = currentVersion,
    )

    private fun info(latest: Int = 7, minSupported: Int = 1) = UpdateInfo(
        latestVersionCode = latest,
        latestVersionName = "1.6",
        apkUrl = "https://backoffice.example.com/app/faceverify-$latest.apk",
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
    fun `launch housekeeping never blocks the first frame`() = runTest {
        serverSays(AppResult.Success(null))

        val vm = viewModel()

        assertEquals(UpdatePhase.Idle, vm.phase.value)
    }

    @Test
    fun `up to date stays idle after launch housekeeping runs once`() = runTest {
        serverSays(AppResult.Success(null))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, vm.phase.value)
        coVerify(exactly = 1) { backupStore.pruneStaleBackups(5) }
        coVerify(exactly = 1) { installer.abandonStaleSessions() }
        coVerify(exactly = 1) { repository.clearDownloads() }
    }

    @Test
    fun `a failed check surfaces a dismissible notice`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))

        val vm = viewModel()
        advanceUntilIdle()

        val phase = vm.phase.value as UpdatePhase.CheckFailed
        assertEquals(
            errorMapper.toUserMessage(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            phase.message,
        )
    }

    @Test
    fun `dismissing the check notice returns to idle`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDismissed()

        assertEquals(UpdatePhase.Idle, vm.phase.value)
    }

    @Test
    fun `retrying a failed check re-checks`() = runTest {
        serverSays(
            AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            AppResult.Success(null),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRetry()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, vm.phase.value)
        coVerify(exactly = 2) { repository.fetchVersionInfo() }
    }

    @Test
    fun `an optional update offers a dismissible prompt`() = runTest {
        serverSays(AppResult.Success(info(latest = 7, minSupported = 1)))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpdateAvailable(info(latest = 7, minSupported = 1), forced = false), vm.phase.value)

        vm.onDismissed()
        assertEquals(UpdatePhase.Idle, vm.phase.value)
    }

    @Test
    fun `a forced update cannot be dismissed`() = runTest {
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDismissed()

        assertEquals(UpdatePhase.UpdateAvailable(info(latest = 7, minSupported = 7), forced = true), vm.phase.value)
    }

    @Test
    fun `accepting without install permission routes through settings and resumes`() = runTest {
        serverSays(AppResult.Success(info()))
        every { installer.canRequestInstalls() } returns false
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()
        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), vm.phase.value)

        every { installer.canRequestInstalls() } returns true
        vm.onReturnedFromSettings()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Restarting, vm.phase.value)
    }

    @Test
    fun `returning from settings without the grant stays put`() = runTest {
        serverSays(AppResult.Success(info()))
        every { installer.canRequestInstalls() } returns false
        val vm = viewModel()
        advanceUntilIdle()
        vm.onUpdateAccepted()
        advanceUntilIdle()

        vm.onReturnedFromSettings()
        advanceUntilIdle()

        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), vm.phase.value)
    }

    @Test
    fun `the happy path walks download then backup then install`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Restarting, vm.phase.value)
        coVerifyOrder {
            repository.downloadAndVerify(info(), any())
            backupStore.backupCurrentApk(currentVersion)
            installer.install(apkFile)
        }
    }

    @Test
    fun `download progress reaches the phase as it streams`() = runTest {
        serverSays(AppResult.Success(info()))
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val seen = mutableListOf<UpdatePhase>()
        lateinit var vm: UpdateViewModel
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            seen.add(vm.phase.value)
            onProgress(100, 100)
            seen.add(vm.phase.value)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
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
    fun `a corrupted download fails with a fresh-download retry`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()

        assertEquals(
            UpdatePhase.Failed(
                message = businessMessage(BusinessCode.UPDATE_CORRUPTED),
                info = info(),
                forced = false,
                retry = RetryTarget.DOWNLOAD,
            ),
            vm.phase.value,
        )

        vm.onRetry()
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `a backup failure blocks the install`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()

        val phase = vm.phase.value as UpdatePhase.Failed
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
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()

        val phase = vm.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_ABORTED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)

        vm.onRetry()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Restarting, vm.phase.value)
        coVerify(exactly = 1) { repository.downloadAndVerify(any(), any()) }
        coVerify(exactly = 2) { installer.install(apkFile) }
    }

    @Test
    fun `retrying an install after cache eviction re-downloads`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.Aborted, InstallOutcome.Committed)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onUpdateAccepted()
        advanceUntilIdle()

        assertTrue(apkFile.delete())
        vm.onRetry()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `any other install failure is retryable at the install stage`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")
        val vm = viewModel()
        advanceUntilIdle()

        vm.onUpdateAccepted()
        advanceUntilIdle()

        val phase = vm.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
    }

    @Test
    fun `denying the legacy storage permission fails the backup path`() = runTest {
        serverSays(AppResult.Success(info()))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLegacyWriteDenied()

        val phase = vm.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_BACKUP_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
    }
}
