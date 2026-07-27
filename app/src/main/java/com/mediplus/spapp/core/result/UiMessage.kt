package com.mediplus.spapp.core.result

import androidx.annotation.StringRes

/**
 * A user-facing message expressed purely as string-resource references (Principle III: all
 * user text lives in resources; Principle/FR-029: nothing raw from the server or the biometric
 * layer can leak into UI). Because [UiMessage] holds only resource IDs — never free text pulled
 * from a server reason or identity/biometric data — it is non-revealing by construction.
 *
 * @param titleRes short headline for the state
 * @param bodyRes the explanation of what happened
 * @param actionRes optional label for the recovery action (e.g. "Retry", "Sign in again")
 */
data class UiMessage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:StringRes val actionRes: Int? = null,
)
