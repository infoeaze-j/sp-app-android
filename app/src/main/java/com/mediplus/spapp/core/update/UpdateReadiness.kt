package com.mediplus.spapp.core.update

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mediplus.spapp.data.local.PrefsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two device settings an unattended update depends on, asked for at launch
 * (design 2026-08-03 §7 and §8). Both were designed and then left to the bench checklist as manual
 * per-device steps; this is the app doing them itself, so that skipping one on a single unit is no
 * longer how that unit silently stops updating.
 *
 * Neither ask is part of the update flow proper, and neither blocks anything: a device that refuses
 * both still updates exactly as well as it did before this class existed. What they buy is the
 * *headless* half — the notification is the only way a pending confirmation reaches anyone on a
 * device nobody opens, and the exemption is the only defence against Android stripping
 * `REQUEST_INSTALL_PACKAGES` from precisely the idle devices this whole design targets.
 *
 * Asked from the UI rather than from [UpdateCoordinator] or [UpdateWorker], because both asks raise
 * a system surface and so need a foreground Activity — and because the one moment that reliably has
 * a human in front of the device is the office pass's single manual launch.
 */
@Singleton
class UpdateReadiness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PrefsDataStore,
) {

    /**
     * Whether to raise the `POST_NOTIFICATIONS` dialog.
     *
     * Re-asked on every launch that finds the permission missing rather than recorded as done,
     * because the platform already rate-limits this one: two dismissals and Android stops showing
     * the dialog at all, so a launcher-fired ask cannot become a nuisance. Recording it ourselves
     * would only add a way to *under*-ask — a device that missed the first prompt would never see
     * another, and on a Sunmi V3 that costs it both update notifications for good.
     */
    fun notificationPermissionNeeded(): Boolean = notificationPermissionAskable(
        supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
    )

    /**
     * The Intent that lands on this app's unused-app-restrictions setting, or `null` when there is
     * nothing to ask. Marks the ask as made in the same call, so it can answer non-null at most once
     * per install — "consume" is the point, not a side effect.
     *
     * One-shot, unlike the notification ask above, because nothing rate-limits this one. It is a
     * plain `startActivity` into Settings with no dialog and no result: an operator who backs out of
     * it would be thrown into Settings again on every launch of an app they open every morning, and
     * would learn to back out of it faster. So it is asked once, at the office, where somebody is
     * deliberately setting the device up — and the bench checklist keeps its manual step as the
     * belt-and-braces cover for the operator who dismisses it.
     *
     * The flag can only ever suppress the ask, never cause one: [autoRevokeExemptionAskable] checks
     * the live platform answer first, so a device that already holds the exemption is never sent to
     * Settings regardless of what was persisted.
     */
    suspend fun consumeAutoRevokeExemptionAsk(): Intent? {
        // Unused-app permission reset, and the setting that exempts an app from it, are both API 30.
        // The whole fleet is above that floor; `minSdk 24` is what makes this live code.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val askable = autoRevokeExemptionAskable(
            exempt = context.packageManager.isAutoRevokeWhitelisted,
            alreadyAsked = prefs.autoRevokeExemptionAsked(),
        )
        if (!askable) return null
        prefs.markAutoRevokeExemptionAsked()
        return Intent(
            Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
            "package:${context.packageName}".toUri(),
        )
    }
}

/**
 * Ask for the notification grant when the platform has one to give and has not given it.
 *
 * `supported` is false below API 33, where notifications need no runtime grant — which is the half
 * of the fleet (Sunmi V2s) that depends on them most, and which must never be shown a dialog that
 * would do nothing.
 */
internal fun notificationPermissionAskable(supported: Boolean, granted: Boolean): Boolean =
    supported && !granted

/**
 * Ask for the unused-app-restrictions exemption once, and never on a device that already holds it.
 *
 * The `exempt` check comes from the platform on every call, so it also covers the two cases the
 * persisted flag cannot know about: an install that predates the flag, and a device an operator
 * exempted by hand from the bench checklist.
 */
internal fun autoRevokeExemptionAskable(exempt: Boolean, alreadyAsked: Boolean): Boolean =
    !exempt && !alreadyAsked

/**
 * Lets the update surface resolve [UpdateReadiness] directly, the way the face-check screen resolves
 * its camera factory: both asks belong to a Composable that can launch a system surface, and neither
 * is orchestration [UpdateViewModel] should carry. It also keeps the `android.content.Intent` the
 * exemption ask returns out of a ViewModel, which is the same containment rule the camera follows.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateReadinessEntryPoint {
    fun updateReadiness(): UpdateReadiness
}
