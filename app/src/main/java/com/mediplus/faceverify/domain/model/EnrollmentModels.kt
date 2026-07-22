package com.mediplus.faceverify.domain.model

/**
 * A service (the reason/purpose of the current visit), chosen per transaction (FR-023, FR-023a).
 * Only server-reported services are selectable; the app invents none.
 */
data class Service(
    val serviceId: String,
    val description: String,
    val eligibleForPatient: Boolean,
    val alreadySelected: Boolean,
)

/**
 * A currency the back office will accept for an enrollment. Like [Service], the app enumerates what
 * the server reports and invents none. [value] is what goes on the wire; [label] is display-only.
 */
data class Currency(val value: String, val label: String)

/**
 * What one services call returns: what can be added, and in which currencies. The two travel
 * together so they can never drift apart in the UI state.
 */
data class ServiceCatalog(
    val services: List<Service>,
    val currencies: List<Currency>,
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
)
