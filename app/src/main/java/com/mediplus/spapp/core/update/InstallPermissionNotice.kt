package com.mediplus.spapp.core.update

/**
 * Reconciles the lost-install-permission notice against what an update attempt just ended in
 * (design 2026-08-03 §8).
 *
 * Android revokes permissions for unused apps from API 30, and the whole fleet is API 30 or above.
 * A device stripped of `REQUEST_INSTALL_PACKAGES` reaches [UpdatePhase.PermissionNeeded] and stops
 * there — silently, with nobody present, forever. Posting is the only signal that device would ever
 * produce, so it is posted rather than merely logged.
 *
 * It lives here, as a function of the phase, rather than inside [UpdateCoordinator] or
 * [UpdateWorker]:
 *
 * - Not the coordinator, because a `notifier` would be its seventh constructor parameter, which is
 *   exactly detekt's `LongParameterList` threshold.
 * - Not the worker's body, because there is no Robolectric here and `TestListenableWorkerBuilder`
 *   needs a real `Context`, so anything inside a worker is unreachable from the JVM suite.
 *
 * [presence] is a parameter rather than an assumption. It is tempting to say a worker run implies
 * nobody is watching, but [UpdateScheduler] constrains the periodic work on network alone — it runs
 * happily while the operator has the app open, and a `PRIORITY_HIGH` heads-up would then land on top
 * of the very permission surface they are already reading. With the app open the notice is neither
 * posted nor cleared: it is still true, and the in-app surface is the better place to act on it.
 *
 * The three outcomes, and why each phase earns the one it gets:
 *
 * - [UpdatePhase.PermissionNeeded] is the only positive evidence — `advance()` asked
 *   `canRequestInstalls()` and it answered no.
 * - [UpdatePhase.CheckFailed] is *no* evidence, and so is left untouched. It is reached before
 *   `advance()` ever evaluates the permission, so clearing on it would destroy the only standing
 *   signal merely because the back office was unreachable — repeatedly, since a transport failure
 *   retries with exponential backoff.
 * - Everything else clears. For [UpdatePhase.Failed] that is positive evidence the permission is
 *   present, since `advance()` got past the check in order to fail later. For the rest —
 *   [UpdatePhase.Idle] most of all — it proves nothing: `advance()` may never have run because there
 *   was nothing to install. They clear for the honest reason that no pending update remains for the
 *   notice to point at, and the next run that finds one re-posts. Clearing on
 *   [UpdatePhase.ConfirmationPending] is safe only because the notice carries its own notification
 *   id, so it can never cancel a live confirmation.
 *
 * Exhaustive by design: an `else` is how [UpdatePhase.CheckFailed] first slipped into the clearing
 * branch, and every future variant would have inherited the same mistake.
 */
internal fun UpdateNotifier.reconcileInstallPermission(phase: UpdatePhase, presence: Presence) {
    when (phase) {
        is UpdatePhase.PermissionNeeded ->
            if (presence == Presence.Headless) installPermissionRequired()
        is UpdatePhase.CheckFailed -> Unit
        UpdatePhase.Idle,
        UpdatePhase.Restarting,
        is UpdatePhase.UpdateAvailable,
        is UpdatePhase.Downloading,
        is UpdatePhase.BackingUp,
        is UpdatePhase.Installing,
        is UpdatePhase.ConfirmationPending,
        is UpdatePhase.Failed,
        -> installPermissionRestored()
    }
}
