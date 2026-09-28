package com.mediplus.spapp.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status color tokens Material 3's scheme has no role for (Principle III: shared design tokens).
 * [success] marks a passed check, with [onSuccess] for content drawn on it; [warning] marks a check
 * that works but should be fixed; failures use `colorScheme.error`.
 */
data class StatusColors(val success: Color, val onSuccess: Color, val warning: Color)

internal val LightStatusColors = StatusColors(success = Success, onSuccess = OnSuccess, warning = Warning)
internal val DarkStatusColors = StatusColors(success = SuccessDark, onSuccess = OnSuccessDark, warning = WarningDark)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
