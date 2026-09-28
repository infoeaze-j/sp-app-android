package com.mediplus.spapp.ui.selfcheck

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.theme.LocalSpacing
import com.mediplus.spapp.core.ui.theme.LocalStatusColors
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.SelfCheckItem

/* The building blocks of the self-check screen's rows. */

@Composable
internal fun SectionHeading(@StringRes titleRes: Int) {
    val spacing = LocalSpacing.current
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = spacing.lg, bottom = spacing.xs)
            .semantics { heading() },
    )
}

/** One labelled row with a leading status icon, read out as a single node by screen readers. */
@Composable
internal fun StatusRow(@StringRes labelRes: Int, statusText: String, icon: @Composable () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minTouchTarget)
            .padding(vertical = spacing.xs)
            .semantics(mergeDescendants = true) {},
    ) {
        icon()
        Column(modifier = Modifier.padding(start = spacing.md)) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FixButton(@StringRes labelRes: Int, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.sm)
            .heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(labelRes)) }
}

@Composable
internal fun StatusIcon(status: CheckStatus) {
    val spacing = LocalSpacing.current
    if (status == CheckStatus.Running) {
        CircularProgressIndicator(modifier = Modifier.size(spacing.lg), strokeWidth = spacing.xs / 2)
        return
    }
    val (icon, tint) = when (status) {
        is CheckStatus.Passed -> Icons.Filled.CheckCircle to LocalStatusColors.current.success
        is CheckStatus.Failed -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        is CheckStatus.Warning -> Icons.Filled.Warning to LocalStatusColors.current.warning
        CheckStatus.Pending -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusGlyph(icon, tint)
}

/** Decorative: the row's text already says what the icon shows. */
@Composable
internal fun StatusGlyph(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(LocalSpacing.current.lg),
    )
}

@Composable
internal fun statusText(status: CheckStatus): String = when (status) {
    CheckStatus.Pending -> stringResource(R.string.selfcheck_status_pending)
    CheckStatus.Running -> stringResource(R.string.selfcheck_status_running)
    is CheckStatus.Passed -> status.via
        ?.let { stringResource(R.string.selfcheck_status_connected_via, stringResource(it.labelRes())) }
        ?: withHttpStatus(stringResource(R.string.selfcheck_status_passed), status.httpStatus)
    is CheckStatus.Failed -> withHttpStatus(stringResource(status.reason.labelRes()), status.httpStatus)
    is CheckStatus.Warning -> stringResource(status.reason.labelRes())
    CheckStatus.Skipped -> stringResource(R.string.selfcheck_status_skipped)
    CheckStatus.NotYetAvailable -> stringResource(R.string.selfcheck_status_not_yet_available)
    CheckStatus.NotApplicable -> stringResource(R.string.selfcheck_status_not_applicable)
}

/** The HTTP status is a number, never a server reason, so it is safe to put in front of anyone. */
@Composable
private fun withHttpStatus(text: String, httpStatus: Int?): String =
    if (httpStatus == null) text else stringResource(R.string.selfcheck_status_with_http, text, httpStatus)

/** The button a failing row offers, or null when there is nothing the operator can do from here. */
@StringRes
internal fun fixLabelRes(item: SelfCheckItem, status: CheckStatus): Int? {
    if (status !is CheckStatus.Failed) return null
    return when (item) {
        SelfCheckItem.PERM_CAMERA, SelfCheckItem.PERM_NOTIFICATIONS -> R.string.action_grant_permission
        SelfCheckItem.PERM_INSTALL_APPS -> R.string.action_open_settings
        SelfCheckItem.NFC -> R.string.action_open_settings.takeIf { status.reason == CheckFailure.DISABLED }
        else -> null
    }
}
