package com.mediplus.faceverify.ui.addservice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.ui.components.ErrorState
import com.mediplus.faceverify.core.ui.components.LoadingState
import com.mediplus.faceverify.core.ui.theme.LocalSpacing
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Money
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
    val actions = AddServiceActions(
        onSelect = viewModel::selectService,
        onAmountChange = viewModel::amountChanged,
        onCurrencyChange = viewModel::currencySelected,
        onCancelAmount = viewModel::cancelAmount,
        onConfirmAmount = viewModel::confirmAmount,
        onRetry = viewModel::retry,
        onRecheck = viewModel::recheck,
        onDone = onDone,
    )
    AddServiceScreen(state = state, actions = actions, modifier = modifier)
}

@Composable
fun AddServiceScreen(
    state: AddServiceUiState,
    actions: AddServiceActions,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        AddServicePhase.LoadingServices, AddServicePhase.Submitting -> LoadingState(modifier = modifier)
        is AddServicePhase.Ready -> ServiceList(phase.services, actions.onSelect, modifier)
        is AddServicePhase.EnteringAmount -> {
            ServiceList(phase.services, actions.onSelect, modifier)
            AmountDialog(
                phase = phase,
                onAmountChange = actions.onAmountChange,
                onCurrencyChange = actions.onCurrencyChange,
                onCancel = actions.onCancelAmount,
                onConfirm = actions.onConfirmAmount,
            )
        }
        is AddServicePhase.Blocked -> BlockedContent(phase.outstanding, modifier)
        is AddServicePhase.Unavailable -> UnavailableContent(phase.reason, modifier)
        is AddServicePhase.Confirmed -> ConfirmedContent(actions.onDone, modifier)
        is AddServicePhase.Failed -> ErrorState(
            message = phase.message,
            onAction = if (phase.canRetry) actions.onRetry else null,
            modifier = modifier,
        )
        is AddServicePhase.Uncertain -> UncertainContent(phase.message, actions.onRecheck, modifier)
    }
}

@Composable
private fun ServiceList(services: List<Service>, onSelect: (String) -> Unit, modifier: Modifier) {
    val spacing = LocalSpacing.current
    if (services.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.addservice_no_services),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
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
            ) { Text(stringResource(R.string.addservice_enter_amount)) }
        }
    }
}

/**
 * Amount + currency entry over the service list. Confirm is disabled until the text parses, so an
 * invalid amount can never be submitted rather than being rejected afterwards.
 */
@Composable
private fun AmountDialog(
    phase: AddServicePhase.EnteringAmount,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(phase.selected.description) },
        text = {
            Column {
                CurrencySelector(phase, onCurrencyChange)
                OutlinedTextField(
                    value = phase.amountText,
                    onValueChange = onAmountChange,
                    label = { Text(stringResource(R.string.addservice_amount_label)) },
                    singleLine = true,
                    isError = phase.amountText.isNotEmpty() && Money.parse(phase.amountText) == null,
                    supportingText = {
                        if (phase.amountText.isNotEmpty() && Money.parse(phase.amountText) == null) {
                            Text(stringResource(R.string.addservice_amount_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = Money.parse(phase.amountText) != null) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The currency portion of [AmountDialog], extracted to keep that composable under the LongMethod
 * threshold (Finding 2). A one-option picker is a decision the operator cannot make; showing it as
 * a control would invite a tap that does nothing, so a single currency renders as a labelled
 * static value instead of the dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencySelector(phase: AddServicePhase.EnteringAmount, onCurrencyChange: (Currency) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (phase.currencies.size == 1) {
        // Still labelled like the dropdown branch so TalkBack announces what the value below it is.
        Text(
            text = stringResource(R.string.addservice_currency_label),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = phase.selectedCurrency.label,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = phase.selectedCurrency.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.addservice_currency_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                phase.currencies.forEach { currency ->
                    DropdownMenuItem(
                        text = { Text(currency.label) },
                        onClick = {
                            onCurrencyChange(currency)
                            expanded = false
                        },
                    )
                }
            }
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
