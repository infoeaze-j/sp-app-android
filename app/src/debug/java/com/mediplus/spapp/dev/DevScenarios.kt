package com.mediplus.spapp.dev

/** Which canned outcome each faked endpoint group returns. Names are persisted verbatim. */
enum class AuthScenario { SUCCESS, INVALID_CREDENTIALS, ACCOUNT_LOCKED, THROTTLED, SERVER_ERROR }

enum class FaceScenario { PASS, FAIL_NO_MATCH, FAIL_LIVENESS, SUBJECT_MISMATCH, LOCKED_OUT, SERVER_ERROR }

enum class ServicesScenario { SUCCESS, EMPTY, PATIENT_NOT_FOUND, SERVER_ERROR }

enum class EnrollScenario { CONFIRMED, DUPLICATE, INELIGIBLE, TIMEOUT, SERVER_ERROR }

/** How many currencies the faked services endpoint reports. NONE halts the add-service step. */
enum class CurrencyScenario { MULTIPLE, SINGLE, NONE }

enum class MemberScenario { SUCCESS, INVALID, PATIENT_NOT_FOUND, SERVER_ERROR }

/**
 * The emulated member card tap. Unlike the other scenarios this one fakes *device hardware*, not a
 * back-office response, so it also covers the two no-hardware states the scan screen can show.
 */
enum class CardScenario { SUCCESS, UNREADABLE, TIMEOUT, NFC_DISABLED, NO_NFC_HARDWARE }

/**
 * The emulated camera. Like [CardScenario] and unlike the back-office scenarios, this fakes *device
 * hardware* rather than a server response, so it also covers the no-hardware state.
 */
enum class CameraScenario { SUCCESS, NEVER_GOOD, CAPTURE_ERROR, NO_CAMERA_HARDWARE }

/**
 * The emulated self-update journey. The failure scenarios each land at a different stage:
 * CHECK_FAILS at the version check, DOWNLOAD_FAILS mid-stream, HASH_MISMATCH after a complete
 * download, INSTALL_FAILS at the (faked) installer.
 */
enum class UpdateScenario {
    UP_TO_DATE,
    OPTIONAL_UPDATE,
    FORCED_UPDATE,
    CHECK_FAILS,
    DOWNLOAD_FAILS,
    HASH_MISMATCH,
    INSTALL_FAILS,
}

/**
 * The emulated diagnostics telemetry. OFF = back office wants nothing; REQUESTED_ONCE = one
 * request id, then silent; ALWAYS_REQUESTED = a new id every poll; POLL_FAILS / REPORT_FAILS
 * exercise the swallowed-failure paths.
 */
enum class DiagnosticsScenario { OFF, REQUESTED_ONCE, ALWAYS_REQUESTED, POLL_FAILS, REPORT_FAILS }
