package com.mediplus.spapp.dev.update

import com.mediplus.spapp.core.update.ApkInstaller
import com.mediplus.spapp.core.update.InstallOutcome
import com.mediplus.spapp.core.update.PackageInstallerApkInstaller
import com.mediplus.spapp.dev.DevSettingsStore
import java.io.File
import javax.inject.Inject

/** Debug-only router: fake installer when the master toggle is on, else the real session API. */
class SwitchingApkInstaller @Inject constructor(
    private val real: PackageInstallerApkInstaller,
    private val fake: FakeApkInstaller,
    private val store: DevSettingsStore,
) : ApkInstaller {

    override suspend fun canRequestInstalls(): Boolean = pick().canRequestInstalls()

    override suspend fun install(apk: File): InstallOutcome = pick().install(apk)

    override suspend fun abandonStaleSessions() = pick().abandonStaleSessions()

    private suspend fun pick(): ApkInstaller = if (store.current().fakeEnabled) fake else real
}
