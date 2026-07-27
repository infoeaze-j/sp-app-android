package com.mediplus.spapp.dev.update

import com.mediplus.spapp.core.update.ApkInstaller
import com.mediplus.spapp.core.update.InstallOutcome
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.UpdateScenario
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

/**
 * Fake installer: no session, no process death. Success returns [InstallOutcome.Committed], which
 * the flow renders as its restarting state — the closest a bare emulator gets to a real install.
 */
class FakeApkInstaller @Inject constructor(
    private val store: DevSettingsStore,
) : ApkInstaller {

    override suspend fun canRequestInstalls(): Boolean = true

    override suspend fun install(apk: File): InstallOutcome {
        val settings = store.current()
        delay(settings.latencyMillis)
        return if (settings.update == UpdateScenario.INSTALL_FAILS) {
            InstallOutcome.Failed("DEV_SCENARIO_INSTALL_FAILS")
        } else {
            InstallOutcome.Committed
        }
    }

    override suspend fun abandonStaleSessions() = Unit
}
