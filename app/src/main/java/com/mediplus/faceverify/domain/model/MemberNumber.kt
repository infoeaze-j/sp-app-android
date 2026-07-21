package com.mediplus.faceverify.domain.model

/**
 * A member card number: digits only, longer than 6 characters (FR-011a). Constructed only through
 * [parse], so an instance is proof the format rule already passed — callers never re-validate.
 *
 * [toString] is deliberately redacting: the number identifies a patient, so an accidental
 * interpolation into a log line or an exception message must not leak it. Use [value] to send it.
 */
@JvmInline
value class MemberNumber private constructor(val value: String) {

    override fun toString(): String = REDACTED

    companion object {
        /** "More than 6 characters" — the shortest accepted number is 7 digits. */
        const val MIN_LENGTH = 7

        /** Bounded above so a garbage NDEF payload cannot become an unbounded URL path segment. */
        const val MAX_LENGTH = 32

        private const val REDACTED = "MemberNumber(***)"
        private val PATTERN = Regex("^[0-9]{$MIN_LENGTH,$MAX_LENGTH}$")

        /** The validated number, or null when [raw] is absent or not a well-formed card number. */
        fun parse(raw: String?): MemberNumber? {
            val trimmed = raw?.trim() ?: return null
            return if (PATTERN.matches(trimmed)) MemberNumber(trimmed) else null
        }
    }
}
