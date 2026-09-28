package com.mediplus.spapp.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/*
 * Permission reads and trips into system Settings, shared by the screens that ask for them. A trip
 * to a specific page falls back to this app's own details page when the page is missing — Sunmi
 * ships modified Android, and a Settings screen that exists on stock may not exist there.
 */

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/** This app's "Install unknown apps" switch. The per-app page only exists from Android 8. */
fun Context.openInstallSourceSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return openAppSettings()
    openOrFallBack(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
}

fun Context.openNfcSettings() = openOrFallBack(Intent(Settings.ACTION_NFC_SETTINGS))

private fun Context.openOrFallBack(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        openAppSettings()
    }
}
