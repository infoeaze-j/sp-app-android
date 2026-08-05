package com.mediplus.spapp.core.update

import java.io.File

/**
 * Installs a verified update APK. This interface is the containment seam for the platform
 * installer: no `android.content.pm.PackageInstaller` type may cross it (the FaceCamera /
 * MemberCardReader rule), so ViewModels stay platform-free and JVM-testable.
 */
interface ApkInstaller {

    /**
     * Whether this app may currently request package installs. `false` means the operator must
     * grant the unknown-sources permission in Settings first. Always `true` below API 26, where
     * only the global installer setting governs (surfaced at commit as a failure if blocked).
     * Suspends so the debug switching decorator can consult the dev store per call.
     */
    suspend fun canRequestInstalls(): Boolean

    /**
     * Streams [apk] into an install session and commits it. Suspends until the platform reports a
     * terminal status — which, for a successful self-update, it usually never does: the system
     * kills this process mid-install, so success manifests as death, not as a return value.
     *
     * The one wait that is bounded is a confirmation the operator has been shown and not answered;
     * that settles as [InstallOutcome.AwaitingConfirmation] rather than holding the caller (and,
     * through it, the attempt lock) until they come back.
     */
    suspend fun install(apk: File): InstallOutcome

    /**
     * Abandons leftover sessions from crashed attempts; they count against a system quota. A
     * session the platform reports as committed is spared, whichever kind it turns out to be: one
     * parked on a confirmation, where abandoning it would leave the operator a notification that
     * does nothing, or one the platform is installing silently, where abandoning it would throw
     * away an update already under way.
     */
    suspend fun abandonStaleSessions()
}

/** Terminal outcomes of [ApkInstaller.install] that can be observed from a surviving process. */
sealed interface InstallOutcome {

    /** The system reported success while this process is (unusually) still alive. */
    data object Committed : InstallOutcome

    /** The operator declined the system confirmation. The verified APK is kept for a retry. */
    data object Aborted : InstallOutcome

    /**
     * The platform demanded a confirmation and nobody has answered it: either nobody was
     * foregrounded, so it went straight to a notification, or a dialog was raised and left untouched
     * long enough that waiting on it stopped being worth the attempt lock. Either way a notification
     * carries the confirmation and the session stays open and committed until the operator taps.
     *
     * Not a success — nothing has installed — and not a failure; nothing has gone wrong.
     */
    data object AwaitingConfirmation : InstallOutcome

    /** Any other installer failure (signature mismatch, storage, blocked source). */
    data class Failed(val statusMessage: String?) : InstallOutcome
}
