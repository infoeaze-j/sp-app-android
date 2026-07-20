package com.mediplus.faceverify.core.result

import com.mediplus.faceverify.R
import javax.inject.Inject

/**
 * Maps a structured [AppError] to a clear, NON-revealing [UiMessage] (FR-021, FR-029). The single
 * shared mapper keeps error wording consistent across all four flows (Principle III) and guarantees
 * that server reasons and identity/biometric data never reach the UI: only curated string resources
 * are ever returned.
 */
interface ErrorMapper {
    fun toUserMessage(error: AppError): UiMessage
}

class DefaultErrorMapper @Inject constructor() : ErrorMapper {

    override fun toUserMessage(error: AppError): UiMessage = when (error) {
        is AppError.Business -> businessMessage(error.code)
        is AppError.Transient -> transientMessage(error.kind)
        AppError.Timeout -> UiMessage(
            titleRes = R.string.err_timeout_title,
            bodyRes = R.string.err_timeout_body,
            actionRes = R.string.action_recheck,
        )
        is AppError.SessionInvalid -> UiMessage(
            titleRes = R.string.err_session_ended_title,
            bodyRes = R.string.err_session_ended_body,
            actionRes = R.string.action_sign_in_again,
        )
    }

    private fun businessMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.INVALID_CREDENTIALS -> UiMessage(
            R.string.err_invalid_credentials_title,
            R.string.err_invalid_credentials_body,
            R.string.action_try_again,
        )
        BusinessCode.ACCOUNT_LOCKED -> UiMessage(
            R.string.err_account_locked_title,
            R.string.err_account_locked_body,
        )
        BusinessCode.DOCUMENT_INVALID -> UiMessage(
            R.string.err_document_invalid_title,
            R.string.err_document_invalid_body,
            R.string.action_rescan,
        )
        BusinessCode.DOCUMENT_EXPIRED -> UiMessage(
            R.string.err_document_expired_title,
            R.string.err_document_expired_body,
            R.string.action_rescan,
        )
        BusinessCode.PATIENT_NOT_FOUND -> UiMessage(
            R.string.err_patient_not_found_title,
            R.string.err_patient_not_found_body,
        )
        BusinessCode.FACE_NO_MATCH -> UiMessage(
            R.string.err_face_no_match_title,
            R.string.err_face_no_match_body,
            R.string.action_try_again,
        )
        BusinessCode.FACE_SPOOF -> UiMessage(
            R.string.err_face_spoof_title,
            R.string.err_face_spoof_body,
            R.string.action_try_again,
        )
        BusinessCode.FACE_LOCKED_OUT -> UiMessage(
            R.string.err_face_locked_out_title,
            R.string.err_face_locked_out_body,
        )
        BusinessCode.SUBJECT_MISMATCH -> UiMessage(
            R.string.err_subject_mismatch_title,
            R.string.err_subject_mismatch_body,
        )
        BusinessCode.CONSENT_WITHHELD -> UiMessage(
            R.string.err_consent_withheld_title,
            R.string.err_consent_withheld_body,
        )
        BusinessCode.DUPLICATE_SERVICE -> UiMessage(
            R.string.err_duplicate_service_title,
            R.string.err_duplicate_service_body,
        )
        BusinessCode.SERVICE_INELIGIBLE -> UiMessage(
            R.string.err_service_ineligible_title,
            R.string.err_service_ineligible_body,
        )
        BusinessCode.NOT_CURRENTLY_VERIFIED -> UiMessage(
            R.string.err_not_verified_title,
            R.string.err_not_verified_body,
            R.string.action_start_over,
        )
        BusinessCode.GENERIC -> genericMessage()
    }

    private fun transientMessage(kind: TransientKind): UiMessage = when (kind) {
        TransientKind.NO_CONNECTIVITY -> UiMessage(
            R.string.err_no_connectivity_title,
            R.string.err_no_connectivity_body,
            R.string.action_retry,
        )
        TransientKind.SERVER_ERROR -> UiMessage(
            R.string.err_server_title,
            R.string.err_server_body,
            R.string.action_retry,
        )
        TransientKind.UNKNOWN -> genericMessage()
    }

    private fun genericMessage() = UiMessage(
        titleRes = R.string.err_generic_title,
        bodyRes = R.string.err_generic_body,
        actionRes = R.string.action_retry,
    )
}
