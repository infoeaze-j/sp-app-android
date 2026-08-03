package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import java.io.File
import java.security.MessageDigest

/**
 * Whether bytes on disk match the digest the back office published — the one question both a
 * pre-transfer skip check ([alreadyVerified]) and a post-transfer accept/reject decision ([verified])
 * come down to. Split out of [ApkTransfer]'s byte-moving mechanics because this is a different
 * concern: not how the bytes arrive, but whether they can be trusted once they have.
 */

/**
 * Whether [target] is already the finished, verified download for [info] — the same test [verified]
 * applies after a transfer, applied before starting one. Without it a completed-but-uninstalled APK
 * is deleted by `resumableBytes` and fetched again in full, which is the *normal* state of the
 * headless flow whenever it is parked waiting for a confirmation tap.
 *
 * Nothing is trusted that was not trusted before: the digest is still what decides.
 */
internal fun alreadyVerified(target: File, info: UpdateInfo): Boolean =
    target.length() == info.sizeBytes && digestOf(target).equals(info.sha256, ignoreCase = true)

private fun digestOf(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .apply { updateWithPrefixOf(file, file.length()) }
        .digest()
        .toHex()

internal fun verified(info: UpdateInfo, target: File, streamed: StreamedApk): AppResult<DownloadedApk> =
    if (streamed.bytes == info.sizeBytes && streamed.shaHex.equals(info.sha256, ignoreCase = true)) {
        AppResult.Success(DownloadedApk(target, info.latestVersionCode))
    } else {
        // Deleting here is what stops a bad prefix being resumed into the same failure forever.
        target.delete()
        AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
    }
