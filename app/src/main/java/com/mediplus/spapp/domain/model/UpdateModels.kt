package com.mediplus.spapp.domain.model

import java.io.File

/**
 * What the back office says the newest build is, as served by `GET /app/releases/latest`
 * (see docs/superpowers/specs/2026-07-24-self-update-design.md). [sha256] is the hex digest of the
 * APK bytes at [apkUrl]; [sizeBytes] is authoritative for both progress totals and the size check.
 *
 * [updateRequired] and [updateAvailable] are computed server-side so the "must update" rule lives
 * in one place. [minSupportedVersionCode] is no longer part of the published payload; it is still
 * carried and still checked so that a server which omits the verdicts degrades to the client's own
 * comparison rather than to nothing, but against the current contract it parses to 0 and never
 * decides anything.
 */
data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSupportedVersionCode: Int,
    val updateRequired: Boolean = false,
    val updateAvailable: Boolean = true,
    val releaseNotes: String? = null,
)

/**
 * The gated verdict on an [UpdateInfo] relative to the running build. Only [Forced] blocks the
 * journey; the server controls the split via the `updateRequired` verdict it computes.
 */
sealed interface UpdateStatus {

    /** No newer installable build exists (including "endpoint not deployed yet"). */
    data object UpToDate : UpdateStatus

    /** A newer build exists but this one is still supported; the operator may defer. */
    data class Optional(val info: UpdateInfo) : UpdateStatus

    /** This build is below the supported floor; the app must update before continuing. */
    data class Forced(val info: UpdateInfo) : UpdateStatus
}

/**
 * The running build's identity, injected from BuildConfig so nothing above the DI layer touches
 * BuildConfig directly.
 */
data class CurrentAppVersion(val code: Int, val name: String)

/** A downloaded APK whose digest and size have already been verified against [UpdateInfo]. */
data class DownloadedApk(val file: File, val versionCode: Int)
