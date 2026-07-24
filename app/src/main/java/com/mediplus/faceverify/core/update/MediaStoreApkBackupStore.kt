package com.mediplus.faceverify.core.update

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * The real [ApkBackupStore]: `Downloads/FaceVerify/` via MediaStore on API 29+, the public
 * Downloads directory (plus the legacy write permission) below. Real in BOTH build types — it
 * works on a bare emulator, so the debug fake stack does not wrap it.
 *
 * Known edge: a crash mid-copy on API 29+ leaves an IS_PENDING row that plain queries don't see;
 * the system expires it after about a week, and the next successful backup of the same version
 * writes a fresh row.
 */
class MediaStoreApkBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ApkBackupStore {

    override fun needsLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override suspend fun backupCurrentApk(version: CurrentAppVersion): AppResult<Unit> = withContext(dispatcher) {
        val installedApk = File(context.applicationInfo.sourceDir)
        try {
            if (needsLegacyWritePermission()) {
                legacyBackup(installedApk, version.code)
            } else {
                mediaStoreBackup(installedApk, version.code)
            }
            AppResult.Success(Unit)
        } catch (_: IOException) {
            backupFailed()
        } catch (_: SecurityException) {
            backupFailed()
        }
    }

    override suspend fun pruneStaleBackups(currentVersionCode: Int): Unit = withContext(dispatcher) {
        try {
            if (needsLegacyWritePermission()) {
                legacyPrune(currentVersionCode)
            } else {
                mediaStorePrune(currentVersionCode)
            }
        } catch (_: IOException) {
            // Best-effort by design; a missed prune self-heals on a later launch.
        } catch (_: SecurityException) {
            // After a manual rollback the fresh install doesn't own the old rows.
        }
    }

    private fun backupFailed() =
        AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreBackup(source: File, versionCode: Int) {
        val resolver = context.contentResolver
        val name = BackupRotation.backupFileName(versionCode)
        // Replace-by-name: without this, a same-name insert lands as "name (1).apk".
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, RELATIVE_DIR),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("backup row insert failed")
        val output = resolver.openOutputStream(uri) ?: throw IOException("backup stream unavailable")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStorePrune(currentVersionCode: Int) {
        val resolver = context.contentResolver
        val rows = mutableListOf<Pair<Long, String>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(RELATIVE_DIR),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(cursor.getLong(0) to cursor.getString(1))
            }
        }
        val stale = BackupRotation.staleBackups(rows.map { it.second }, currentVersionCode).toSet()
        rows.filter { (_, name) -> name in stale }.forEach { (id, _) ->
            resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
        }
    }

    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: the pre-Q path only.
    private fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LEGACY_SUBDIR)

    private fun legacyBackup(source: File, versionCode: Int) {
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("backup directory unavailable")
        val target = File(dir, BackupRotation.backupFileName(versionCode))
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun legacyPrune(currentVersionCode: Int) {
        val files = legacyDir().listFiles()?.toList().orEmpty()
        val stale = BackupRotation.staleBackups(files.map { it.name }, currentVersionCode).toSet()
        files.filter { it.name in stale }.forEach { it.delete() }
    }

    private companion object {
        // Not const: DIRECTORY_DOWNLOADS is a non-final platform field.
        val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/FaceVerify/"
        const val LEGACY_SUBDIR = "FaceVerify"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
