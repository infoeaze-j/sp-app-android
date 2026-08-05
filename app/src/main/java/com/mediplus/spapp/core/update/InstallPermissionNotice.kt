package com.mediplus.spapp.core.update

/**
 * Reconciles the lost-install-permission notice against what an attempt just ended in
 * (design 2026-08-03 §8).
 *
 * Android revokes permissions for unused apps from API 30, and the whole fleet is API 30 or above.
 * A device stripped of `REQUEST_INSTALL_PACKAGES` reaches [UpdatePhase.PermissionNeeded] and stops
 * there — silently, with nobody present, forever. This is the only signal that would ever exist for
 * that device, so it is posted rather than merely logged.
 *
 * It lives here, as a function of the phase, rather than inside [UpdateCoordinator] or [UpdateWorker]:
 *
 * - Not the coordinator, because a `notifier` would be its seventh constructor parameter, which is
 *   exactly detekt's `LongParameterList` threshold. Keeping it out also makes the headless-only rule
 *   hold by construction — the foreground path never calls this at all — rather than by an `if`.
 * - Not the worker's body, because there is no Robolectric here and `TestListenableWorkerBuilder`
 *   needs a real `Context`, so anything inside a worker is unreachable from the JVM suite.
 *
 * Every phase other than [UpdatePhase.PermissionNeeded] clears, including
 * [UpdatePhase.ConfirmationPending] — which is safe only because the notice carries its own
 * notification id, so clearing it can never cancel a live confirmation.
 */
internal fun UpdateNotifier.reconcileInstallPermission(phase: UpdatePhase) {
    if (phase is UpdatePhase.PermissionNeeded) {
        installPermissionRequired()
    } else {
        installPermissionRestored()
    }
}
