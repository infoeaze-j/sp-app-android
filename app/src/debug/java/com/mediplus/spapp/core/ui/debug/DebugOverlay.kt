package com.mediplus.spapp.core.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.mediplus.spapp.core.ui.theme.LocalSpacing

/**
 * Debug-only affordance: a bug button pinned to the corner of every screen that opens a panel of
 * facts about the running build (currently the back-office base URL).
 *
 * It is deliberately outside the journey's chrome and drawn last, so it survives places the top bar
 * does not — sign-in, and the full-screen forced-update overlay — which are exactly the moments a
 * device turns out to be pointed at the wrong back office and nobody can tell.
 *
 * The release source set defines the same function as a no-op, so nothing here reaches a clinic.
 */
@Composable
fun DebugOverlay() {
    val spacing = LocalSpacing.current
    var showingInfo by remember { mutableStateOf(false) }

    // The overlay sits outside the Scaffold that hands every screen its insets, so it pads itself;
    // without this the button hides under the gesture bar on an edge-to-edge window.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        FloatingActionButton(
            onClick = { showingInfo = true },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            // Bottom-start: primary actions in this app live bottom-end or full-width, so the far
            // corner is the least likely to sit on top of something the operator needs to press.
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(spacing.sm)
                .size(spacing.minTouchTarget),
        ) {
            Icon(imageVector = Icons.Filled.BugReport, contentDescription = "Debug info")
        }
    }

    if (showingInfo) {
        DebugInfoSheet(onDismiss = { showingInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugInfoSheet(onDismiss: () -> Unit) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(text = "Debug info", style = MaterialTheme.typography.headlineSmall)
            // Selectable so a URL can be long-pressed and copied off the device rather than
            // transcribed by eye from a phone screen.
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    DebugInfo.entries().forEach { entry -> InfoRow(entry) }
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minTouchTarget),
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun InfoRow(entry: DebugInfoEntry) {
    Column {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
