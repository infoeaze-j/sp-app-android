package com.mediplus.faceverify.core.result

/**
 * The single outcome type every back-office interaction returns (FR-027). Modeling success,
 * business rejection, transient failure, and timeout as distinct variants means no code path can
 * silently report success, and denial paths are exhaustively handled at every call site.
 */
sealed interface AppResult<out T> {

    /** A confirmed successful outcome carrying its payload. */
    data class Success<out T>(val data: T) : AppResult<T>

    /** The back office applied a business rule and said no (ineligible, conflict, invalid, no-match). */
    data class BusinessRejection(val error: AppError.Business) : AppResult<Nothing>

    /** A retriable transport/server error (connectivity, 5xx). */
    data class TransientFailure(val error: AppError.Transient) : AppResult<Nothing>

    /** No definitive outcome was received; must be treated as uncertain, never as success. */
    data object Timeout : AppResult<Nothing>
}

/** The [AppError] behind any non-success result, or null for [AppResult.Success]. */
fun AppResult<*>.appErrorOrNull(): AppError? = when (this) {
    is AppResult.Success -> null
    is AppResult.BusinessRejection -> error
    is AppResult.TransientFailure -> error
    AppResult.Timeout -> AppError.Timeout
}

/** Maps the success payload, propagating any failure variant unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.BusinessRejection -> this
    is AppResult.TransientFailure -> this
    AppResult.Timeout -> AppResult.Timeout
}

/**
 * A structured, non-user-facing error. Mapped to a clear, non-revealing [UiMessage] by
 * [ErrorMapper]; no [AppError] ever carries identity or biometric data (FR-029).
 */
sealed interface AppError {

    /** A back-office business rejection. [serverReason] is diagnostic only and never shown raw. */
    data class Business(val code: BusinessCode, val serverReason: String? = null) : AppError

    /** A retriable transport/server failure. */
    data class Transient(val kind: TransientKind, val cause: Throwable? = null) : AppError

    /** No definitive outcome (timeout / connectivity loss mid-request). */
    data object Timeout : AppError

    /** The session is no longer valid; the app must force re-authentication (FR-004). */
    data class SessionInvalid(val kind: SessionErrorKind) : AppError
}

/** Curated business outcomes. Each maps to a fixed, non-revealing user message. */
enum class BusinessCode {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    MEMBER_INVALID,
    CARD_UNREADABLE,
    PATIENT_NOT_FOUND,
    FACE_NO_MATCH,
    FACE_SPOOF,
    FACE_LOCKED_OUT,
    SUBJECT_MISMATCH,
    CONSENT_WITHHELD,
    DUPLICATE_SERVICE,
    SERVICE_INELIGIBLE,
    NOT_CURRENTLY_VERIFIED,
    GENERIC,
}

enum class TransientKind { NO_CONNECTIVITY, SERVER_ERROR, UNKNOWN }

enum class SessionErrorKind { EXPIRED, INVALIDATED, NONE }
