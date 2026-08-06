package com.mediplus.spapp.core.crash

/**
 * The fail-closed filter deciding what may leave the device in a crash report.
 *
 * Pure by design — no Android, no Sentry types — so every rule is exhaustively unit-testable and the
 * Sentry-shaped adapter in [SentryScrubber] stays thin. Nothing here is a denylist: an unrecognised
 * breadcrumb category is refused, so a future SDK upgrade that introduces a new one leaks nothing
 * until someone opts it in. Same fail-safe direction as a null freshness window counting as stale.
 */
object ScrubRules {

    const val ID_PLACEHOLDER = "{id}"
    const val REDACTED = "{redacted}"

    /**
     * A wholly-numeric path segment is an identifier essentially by definition, so the bar is low.
     * `MemberNumber` is `^[0-9]{7,32}$`, so 4 leaves margin without touching `/v1/`.
     */
    private const val MIN_ID_DIGITS_IN_PATH = 4

    /**
     * A message is prose that may contain incidental small numbers (status codes, retry counts), so
     * the bar is the shortest real member number. Accepted cost: a legitimate 7+ digit byte count or
     * epoch millis in a message is redacted too.
     */
    private const val MIN_ID_DIGITS_IN_TEXT = 7

    private val NUMERIC_SEGMENT = Regex("^\\d{$MIN_ID_DIGITS_IN_PATH,}$")
    private val LONG_DIGIT_RUN = Regex("\\d{$MIN_ID_DIGITS_IN_TEXT,}")

    /** Breadcrumb categories permitted to leave the device. Everything else is dropped. */
    private val ALLOWED_CATEGORIES = setOf(
        // Static route names only: AppRoute carries nothing but its path and no destination takes
        // arguments, so these can never contain an identifier.
        "navigation",
        "app.lifecycle",
        "ui.lifecycle",
        // Bandwidth and wifi-vs-cellular. Genuinely useful for a flaky clinic connection; no identity.
        "network.event",
        // Kept only because templateUrl strips the member number from the path.
        "http",
    )

    /** The only breadcrumb data keys an http crumb may carry out. */
    val allowedHttpDataKeys: Set<String> = setOf("url", "method", "status_code")

    fun isAllowedCategory(category: String?): Boolean = category in ALLOWED_CATEGORIES

    /**
     * Replaces every wholly-numeric path segment with [ID_PLACEHOLDER], redacts embedded digit runs
     * of 7+ digits, and drops the query string, so `members/634743753/services` becomes
     * `members/{id}/services` and `members/634743753v2/services` becomes
     * `members/{redacted}v2/services`. Scheme, host and endpoint shape survive — enough to know
     * which call failed.
     */
    fun templateUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val templated = path.split('/')
            .joinToString("/") { segment -> if (NUMERIC_SEGMENT.matches(segment)) ID_PLACEHOLDER else segment }
        return redactDigitRuns(templated) ?: templated
    }

    /** Replaces long digit runs anywhere in free text, guarding against interpolated identifiers. */
    fun redactDigitRuns(text: String?): String? = text?.replace(LONG_DIGIT_RUN, REDACTED)
}
