package com.mediplus.faceverify.core.update

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.domain.model.CurrentAppVersion

/**
 * Keeps the rollback backup: a copy of the CURRENTLY INSTALLED APK in shared storage that survives
 * uninstall, so a technician can revert a bad release by uninstalling the app and installing the
 * backup from the Files app (Android forbids in-place downgrades). Containment seam for
 * MediaStore/storage types, mirroring [ApkInstaller].
 */
interface ApkBackupStore {

    /**
     * Copies the installed APK to shared Downloads as `faceverify-backup-v{code}.apk`, replacing
     * any previous copy of the same version. The install flow never proceeds when this fails.
     */
    suspend fun backupCurrentApk(version: CurrentAppVersion): AppResult<Unit>

    /**
     * Deletes backups made stale by a successful update, per [BackupRotation]. Best-effort and
     * silent: runs at every launch, never on a failure path, and swallows ownership loss (e.g.
     * after a manual rollback the fresh install does not own the old MediaStore rows).
     */
    suspend fun pruneStaleBackups(currentVersionCode: Int)

    /** True below API 29, where writing shared Downloads needs WRITE_EXTERNAL_STORAGE at runtime. */
    fun needsLegacyWritePermission(): Boolean
}
