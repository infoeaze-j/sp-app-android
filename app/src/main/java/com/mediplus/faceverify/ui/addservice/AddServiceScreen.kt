package com.mediplus.faceverify.ui.addservice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.ui.components.ErrorState
import com.mediplus.faceverify.core.ui.components.LoadingState
import com.mediplus.faceverify.core.ui.theme.LocalSpacing
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.usecase.Outstanding

/**
 * US4 add-service destination. Lists eligible services, blocks when the identity isn't currently
 * verified (naming the outstanding requirement), prevents duplicates, confirms success only on
 * back-office confirmation, and never shows a timeout as success (safe re-check).
 */
@Composable
fun AddServiceRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddServiceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AddServiceScreen(
        state = state,
        onSelect = viewModel::selectService,
        onRetry = viewModel::retry,
        onRecheck = viewModel::recheck,
        onDone = onDone,
        modifier = modifier,
    )
}

@Composable
fun AddServiceScreen(
    state: AddServiceUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onRecheck: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        AddServicePhase.LoadingServices, AddServicePhase.Submitting -> LoadingState(modifier = modifier)
        is AddServicePhase.Ready -> ServiceList(phase.services, onSelect, modifier)
        is AddServicePhase.EnteringAmount -> ServiceList(phase.services, onSelect, modifier)
        is AddServicePhase.Blocked -> BlockedContent(phase.outstanding, modifier)
        is AddServicePhase.Unavailable -> UnavailableContent(phase.reason, modifier)
        is AddServicePhase.Confirmed -> ConfirmedContent(onDone, modifier)
        is AddServicePhase.Failed -> ErrorState(
            message = phase.message,
            onAction = if (phase.canRetry) onRetry else null,
            modifier = modifier,
        )
        is AddServicePhase.Uncertain -> UncertainContent(phase.message, onRecheck, modifier)
    }
}

@Composable
private fun ServiceList(services: List<Service>, onSelect: (String) -> Unit, modifier: Modifier) {
    val spacing = LocalSpacing.current
    if (services.isEmpty()) {
        CenteredMessage(R.string.addservice_no_services, modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize().padding(spacing.md)) {
        Text(
            text = stringResource(R.string.addservice_choose),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = spacing.md),
        )
        LazyColumn {
            items(services, key = { it.serviceId }) { service ->
                ServiceRow(service, onSelect)
            }
        }
    }
}

@Composable
private fun ServiceRow(service: Service, onSelect: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val enabled = service.eligibleForPatient && !service.alreadySelected
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(service.description, style = MaterialTheme.typography.titleMedium)
            val tag = when {
                service.alreadySelected -> stringResource(R.string.addservice_already_added)
                !service.eligibleForPatient -> stringResource(R.string.addservice_ineligible_tag)
                else -> null
            }
            if (tag != null) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
            Button(
                onClick = { onSelect(service.serviceId) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm).heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.action_confirm)) }
        }
    }
}

@Composable
private fun BlockedContent(outstanding: Outstanding, modifier: Modifier) {
    val bodyRes = when (outstanding) {
        Outstanding.DOCUMENT -> R.string.addservice_blocked_document
        Outstanding.FACE -> R.string.addservice_blocked_face
        Outstanding.STALE, Outstanding.NONE -> R.string.addservice_blocked_stale
    }
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.addservice_blocked_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

@Composable
private fun UnavailableContent(reason: UnavailableReason, modifier: Modifier) {
    val bodyRes = when (reason) {
        UnavailableReason.NO_CURRENCY -> R.string.addservice_unavailable_no_currency
    }
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.addservice_unavailable_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}

@Composable
private fun ConfirmedContent(onDone: () -> Unit, modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.addservice_confirmed_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.addservice_confirmed_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Button(
            onClick = onDone,
            modifier = Modifier.padding(top = spacing.lg).fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.addservice_done)) }
    }
}

@Composable
private fun UncertainContent(message: UiMessage, onRecheck: () -> Unit, modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.addservice_uncertain_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.addservice_uncertain_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Button(
            onClick = onRecheck,
            modifier = Modifier.padding(top = spacing.lg).fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.action_recheck)) }
    }
}

@Composable
private fun CenteredMessage(messageRes: Int, modifier: Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
