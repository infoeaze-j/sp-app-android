package com.mediplus.spapp.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import com.mediplus.spapp.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * The real [ApkInstaller], backed by a [PackageInstaller] session: write + fsync the verified APK,
 * commit with a status receiver, and suspend until a terminal status arrives on [InstallEventBus].
 *
 * Every commit requests `USER_ACTION_NOT_REQUIRED` (API 31+) and reacts to whatever the platform
 * answers — a terminal status means it installed unattended, `STATUS_PENDING_USER_ACTION` means a
 * human is needed. Nothing here branches on the OS version to predict which. The permission that
 * makes silence possible keys off being the installer of record, which this app becomes only after
 * it has installed itself once, so the first self-update on a sideloaded device may still ask.
 */
class PackageInstallerApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: InstallEventBus,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ApkInstaller {

    override suspend fun canRequestInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // No per-app grant exists; the global unknown-sources setting governs at commit time.
            true
        }

    override suspend fun install(apk: File): InstallOutcome = withContext(dispatcher) {
        val installer = context.packageManager.packageInstaller
        var opened: Int? = null
        try {
            abandonCommittedSessions(installer)
            val sessionId = installer.createSession(sessionParams(apk))
            opened = sessionId
            installer.openSession(sessionId).use { session ->
                writeApk(session, apk)
                awaitOutcome(sessionId) { session.commit(statusIntentSender(sessionId)) }
            }
        } catch (e: IOException) {
            opened?.let { abandonQuietly(installer, it) }
            InstallOutcome.Failed(e.message)
        }
    }

    override suspend fun abandonStaleSessions(): Unit = withContext(dispatcher) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { info ->
            if (!isAwaitingConfirmation(info)) abandonQuietly(installer, info.sessionId)
        }
    }

    /**
     * Always asks for a confirmation-free commit and lets the platform answer, rather than deciding
     * from [Build.VERSION.SDK_INT] whether it is available (design 2026-08-03 §3). Sunmi ships
     * modified Android: a V3 reporting API 33 may still refuse, and a version check that assumed
     * silence would wait for a terminal status that never arrives.
     *
     * The guard here is API availability only — `setRequireUserAction` does not exist before
     * API 31. Below that the request is simply not made and the platform answers
     * `STATUS_PENDING_USER_ACTION`, which is the whole of the V2s path.
     */
    private fun sessionParams(apk: File) =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

    private fun writeApk(session: PackageInstaller.Session, apk: File) {
        session.openWrite(BASE_APK_NAME, 0, apk.length()).use { output ->
            apk.inputStream().use { input -> input.copyTo(output) }
            session.fsync(output)
        }
    }

    /** Subscribes to the bus BEFORE committing so the terminal status can never be missed. */
    private suspend fun awaitOutcome(sessionId: Int, commit: () -> Unit): InstallOutcome =
        coroutineScope {
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                bus.events.first { it.sessionId == sessionId && it.isTerminal }
            }
            commit()
            val event = terminal.await()
            when {
                event.awaitingConfirmation -> InstallOutcome.AwaitingConfirmation
                event.status == PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Committed
                event.status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Aborted
                else -> InstallOutcome.Failed(event.message)
            }
        }

    private fun statusIntentSender(sessionId: Int): IntentSender {
        val intent = Intent(context, UpdateStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Mutable so the platform installer can attach its status extras; safe because the
            // intent targets our own non-exported receiver by explicit component.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private fun abandonQuietly(installer: PackageInstaller, sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (_: SecurityException) {
            // Not ours to abandon; nothing to clean.
        } catch (_: IllegalArgumentException) {
            // Already gone — listed by mySessions, then finalised before we got here.
        }
    }

    /**
     * A committed session is one the platform has accepted and is holding open. Despite the name,
     * `isCommitted` is broader than "awaiting confirmation": it becomes true the moment `commit()`
     * is called, so on a device where [sessionParams] secured `USER_ACTION_NOT_REQUIRED` a committed
     * session may instead be one the platform is silently installing right now, with no operator
     * involved at all. What still makes it safe to sweep here is not any property of the session —
     * it is [UpdateCoordinator]'s attempt lock, which serialises attempts so this can never run
     * concurrently with an in-flight install of this app's own making.
     *
     * On this fleet a committed session that IS awaiting a tap is one an [UpdateNotifications]
     * notification is carrying. Abandoning it would leave a notification that does nothing, and on
     * the V2s (API 30) that notification is the ONLY way such an update ever completes.
     *
     * The alternative — remembering the session id ourselves — cannot work: launch housekeeping
     * runs before any install in a process, and the id does not survive the process death that a
     * reboot causes. The platform's own record does.
     *
     * `isCommitted` is API 29+; the whole fleet is API 30+, and below 29 the old sweep-everything
     * behaviour is unchanged for [abandonStaleSessions]. `minSdk` is 24, so that range is live code:
     * below API 29 this always answers `false`, which means [abandonCommittedSessions] abandons
     * nothing there either, and the accumulation it exists to prevent stays possible on API 24-28.
     * Not fixed, because the fleet floor is 30 and this guard was specified for that fleet.
     */
    private fun isAwaitingConfirmation(info: PackageInstaller.SessionInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isCommitted

    /**
     * Keeps exactly one committed session alive (API 29+): the newest. An older committed session is
     * one the operator never confirmed; abandoning it here delivers a terminal (aborted) status to
     * [UpdateStatusReceiver], which matches it against [UpdateNotifications] by session id before
     * clearing — so this can only ever retire the abandoned session's own notification, whatever
     * this attempt goes on to do (including ending in [InstallOutcome.Committed], a silent install
     * that posts no notification at all). Left alone, older committed sessions accumulate against
     * the per-UID session cap until `createSession` refuses outright.
     */
    private fun abandonCommittedSessions(installer: PackageInstaller) {
        installer.mySessions.forEach { info ->
            if (isAwaitingConfirmation(info)) abandonQuietly(installer, info.sessionId)
        }
    }

    private companion object {
        const val BASE_APK_NAME = "base.apk"
        const val ACTION_INSTALL_STATUS = "com.mediplus.spapp.INSTALL_STATUS"
    }
}
