package com.mediplus.faceverify.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.mediplus.faceverify.core.ui.theme.LocalSpacing

/**
 * One of an [ActionDrawer]'s two actions. [labelRes] is a string resource because no user-facing
 * text in this app is free text.
 */
data class DrawerAction(
    val labelRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * The app's only modal surface: a drawer that slides up over the current screen carrying a title, a
 * body, and exactly two actions.
 *
 * The journey used to interrupt with centred alert dialogs. A drawer keeps the screen behind it
 * visible — the operator can still see the service list or the capture they were on while deciding —
 * and puts both actions within thumb reach at the bottom of a held device rather than floating at
 * the vertical centre. Every interruption goes through here so none of them can drift apart in
 * layout, wording, or touch-target size.
 *
 * Actions are real buttons rather than the dialog convention of bare text, so the one that commits
 * is distinguishable at a glance from the way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDrawer(
    title: String,
    confirm: DrawerAction,
    dismiss: DrawerAction,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    // Always open fully expanded. At partial expansion a long body (the pre-submit summary) pushes
    // the action row below the fold, leaving the operator no visible way to act on the drawer.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Let the drawer slide away before the caller acts, so a button press does not blink the surface
    // out of existence. `invokeOnCompletion` rather than awaiting `hide()`: it fires on cancellation
    // too, so an interrupted animation still delivers the action instead of swallowing it.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        // Swipe-down and scrim taps do exactly what the dismiss button does — the drawer has already
        // animated away by the time this fires, so the action runs directly. The only path out of
        // here that acts on anything is the explicit confirm.
        onDismissRequest = dismiss.onClick,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.lg),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            // `fill = false` so a short body still wraps to its own height rather than stretching the
            // drawer to full screen; a body taller than the space left over scrolls inside it, which
            // keeps the actions below on screen either way.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = spacing.md),
                content = content,
            )
            DrawerActions(confirm = confirm, dismiss = dismiss, onAct = ::closeThen)
        }
    }
}

/** The action row, split out to keep [ActionDrawer] under the LongMethod threshold. */
@Composable
private fun DrawerActions(
    confirm: DrawerAction,
    dismiss: DrawerAction,
    onAct: (() -> Unit) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        OutlinedButton(
            onClick = { onAct(dismiss.onClick) },
            enabled = dismiss.enabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(dismiss.labelRes))
        }
        Button(
            onClick = { onAct(confirm.onClick) },
            enabled = confirm.enabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = spacing.minTouchTarget),
        ) {
            Text(stringResource(confirm.labelRes))
        }
    }
}
