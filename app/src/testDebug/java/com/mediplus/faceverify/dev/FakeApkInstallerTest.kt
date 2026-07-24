package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.update.InstallOutcome
import com.mediplus.faceverify.dev.update.FakeApkInstaller
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeApkInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store = TestDevSettingsStore()
    private val installer = FakeApkInstaller(store)

    @Test
    fun `install permission is always granted on the fake`() = runTest {
        assertTrue(installer.canRequestInstalls())
    }

    @Test
    fun `INSTALL_FAILS fails the install`() = runTest {
        store.value = store.value.copy(update = UpdateScenario.INSTALL_FAILS)

        assertTrue(installer.install(tempFolder.newFile()) is InstallOutcome.Failed)
    }

    @Test
    fun `any other scenario commits`() = runTest {
        store.value = store.value.copy(update = UpdateScenario.OPTIONAL_UPDATE)

        assertEquals(InstallOutcome.Committed, installer.install(tempFolder.newFile()))
    }
}
