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
 * Future confirmation-free path (deliberately NOT enabled yet): once this app has performed one
 * self-update it becomes its own installer of record, and on Android 12+ declaring
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` plus `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`
 * would let subsequent updates commit without the confirmation screen.
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
        abandonCommittedSessions(installer)
        val sessionId = installer.createSession(sessionParams(apk))
        try {
            installer.openSession(sessionId).use { session ->
                writeApk(session, apk)
                awaitOutcome(sessionId) { session.commit(statusIntentSender(sessionId)) }
            }
        } catch (e: IOException) {
            abandonQuietly(installer, sessionId)
            InstallOutcome.Failed(e.message)
        }
    }

    override suspend fun abandonStaleSessions(): Unit = withContext(dispatcher) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { info ->
            if (!isAwaitingConfirmation(info)) abandonQuietly(installer, info.sessionId)
        }
    }

    private fun sessionParams(apk: File) =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
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
        }
    }

    /**
     * A committed session is one the platform has accepted and is holding open — on this fleet,
     * because it is waiting for the operator to tap the confirmation an [UpdateNotifications]
     * notification is carrying. Abandoning it would leave a notification that does nothing, and on
     * the V2s (API 30) that notification is the ONLY way an update ever completes.
     *
     * The alternative — remembering the session id ourselves — cannot work: launch housekeeping
     * runs before any install in a process, and the id does not survive the process death that a
     * reboot causes. The platform's own record does.
     *
     * `isCommitted` is API 29+; the whole fleet is API 30+, and below 29 the old sweep-everything
     * behaviour is unchanged.
     */
    private fun isAwaitingConfirmation(info: PackageInstaller.SessionInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isCommitted

    /**
     * Keeps exactly one committed session alive: the newest. An older committed session is one the
     * operator never confirmed, and it is already unreachable — [UpdateNotifications] posts under a
     * single id, so the notification carrying its intent was replaced by this attempt's. Left alone
     * they accumulate against the per-UID session cap until `createSession` refuses outright.
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
