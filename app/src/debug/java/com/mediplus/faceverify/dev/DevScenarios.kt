package com.mediplus.faceverify.dev

/** Which canned outcome each faked endpoint group returns. Names are persisted verbatim. */
enum class AuthScenario { SUCCESS, INVALID_CREDENTIALS, ACCOUNT_LOCKED, THROTTLED, SERVER_ERROR }

enum class DocumentScenario { SUCCESS, INVALID, PATIENT_NOT_FOUND, SERVER_ERROR }

enum class FaceScenario { PASS, FAIL_NO_MATCH, FAIL_LIVENESS, SUBJECT_MISMATCH, LOCKED_OUT, SERVER_ERROR }

enum class ServicesScenario { SUCCESS, EMPTY, PATIENT_NOT_FOUND, SERVER_ERROR }

enum class EnrollScenario { CONFIRMED, DUPLICATE, INELIGIBLE, TIMEOUT, SERVER_ERROR }
