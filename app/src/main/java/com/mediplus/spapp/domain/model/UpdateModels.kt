package com.mediplus.spapp.domain.model

import java.io.File

/**
 * What the back office says the newest build is, as served by `GET /app/version`
 * (see docs/superpowers/specs/2026-07-24-self-update-design.md). [sha256] is the hex digest of the
 * APK bytes at [apkUrl]; [sizeBytes] is authoritative for both progress totals and the size check.
 */
data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSupportedVersionCode: Int,
)

/**
 * The gated verdict on an [UpdateInfo] relative to the running build. Only [Forced] blocks the
 * journey; the server controls the split via `minSupportedVersionCode`.
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
