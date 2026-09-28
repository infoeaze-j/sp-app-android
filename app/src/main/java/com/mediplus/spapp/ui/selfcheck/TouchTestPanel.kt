package com.mediplus.spapp.ui.selfcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.theme.LocalSpacing
import com.mediplus.spapp.core.ui.theme.LocalStatusColors
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.TouchTestStatus
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Width of the password field relative to its show/hide toggle, as on the sign-in screen. */
private const val PASSWORD_FIELD_WEIGHT = 3f

/** Space left of the log out zone, pushing it into the top-end corner where the app bar puts it. */
private const val LOG_OUT_OFFSET_WEIGHT = 2f

private val KEYPAD_ROWS = listOf(
    listOf(TouchZone.KEY_1, TouchZone.KEY_2, TouchZone.KEY_3),
    listOf(TouchZone.KEY_4, TouchZone.KEY_5, TouchZone.KEY_6),
    listOf(TouchZone.KEY_7, TouchZone.KEY_8, TouchZone.KEY_9),
    listOf(TouchZone.KEY_DECIMAL, TouchZone.KEY_0, TouchZone.KEY_BACKSPACE),
)

/** The touch-screen row on the self-check screen: the last result and a way to (re)start it. */
@Composable
internal fun TouchRow(touch: TouchTestPhase, onStart: () -> Unit) {
    val status = (touch as? TouchTestPhase.Idle)?.status
    StatusRow(labelRes = R.string.selfcheck_section_touch, statusText = touchStatusText(status)) {
        if (status == null) StatusIcon(CheckStatus.Running) else TouchStatusGlyph(status)
    }
    val startRes = when (touch) {
        TouchTestPhase.Idle(TouchTestStatus.NeverRun), TouchTestPhase.Loading -> R.string.selfcheck_touch_start
        else -> R.string.selfcheck_touch_again
    }
    FixButton(labelRes = startRes, onClick = onStart)
}

@Composable
private fun TouchStatusGlyph(status: TouchTestStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        TouchTestStatus.NeverRun -> StatusGlyph(Icons.Outlined.RadioButtonUnchecked, colors.onSurfaceVariant)
        is TouchTestStatus.Passed -> StatusGlyph(Icons.Filled.CheckCircle, LocalStatusColors.current.success)
        is TouchTestStatus.Due -> StatusGlyph(Icons.Outlined.Schedule, colors.onSurfaceVariant)
        is TouchTestStatus.Failed -> StatusGlyph(Icons.Filled.Error, colors.error)
    }
}

/** Null while the last result is being read or written. */
@Composable
private fun touchStatusText(status: TouchTestStatus?): String = when (status) {
    null -> stringResource(R.string.selfcheck_status_running)
    TouchTestStatus.NeverRun -> stringResource(R.string.selfcheck_touch_not_run)
    is TouchTestStatus.Passed -> stringResource(R.string.selfcheck_touch_passed_at, testedAt(status.at))
    is TouchTestStatus.Due -> stringResource(R.string.selfcheck_touch_due, testedAt(status.lastPassedAt))
    is TouchTestStatus.Failed -> stringResource(R.string.selfcheck_touch_failed_at, testedAt(status.at))
}

/** The device's own locale and time zone: the operator reads this against the clock on the wall. */
@Composable
private fun testedAt(at: Instant): String {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
    return formatter.format(at)
}

/**
 * The touch-screen test: large tap targets placed where the app asks an operator to tap — the log
 * out corner, the sign-in fields and password toggle, the amount keypad, and the bottom primary
 * button. Each fills in with a tick once it registers; the test passes when every one has.
 * The operator can end it early by reporting a dead area, and system back abandons it.
 */
@Composable
fun TouchTestPanel(
    tapped: Set<TouchZone>,
    onTap: (TouchZone) -> Unit,
    onReportProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val zone: @Composable (TouchZone, Modifier) -> Unit = { z, m -> ZoneBox(z, z in tapped, onTap, m) }
    val rowArrangement = Arrangement.spacedBy(spacing.sm)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = rowArrangement) {
            Spacer(Modifier.weight(LOG_OUT_OFFSET_WEIGHT))
            zone(TouchZone.LOG_OUT, Modifier.weight(1f).fillMaxHeight())
        }
        zone(TouchZone.IDENTIFIER, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.weight(1f), horizontalArrangement = rowArrangement) {
            zone(TouchZone.PASSWORD, Modifier.weight(PASSWORD_FIELD_WEIGHT).fillMaxHeight())
            zone(TouchZone.SHOW_PASSWORD, Modifier.weight(1f).fillMaxHeight())
        }
        TouchInstructions(onReportProblem)
        KEYPAD_ROWS.forEach { keys ->
            Row(Modifier.weight(1f), horizontalArrangement = rowArrangement) {
                keys.forEach { key -> zone(key, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        zone(TouchZone.PRIMARY_BUTTON, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Sits in the gap between the fields and the keypad, where the app itself never asks for a tap,
 * so the way out does not overlap a zone under test.
 */
@Composable
private fun TouchInstructions(onReportProblem: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.selfcheck_touch_instructions),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onReportProblem, modifier = Modifier.heightIn(min = spacing.minTouchTarget)) {
            Text(stringResource(R.string.selfcheck_touch_report_problem))
        }
    }
}

@Composable
private fun ZoneBox(zone: TouchZone, tapped: Boolean, onTap: (TouchZone) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    Surface(
        onClick = { onTap(zone) },
        shape = MaterialTheme.shapes.medium,
        // Success green, matching the passed checkmarks: the brand primary is orange, too close
        // to the error red, and primaryContainer is a near-identical pink to surfaceVariant.
        color = if (tapped) status.success else colors.surfaceVariant,
        contentColor = if (tapped) status.onSuccess else colors.onSurfaceVariant,
        modifier = modifier.semantics { selected = tapped },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tapped) Icon(Icons.Filled.Check, contentDescription = null)
                ZoneLabel(zone)
            }
        }
    }
}

@Composable
private fun ZoneLabel(zone: TouchZone) {
    val decimal = remember { DecimalFormatSymbols.getInstance().decimalSeparator.toString() }
    val style = MaterialTheme.typography.titleMedium
    when (zone) {
        TouchZone.KEY_BACKSPACE -> Icon(
            Icons.AutoMirrored.Filled.Backspace,
            contentDescription = stringResource(R.string.selfcheck_zone_backspace),
        )
        TouchZone.KEY_DECIMAL -> Text(decimal, style = style)
        else -> Text(zone.label(), style = style)
    }
}

@Composable
private fun TouchZone.label(): String = when (this) {
    TouchZone.LOG_OUT -> stringResource(R.string.selfcheck_zone_log_out)
    TouchZone.IDENTIFIER -> stringResource(R.string.selfcheck_zone_identifier)
    TouchZone.PASSWORD -> stringResource(R.string.selfcheck_zone_password)
    TouchZone.SHOW_PASSWORD -> stringResource(R.string.selfcheck_zone_show_password)
    TouchZone.PRIMARY_BUTTON -> stringResource(R.string.selfcheck_zone_primary)
    // The keypad's digits: the same in every language the app ships.
    else -> name.removePrefix("KEY_")
}
