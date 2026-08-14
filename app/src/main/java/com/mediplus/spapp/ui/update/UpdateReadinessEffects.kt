package com.mediplus.spapp.ui.update

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mediplus.spapp.core.update.UpdateReadiness
import com.mediplus.spapp.core.update.UpdateReadinessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * Raises the two launch-time asks an unattended update depends on (design 2026-08-03 §7, §8), one
 * after the other: the notification grant first, then the unused-app-restrictions exemption.
 *
 * They are strictly sequenced, and the sequencing is the reason this is not two independent effects.
 * The exemption ask is a `startActivity` into Settings, so firing it while the permission dialog is
 * up would bury the dialog under a screen the operator never asked for — and `launch()` returns the
 * instant the dialog is *requested*, not when it is answered. So the exemption ask hangs off the
 * permission result, and runs directly only when there was no permission to ask for (every device
 * below API 33, which is the Sunmi V2s half of the fleet).
 *
 * Fired from a `LaunchedEffect(Unit)` inside [UpdateHost], which sits above the whole app: once per
 * Activity creation, before sign-in, which is exactly the office pass's single manual launch. It is
 * deliberately not tied to the update flow — a device with no update pending still needs both
 * settings in place *before* the day it has one.
 *
 * Nothing here can fail in a way worth reporting, and nothing here blocks. Both asks are
 * best-effort: the grant is read back from the platform the next time a notification is posted, and
 * the exemption is read back from the platform on the next launch.
 */
@Composable
internal fun UpdateReadinessEffects() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readiness = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, UpdateReadinessEntryPoint::class.java)
            .updateReadiness()
    }
    // The grant result is ignored on purpose: a denial degrades the headless path to "installs the
    // next time somebody opens the app", which is what the app did before any of this existed. What
    // the callback is for is the ordering above — it fires either way, including on the platform's
    // own auto-denial after two dismissals, so the exemption ask can never be stranded behind it.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> scope.launch { askForAutoRevokeExemption(readiness, context) } }

    LaunchedEffect(Unit) {
        if (readiness.notificationPermissionNeeded()) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askForAutoRevokeExemption(readiness, context)
        }
    }
}

private suspend fun askForAutoRevokeExemption(readiness: UpdateReadiness, context: Context) {
    readiness.consumeAutoRevokeExemptionAsk()?.let(context::startActivity)
}
