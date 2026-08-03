package com.mediplus.spapp.core.update

import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The launch-time tidy-up, run once per process before the first check: drop rollback backups for
 * builds we can no longer roll back to, abandon install sessions nobody is waiting on, and delete
 * downloads at or below the running build.
 *
 * It runs here and never on a failure path, so a failed update keeps its whole rollback chain. The
 * download prune is narrow by design: a partial download for a build still on offer survives it,
 * which is what lets an interrupted transfer resume after a restart.
 *
 * Split out of [UpdateCoordinator] rather than inlined because the coordinator would otherwise need
 * seven constructor arguments, which is exactly detekt's threshold. The three prunes share nothing
 * but their timing, so they lose nothing by sitting together.
 */
@Singleton
class UpdateHousekeeping @Inject constructor(
    private val backupStore: ApkBackupStore,
    private val installer: ApkInstaller,
    private val updateRepository: UpdateRepository,
    private val currentVersion: CurrentAppVersion,
) {

    private var done = false

    /** Idempotent per process: later attempts are no-ops, not repeated prunes. */
    suspend fun runOnce() {
        if (done) return
        done = true
        backupStore.pruneStaleBackups(currentVersion.code)
        installer.abandonStaleSessions()
        updateRepository.pruneObsoleteDownloads()
    }
}
