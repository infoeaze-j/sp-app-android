package com.mediplus.faceverify.ui.memberscan

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.R
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.ui.components.ErrorState
import com.mediplus.faceverify.core.ui.components.LoadingState
import com.mediplus.faceverify.core.ui.theme.LocalSpacing
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability

/**
 * US2 member card destination. Handles NFC-unavailable/disabled messaging, the live card read (via
 * NFC reader mode), manual number entry when the card is unreadable, and the member-confirmation
 * step. On confirmation it advances.
 */
@Composable
fun MemberScanRoute(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemberScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    androidx.compose.runtime.LaunchedEffect(state.phase) {
        if (state.phase is MemberScanPhase.Verified) onVerified()
    }

    // Listen for a tap only while we're actually waiting for one. Keyed on a boolean (not the phase)
    // so the in-flight read isn't cancelled the moment the phase advances to Reading.
    val waitingForTap = state.phase == MemberScanPhase.ReadyToScan
    androidx.compose.runtime.LaunchedEffect(waitingForTap, activity) {
        if (waitingForTap && activity != null) viewModel.startScan(NfcHost(activity))
    }

    // The scan outlives recomposition, so it is stopped explicitly when the screen goes away.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    MemberScanScreen(
        state = state,
        onManualEntry = viewModel::showManualEntry,
        onSubmitNumber = viewModel::submitManualNumber,
        onConfirm = viewModel::onConfirm,
        onRetry = viewModel::retry,
        onOpenSettings = {
            activity?.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        },
        modifier = modifier,
    )
}

@Composable
fun MemberScanScreen(
    state: MemberScanUiState,
    onManualEntry: () -> Unit,
    onSubmitNumber: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        MemberScanPhase.CheckingAvailability -> LoadingState(modifier = modifier)
        MemberScanPhase.Reading -> LoadingState(messageRes = R.string.card_reading, modifier = modifier)
        MemberScanPhase.Verifying -> LoadingState(messageRes = R.string.card_verifying, modifier = modifier)
        MemberScanPhase.Verified -> LoadingState(modifier = modifier)
        is MemberScanPhase.Unavailable ->
            UnavailableContent(phase.availability, onOpenSettings, onManualEntry, modifier)
        MemberScanPhase.ManualEntry -> ManualEntryContent(onSubmitNumber, modifier)
        MemberScanPhase.ReadyToScan -> ReadyToScanContent(onManualEntry, modifier)
        is MemberScanPhase.Confirm -> ConfirmContent(phase.member, onConfirm, modifier)
        is MemberScanPhase.Failed -> ErrorState(message = phase.message, onAction = onRetry, modifier = modifier)
    }
}

@Composable
private fun UnavailableContent(
    availability: NfcAvailability,
    onOpenSettings: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val messageRes = if (availability == NfcAvailability.DISABLED) {
            R.string.card_unavailable_disabled
        } else {
            R.string.card_unavailable_none
        }
        Text(stringResource(R.string.card_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
        if (availability == NfcAvailability.DISABLED) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.action_open_settings)) }
        }
        // Manual entry means no-NFC is never a dead end.
        OutlinedButton(
            onClick = onManualEntry,
            modifier = Modifier.padding(top = spacing.md).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.action_enter_manually)) }
    }
}

@Composable
private fun ManualEntryContent(
    onSubmitNumber: (String) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    var number by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.card_manual_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.card_manual_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        OutlinedTextField(
            value = number,
            // Filtering here is ergonomics, not validation — MemberNumber.parse is the rule.
            onValueChange = { number = it.filter(Char::isDigit).take(MemberNumber.MAX_LENGTH) },
            label = { Text(stringResource(R.string.card_number_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmitNumber(number) },
            enabled = number.length >= MemberNumber.MIN_LENGTH,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.card_submit_button)) }
    }
}

@Composable
private fun ReadyToScanContent(
    onManualEntry: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.card_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.card_prompt_tap),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.md),
        )
        OutlinedButton(
            onClick = onManualEntry,
            modifier = Modifier.padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.action_enter_manually)) }
    }
}

@Composable
private fun ConfirmContent(
    member: MemberDetails,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
    ) {
        Text(stringResource(R.string.card_confirm_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.card_confirm_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        Field(R.string.card_field_name, member.fullName)
        Field(R.string.card_field_number, member.memberNumber)
        Field(R.string.card_field_dob, member.dateOfBirth)
        Field(R.string.card_field_status, member.membershipStatus)
        member.plan?.let { Field(R.string.card_field_plan, it) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.card_confirm_button)) }
    }
}

@Composable
private fun Field(labelRes: Int, value: String) {
    val spacing = LocalSpacing.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(FIELD_LABEL_WEIGHT),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val FIELD_LABEL_WEIGHT = 0.4f
