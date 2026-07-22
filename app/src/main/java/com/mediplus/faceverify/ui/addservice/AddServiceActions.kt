package com.mediplus.faceverify.ui.addservice

import com.mediplus.faceverify.domain.model.Currency

/**
 * Everything the add-service screen can ask the ViewModel to do. Kept in its own file (rather than
 * inline in AddServiceScreen.kt) so this single top-level declaration doesn't collide with
 * MatchingDeclarationName.
 */
data class AddServiceActions(
    val onSelect: (String) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onCurrencyChange: (Currency) -> Unit,
    val onCancelAmount: () -> Unit,
    val onConfirmAmount: () -> Unit,
    val onRetry: () -> Unit,
    val onRecheck: () -> Unit,
    val onDone: () -> Unit,
)
