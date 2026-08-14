package com.mediplus.spapp.ui.update

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.components.ActionDrawer
import com.mediplus.spapp.core.ui.components.DrawerAction
import com.mediplus.spapp.core.ui.theme.LocalSpacing
import com.mediplus.spapp.core.update.UpdatePhase

/**
 * Hosts the whole self-update surface above the app chrome (drawn after the Scaffold in NavGraph,
 * so a forced update covers the top bar too). Optional interruptions use the app's one modal
 * surface, [ActionDrawer]; everything the operator must not escape uses a full-screen overlay; and
 * progress from an attempt nobody asked for is not shown at all — see [ProgressSurface].
 */
@Composable
fun UpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The two device settings the headless path depends on, asked for once at launch. Hosted here
    // because this composable is the one that sits above the whole app; it has nothing to do with
    // the phase below it.
    UpdateReadinessEffects()

    // Completes the unknown-sources round-trip: returning from Settings resumes the flow.
    LifecycleResumeEffect(Unit) {
        viewModel.onReturnedFromSettings()
        onPauseOrDispose { }
    }

    // A denial proceeds to onUpdateAccepted() exactly like a grant. Below API 29 the rollback backup
    // needs this permission to write to Downloads/SpApp/, so a denial costs the backup — but not the
    // update: since the backup stopped being a gate, UpdatePipeline discards its result and installs
    // regardless. Rollback is a manual procedure anyway, so the operator loses a convenience rather
    // than the update itself.
    val legacyWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> viewModel.onUpdateAccepted() }
    val onAccept = {
        if (viewModel.needsLegacyWritePermission()) {
            legacyWriteLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.onUpdateAccepted()
        }
    }
    // Unreachable below API 26: canRequestInstalls() is always true there.
    val onOpenSettings = {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ),
        )
    }

    UpdatePhaseSurface(phase, viewModel, onAccept, onOpenSettings)
}

@Composable
private fun UpdatePhaseSurface(
    phase: UpdatePhase,
    viewModel: UpdateViewModel,
    onAccept: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (phase) {
        UpdatePhase.Idle -> Unit
        is UpdatePhase.CheckFailed -> UpdateDrawer(
            title = stringResource(phase.message.titleRes),
            body = stringResource(phase.message.bodyRes),
            confirmLabelRes = R.string.action_retry,
            onConfirm = viewModel::onRetry,
            onDismiss = viewModel::onDismissed,
        )
        is UpdatePhase.UpdateAvailable -> AvailableSurface(phase, onAccept, viewModel::onDismissed)
        is UpdatePhase.PermissionNeeded -> PermissionSurface(phase, onOpenSettings, viewModel::onDismissed)
        is UpdatePhase.Progress -> ProgressSurface(phase)
        is UpdatePhase.ConfirmationPending -> ConfirmationSurface(
            phase,
            viewModel::onRetry,
            viewModel::onDismissed,
        )
        is UpdatePhase.Failed -> FailedSurface(phase, viewModel::onRetry, viewModel::onDismissed)
    }
}

/**
 * Work in flight, and the only surface the operator may never be shown at all.
 *
 * The periodic worker is constrained on network alone, so a background attempt routinely overlaps a
 * live journey. Rendering its progress would take the whole screen — chrome, log out and all — from
 * an operator mid-NFC-tap or mid-face-capture, for the length of a download plus a ~50 MB backup
 * copy plus a ~50 MB session write, with no action on it to escape by. The rule that decides this
 * is `visibleToOperator`, which lives on [UpdatePhase.Progress] so there is one copy of it; every
 * phase that needs a human is not a [UpdatePhase.Progress] and reaches its own surface above,
 * however it was produced.
 */
