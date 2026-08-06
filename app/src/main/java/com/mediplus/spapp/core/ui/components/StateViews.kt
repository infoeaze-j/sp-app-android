package com.mediplus.spapp.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.mediplus.spapp.R
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.ui.theme.LocalSpacing

/** Full-screen busy indicator with an accessible status message. */
@Composable
fun LoadingState(
    messageRes: Int = R.string.state_loading,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Consistent error presentation driven by a curated [UiMessage] (resource-only, non-revealing).
 * The optional recovery action is shown only when the message carries an [UiMessage.actionRes].
 */
@Composable
fun ErrorState(
    message: UiMessage,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(message.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(message.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        val action = message.actionRes
        if (action != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = spacing.lg)
                    .heightIn(min = spacing.minTouchTarget),
            ) {
                Text(stringResource(action))
            }
        }
    }
}

/**
 * Shown when a required runtime permission (camera) is denied. Offers to request again or open
 * settings; the app never proceeds without the permission (FR-016).
 */
@Composable
fun PermissionDeniedState(
    rationaleRes: Int,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.state_permission_needed_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(rationaleRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Button(
            onClick = onRequest,
            modifier = Modifier
                .padding(top = spacing.lg)
                .fillMaxWidth()
                .widthIn(max = spacing.maxContentWidth)
                .heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(R.string.action_grant_permission))
        }
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .padding(top = spacing.sm)
                .fillMaxWidth()
                .widthIn(max = spacing.maxContentWidth)
                .heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}
