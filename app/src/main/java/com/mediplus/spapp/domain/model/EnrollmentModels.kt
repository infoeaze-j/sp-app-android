package com.mediplus.spapp.domain.model

/**
 * A service (the reason/purpose of the current visit), chosen per transaction (FR-023, FR-023a).
 * Only server-reported services are selectable; the app invents none.
 */
data class Service(
    val serviceId: String,
    /** The provider-facing code for the service; display-only alongside [description]. */
    val code: String,
    val description: String,
    val eligibleForPatient: Boolean,
    /** Already added for this visit, so offering it again would only produce a duplicate. */
    val alreadyEnrolled: Boolean,
)

/**
 * A currency the back office will accept for an enrollment. Like [Service], the app enumerates what
 * the server reports and invents none. [code] is what goes on the wire; [label] is display-only.
 *
 * [minorUnitExponent] is stated by the server rather than assumed, because scaling every amount by
 * 100 is wrong for JPY and for KWD — see [Money].
 */
data class Currency(
    val code: String,
    val label: String,
    val minorUnitExponent: Int = Money.DEFAULT_MINOR_UNIT_EXPONENT,
    val isDefault: Boolean = false,
)

/**
 * What one services call returns: what can be added, in which currencies, and for which visit. They
 * travel together so they can never drift apart in the UI state.
 */
data class ServiceCatalog(
    val services: List<Service>,
    val currencies: List<Currency>,
    val visitDate: String? = null,
)

/**
 * One enrollment submission, minus the member it is for. Bundled rather than passed as loose
 * arguments because a retry must re-send *exactly* these values — same key, same verification, same
 * amount — and a single object cannot drift apart between the first attempt and the retry (FR-022).
 *
 * [verificationId] is the single-use token the face step issued; the back office checks it belongs
 * to this member, has not expired and has not already been spent.
 */
data class EnrollmentRequest(
    val serviceId: String,
    val verificationId: String,
    /** The [Currency.code] that was selected, never its display label. */
    val currency: String,
    val amount: Money,
    val idempotencyKey: String,
)

/** Outcome of an enrollment submission (FR-020, FR-022). */
sealed interface EnrollmentStatus {
    /** Confirmed by the back office — the only success signal (FR-020). */
    data class Confirmed(val enrollmentId: String) : EnrollmentStatus

    /** A specific business rejection (ineligible/conflict) (FR-021). */
    data class Rejected(val reason: String?) : EnrollmentStatus

    /** No definitive outcome (timeout/connectivity) — never shown as success (FR-022). */
    data object Uncertain : EnrollmentStatus

    /** Submitted, awaiting confirmation. */
    data object Pending : EnrollmentStatus
}

/**
 * The record that a verified patient had a service added for the current visit (FR-020, FR-022,
 * FR-023a). The [idempotencyKey] is per-transaction so retries after an uncertain outcome never
 * create a duplicate.
 */
data class Enrollment(
    val enrollmentId: String?,
    val memberNumber: String,
    val service: Service,
    val idempotencyKey: String,
    val status: EnrollmentStatus,
    val timestampMillis: Long?,
    /**
     * What was charged. Null on the re-check path only: re-check identifies an enrollment by
     * idempotency key alone and the response carries no amount, so the repository has nothing to
     * populate these with there — the same reason [enrollmentId] and [Service.description] are
     * already empty on that path.
     */
    val currency: String?,
    val amount: Money?,
    /** The visit the enrollment was recorded against, as the back office dates it. */
    val visitDate: String? = null,
)