@Composable
private fun ProgressSurface(phase: UpdatePhase.Progress) {
    if (!phase.visibleToOperator) return
    when (phase) {
        is UpdatePhase.Downloading -> UpdateOverlay(stringResource(R.string.update_downloading)) {
            DownloadProgress(phase)
        }
        is UpdatePhase.BackingUp -> UpdateOverlay(stringResource(R.string.update_backing_up)) {
            CircularProgressIndicator()
        }
        is UpdatePhase.Installing -> UpdateOverlay(stringResource(R.string.update_installing)) {
            CircularProgressIndicator()
        }
        is UpdatePhase.Restarting -> UpdateOverlay(stringResource(R.string.update_restarting)) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AvailableSurface(
    phase: UpdatePhase.UpdateAvailable,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (phase.forced) {
        UpdateOverlay(
            title = stringResource(R.string.update_required_title),
            body = stringResource(R.string.update_required_body, phase.info.latestVersionName),
            actionLabel = stringResource(R.string.action_update_now),
            onAction = onAccept,
        )
    } else {
        UpdateDrawer(
            title = stringResource(R.string.update_available_title),
            body = stringResource(R.string.update_available_body, phase.info.latestVersionName),
            confirmLabelRes = R.string.action_update_now,
            onConfirm = onAccept,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PermissionSurface(
    phase: UpdatePhase.PermissionNeeded,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (phase.forced) {
        UpdateOverlay(
            title = stringResource(R.string.update_permission_title),
            body = stringResource(R.string.update_permission_body),
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = onOpenSettings,
        )
    } else {
        UpdateDrawer(
            title = stringResource(R.string.update_permission_title),
            body = stringResource(R.string.update_permission_body),
            confirmLabelRes = R.string.action_open_settings,
            onConfirm = onOpenSettings,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The operator opened the app while an install was waiting on a notification tap. The action raises
 * the system confirmation directly, which is strictly better than sending them back to the shade.
 */
@Composable
private fun ConfirmationSurface(
    phase: UpdatePhase.ConfirmationPending,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (phase.forced) {
        UpdateOverlay(
            title = stringResource(R.string.update_confirm_title),
            body = stringResource(R.string.update_confirm_body),
            actionLabel = stringResource(R.string.action_install_now),
            onAction = onConfirm,
        )
    } else {
        UpdateDrawer(
            title = stringResource(R.string.update_confirm_title),
            body = stringResource(R.string.update_confirm_body),
            confirmLabelRes = R.string.action_install_now,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun FailedSurface(
    phase: UpdatePhase.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (phase.forced) {
        UpdateOverlay(
            title = stringResource(phase.message.titleRes),
            body = stringResource(phase.message.bodyRes),
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
    } else {
        UpdateDrawer(
            title = stringResource(phase.message.titleRes),
            body = stringResource(phase.message.bodyRes),
            confirmLabelRes = R.string.action_retry,
            onConfirm = onRetry,
            onDismiss = onDismiss,
        )
    }
}

/** The dismissible form of every update interruption: the app's shared drawer with two actions. */
@Composable
private fun UpdateDrawer(
    title: String,
    body: String,
    confirmLabelRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionDrawer(
        title = title,
        confirm = DrawerAction(labelRes = confirmLabelRes, onClick = onConfirm),
        dismiss = DrawerAction(labelRes = R.string.action_update_later, onClick = onDismiss),
    ) {
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The inescapable form: a full-screen surface above the chrome with no way out but the one action
 * (or none while work is in flight).
 */
@Composable
private fun UpdateOverlay(
    body: String,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = spacing.md),
            )
            Column(
                modifier = Modifier.padding(top = spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = spacing.lg)
                        .heightIn(min = spacing.minTouchTarget),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(phase: UpdatePhase.Downloading) {
    val spacing = LocalSpacing.current
    val fraction = if (phase.totalBytes > 0) {
        (phase.bytesSoFar.toFloat() / phase.totalBytes).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.update_percent, (fraction * PERCENT).toInt()),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = spacing.sm),
    )
}

private const val PERCENT = 100
