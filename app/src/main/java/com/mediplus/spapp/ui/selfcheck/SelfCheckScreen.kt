package com.mediplus.spapp.ui.selfcheck

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.openAppSettings
import com.mediplus.spapp.core.ui.openInstallSourceSettings
import com.mediplus.spapp.core.ui.openNfcSettings
import com.mediplus.spapp.core.ui.theme.LocalSpacing
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.PERMISSION_CHECKS
import com.mediplus.spapp.domain.model.SelfCheckItem

private val NETWORK_ROWS = listOf(SelfCheckItem.LOCAL_NETWORK, SelfCheckItem.INTERNET)
private val SERVER_ROWS = listOf(
    SelfCheckItem.API,
    SelfCheckItem.ENDPOINT_LOGIN,
    SelfCheckItem.ENDPOINT_SESSION,
    SelfCheckItem.ENDPOINT_MEMBER_VERIFY,
)

/**
 * The operator self check, opened from sign-in: network, server, permissions and a touch-screen
 * test, so an operator — or whoever they phone — can see *why* the app is not working rather than
 * only that it is not. It sits outside the journey (back returns to sign-in) and before any session
 * exists, so there is no app bar and nothing to log out of.
 */
@Composable
fun SelfCheckRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SelfCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // A permission fixed in Settings shows as fixed the moment the operator comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }
    val fix = rememberPermissionFixer(onResult = viewModel::refreshPermissions)
    // Held here, above the swap to the touch panel, so the operator comes back to the touch row
    // rather than to the top of the list.
    val scroll = rememberScrollState()

    val touch = state.touch
    if (touch is TouchTestPhase.InProgress) {
        BackHandler(onBack = viewModel::closeTouchTest)
        TouchTestPanel(
            tapped = touch.tapped,
            onTap = viewModel::onZoneTapped,
            onReportProblem = viewModel::reportTouchProblem,
            modifier = modifier,
        )
    } else {
        SelfCheckScreen(
            state = state,
            actions = SelfCheckActions(
                onRerun = viewModel::rerun,
                onFix = fix,
                onStartTouchTest = viewModel::startTouchTest,
                onBack = onBack,
            ),
            modifier = modifier,
            scroll = scroll,
        )
    }
}

/** The screen's callbacks, bundled so the screen stays under detekt's parameter limit. */
@Immutable
data class SelfCheckActions(
    val onRerun: () -> Unit,
    val onFix: (SelfCheckItem) -> Unit,
    val onStartTouchTest: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun SelfCheckScreen(
    state: SelfCheckUiState,
    actions: SelfCheckActions,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.selfcheck_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.selfcheck_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )

        CheckSection(R.string.selfcheck_section_network, NETWORK_ROWS, state.network, actions.onFix)
        CheckSection(R.string.selfcheck_section_server, SERVER_ROWS, state.network, actions.onFix)
        CheckSection(R.string.selfcheck_section_permissions, PERMISSION_CHECKS, state.permissions, actions.onFix)
        SectionHeading(R.string.selfcheck_section_touch)
        TouchRow(state.touch, actions.onStartTouchTest)

        BottomActions(isRunning = state.isRunning, onRerun = actions.onRerun, onBack = actions.onBack)
    }
}

@Composable
private fun CheckSection(
    @StringRes titleRes: Int,
    items: List<SelfCheckItem>,
    statuses: Map<SelfCheckItem, CheckStatus>,
    onFix: (SelfCheckItem) -> Unit,
) {
    SectionHeading(titleRes)
    items.forEach { item ->
        val status = statuses[item] ?: CheckStatus.Pending
        StatusRow(labelRes = item.labelRes(), statusText = statusText(status)) { StatusIcon(status) }
        val fixRes = fixLabelRes(item, status)
        if (fixRes != null) FixButton(fixRes) { onFix(item) }
    }
}

@Composable
private fun BottomActions(isRunning: Boolean, onRerun: () -> Unit, onBack: () -> Unit) {
    val spacing = LocalSpacing.current
    Button(
        onClick = onRerun,
        enabled = !isRunning,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xl)
            .heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.selfcheck_run_again)) }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm)
            .heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.selfcheck_back_to_signin)) }
}

/**
 * Runtime permissions are asked for in place. When the platform will no longer show the dialog
 * (denied for good), the answer comes back as an immediate "no" with no rationale to show, and the
 * only way left is the app's Settings page — so that is where the operator is sent.
 */
@Composable
private fun rememberPermissionFixer(onResult: () -> Unit): (SelfCheckItem) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var requested by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && activity.deniedForGood(requested)) context.openAppSettings()
        onResult()
    }
    return { item ->
        val permission = runtimePermissionFor(item)
        if (permission != null) {
            requested = permission
            launcher.launch(permission)
        } else {
            context.openSettingsFor(item)
        }
    }
}

/** A refused permission with no rationale to show is one the platform will not ask about again. */
private fun Activity?.deniedForGood(permission: String?): Boolean =
    this != null && permission != null && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

private fun runtimePermissionFor(item: SelfCheckItem): String? = when {
    item == SelfCheckItem.PERM_CAMERA -> Manifest.permission.CAMERA
    item == SelfCheckItem.PERM_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        Manifest.permission.POST_NOTIFICATIONS
    else -> null
}

private fun Context.openSettingsFor(item: SelfCheckItem) {
    when (item) {
        SelfCheckItem.PERM_INSTALL_APPS -> openInstallSourceSettings()
        SelfCheckItem.NFC -> openNfcSettings()
        else -> Unit
    }
}
