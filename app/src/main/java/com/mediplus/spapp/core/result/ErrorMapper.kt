package com.mediplus.spapp.core.result

import com.mediplus.spapp.R
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
    }

    // The dispatcher stays exhaustive over BusinessCode, so a new code cannot compile without a
    // mapping; the per-area functions below only ever see their own subset.
    private fun businessMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.INVALID_CREDENTIALS,
        BusinessCode.ACCOUNT_LOCKED,
        -> signInMessage(code)

        BusinessCode.MEMBER_INVALID,
        BusinessCode.CARD_UNREADABLE,
        BusinessCode.PATIENT_NOT_FOUND,
        BusinessCode.FACE_NO_MATCH,
        BusinessCode.FACE_SPOOF,
        BusinessCode.FACE_LOCKED_OUT,
        BusinessCode.SUBJECT_MISMATCH,
        BusinessCode.CONSENT_WITHHELD,
        BusinessCode.NOT_CURRENTLY_VERIFIED,
        -> verificationMessage(code)

        BusinessCode.DUPLICATE_SERVICE,
        BusinessCode.SERVICE_INELIGIBLE,
        -> enrollmentMessage(code)

        BusinessCode.UPDATE_CORRUPTED,
        BusinessCode.UPDATE_BACKUP_FAILED,
        BusinessCode.UPDATE_INSTALL_ABORTED,
        BusinessCode.UPDATE_INSTALL_FAILED,
        -> updateMessage(code)

        BusinessCode.GENERIC -> genericMessage()
    }

    private fun signInMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.INVALID_CREDENTIALS -> UiMessage(
            R.string.err_invalid_credentials_title,
            R.string.err_invalid_credentials_body,
            R.string.action_try_again,
        )
        BusinessCode.ACCOUNT_LOCKED -> UiMessage(
            R.string.err_account_locked_title,
            R.string.err_account_locked_body,
        )
        else -> genericMessage()
    }

    private fun verificationMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.MEMBER_INVALID -> UiMessage(
            R.string.err_member_invalid_title,
            R.string.err_member_invalid_body,
            R.string.action_rescan,
        )
        BusinessCode.CARD_UNREADABLE -> UiMessage(
            R.string.err_card_unreadable_title,
            R.string.err_card_unreadable_body,
            R.string.action_enter_manually,
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
        BusinessCode.NOT_CURRENTLY_VERIFIED -> UiMessage(
            R.string.err_not_verified_title,
            R.string.err_not_verified_body,
            R.string.action_start_over,
        )
        else -> genericMessage()
    }

    private fun enrollmentMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.DUPLICATE_SERVICE -> UiMessage(
            R.string.err_duplicate_service_title,
            R.string.err_duplicate_service_body,
        )
        BusinessCode.SERVICE_INELIGIBLE -> UiMessage(
            R.string.err_service_ineligible_title,
            R.string.err_service_ineligible_body,
        )
        else -> genericMessage()
    }

    private fun updateMessage(code: BusinessCode): UiMessage = when (code) {
        BusinessCode.UPDATE_CORRUPTED -> UiMessage(
            R.string.err_update_corrupted_title,
            R.string.err_update_corrupted_body,
            R.string.action_retry,
        )
        // Retained as a diagnostic code only: since the 2026-08-03 unattended-update design the
        // backup is best effort and no longer blocks an install, so nothing routes this to the
        // operator. The mapping stays so that if a future path ever does surface it, it surfaces as
        // itself rather than falling through to the generic message.
        BusinessCode.UPDATE_BACKUP_FAILED -> UiMessage(
            R.string.err_update_backup_failed_title,
            R.string.err_update_backup_failed_body,
            R.string.action_retry,
        )
        BusinessCode.UPDATE_INSTALL_ABORTED -> UiMessage(
            R.string.err_update_install_aborted_title,
            R.string.err_update_install_aborted_body,
            R.string.action_retry,
        )
        BusinessCode.UPDATE_INSTALL_FAILED -> UiMessage(
            R.string.err_update_install_failed_title,
            R.string.err_update_install_failed_body,
            R.string.action_retry,
        )
        else -> genericMessage()
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
        TransientKind.DOWNLOAD_INTERRUPTED -> UiMessage(
            R.string.err_download_interrupted_title,
            R.string.err_download_interrupted_body,
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
