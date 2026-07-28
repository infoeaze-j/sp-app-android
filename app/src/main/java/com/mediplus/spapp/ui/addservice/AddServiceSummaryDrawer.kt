package com.mediplus.spapp.ui.addservice

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.components.ActionDrawer
import com.mediplus.spapp.core.ui.components.DrawerAction
import com.mediplus.spapp.core.ui.theme.LocalSpacing
import com.mediplus.spapp.domain.model.MemberDetails

/**
 * The last thing between a filled-in transaction and the back office: who it is for, what is being
 * added, and what it costs, all in one drawer so the operator can catch a wrong patient or a
 * mistyped amount while it is still free to fix (FR-019a).
 *
 * Lives in its own file rather than beside the other add-service composables so neither file grows
 * past detekt's structural thresholds.
 */
@Composable
internal fun AddServiceSummaryDrawer(
    phase: AddServicePhase.ReviewingSummary,
    onEdit: () -> Unit,
    onSubmit: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ActionDrawer(
        title = stringResource(R.string.addservice_summary_title),
        confirm = DrawerAction(
            labelRes = R.string.addservice_summary_submit,
            onClick = onSubmit,
            // Approving a charge for a patient we cannot name is not a decision the operator can
            // meaningfully make, so it is not offered — they go back and re-verify instead.
            enabled = phase.patient != null,
        ),
        // Dismissing lands on amount entry, not on a submission: the only way out of this drawer
        // that sends anything is the explicit confirm.
        dismiss = DrawerAction(labelRes = R.string.action_back, onClick = onEdit),
    ) {
        // ActionDrawer scrolls the body for us, so this list just stacks.
        Text(
            text = stringResource(R.string.addservice_summary_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        phase.providerName?.let { ProviderSection(it) }
        PatientSection(phase.patient)
        SectionHeading(R.string.addservice_summary_service_heading)
        SummaryField(R.string.addservice_summary_service_label, phase.selected.description)
        SectionHeading(R.string.addservice_summary_charge_heading)
        SummaryField(R.string.addservice_currency_label, phase.currency.label)
        SummaryField(R.string.addservice_amount_label, phase.amount.format(phase.currency.minorUnitExponent))
    }
}

@Composable
private fun ProviderSection(name: String) {
    SectionHeading(R.string.addservice_summary_provider_heading)
    Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PatientSection(patient: MemberDetails?) {
    SectionHeading(R.string.addservice_summary_patient_heading)
    if (patient == null) {
        Text(
            text = stringResource(R.string.addservice_summary_patient_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    SummaryField(R.string.card_field_name, patient.fullName)
    SummaryField(R.string.card_field_number, patient.memberNumber)
    patient.dateOfBirth?.let { SummaryField(R.string.card_field_dob, it) }
    patient.plan?.let { SummaryField(R.string.card_field_plan, it) }
}

@Composable
private fun SectionHeading(labelRes: Int) {
    val spacing = LocalSpacing.current
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = spacing.md, bottom = spacing.xs),
    )
}

@Composable
private fun SummaryField(labelRes: Int, value: String) {
    val spacing = LocalSpacing.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(SUMMARY_LABEL_WEIGHT),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val SUMMARY_LABEL_WEIGHT = 0.45f
