package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.update.InstallOutcome
import com.mediplus.faceverify.core.update.PackageInstallerApkInstaller
import com.mediplus.faceverify.data.repository.UpdateRepositoryImpl
import com.mediplus.faceverify.dev.repository.FakeUpdateRepository
import com.mediplus.faceverify.dev.update.FakeApkInstaller
import com.mediplus.faceverify.dev.update.SwitchingApkInstaller
import com.mediplus.faceverify.dev.repository.SwitchingUpdateRepository
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The update seams route per call: fake while the master toggle is on, real otherwise. */
class SwitchingUpdateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store = TestDevSettingsStore()
    private val realRepository = mockk<UpdateRepositoryImpl>()
    private val realInstaller = mockk<PackageInstallerApkInstaller>()

    private fun switchingRepository() = SwitchingUpdateRepository(
        real = realRepository,
        fake = FakeUpdateRepository(store, CurrentAppVersion(5, "1.4"), tempFolder.root),
        store = store,
    )

    private fun switchingInstaller() = SwitchingApkInstaller(
        real = realInstaller,
        fake = FakeApkInstaller(store),
        store = store,
    )

    @Test
    fun `repository uses the fake while the toggle is on`() = runTest {
        store.value = store.value.copy(fakeEnabled = true, update = UpdateScenario.UP_TO_DATE)

        assertEquals(AppResult.Success(null), switchingRepository().fetchVersionInfo())
    }

    @Test
    fun `repository uses the real impl while the toggle is off`() = runTest {
        store.value = store.value.copy(fakeEnabled = false)
        coEvery { realRepository.fetchVersionInfo() } returns AppResult.Timeout

        assertEquals(AppResult.Timeout, switchingRepository().fetchVersionInfo())
    }

    @Test
    fun `installer uses the fake while the toggle is on`() = runTest {
        store.value = store.value.copy(fakeEnabled = true, update = UpdateScenario.OPTIONAL_UPDATE)

        assertEquals(InstallOutcome.Committed, switchingInstaller().install(tempFolder.newFile()))
    }

    @Test
    fun `installer uses the real impl while the toggle is off`() = runTest {
        store.value = store.value.copy(fakeEnabled = false)
        coEvery { realInstaller.install(any()) } returns InstallOutcome.Aborted

        assertEquals(InstallOutcome.Aborted, switchingInstaller().install(tempFolder.newFile()))
    }
}
