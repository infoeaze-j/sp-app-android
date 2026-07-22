package com.mediplus.faceverify.domain.model

/**
 * A positive amount of money in minor units (cents), decoupled from any currency — the currency is
 * carried separately because the back office supplies the list of allowed ones.
 *
 * Minor units rather than a decimal type so no floating-point rounding can ever reach the wire.
 * Every currency in use is assumed to have 2 decimal places; see the "Known limitation" section of
 * the design spec.
 */
data class Money(val cents: Long) {

    companion object {
        /**
         * At most 15 whole digits keeps `whole * 100` inside [Long] with room to spare, so the
         * digit cap doubles as the overflow guard.
         */
        private val PATTERN = Regex("""^\d{1,15}(\.\d{1,2})?$""")

        /**
         * Parses operator input. Returns null for anything that is not a strictly positive amount
         * with at most two decimal places — empty, zero, negative, over-precise, or malformed.
         *
         * Deliberately locale-independent: `.` is the only accepted decimal separator and only
         * ASCII digits are accepted, so the device locale can never change what gets transmitted.
         * Whitespace is rejected rather than trimmed, so the caller never has to wonder whether the
         * string it holds is the string that was parsed.
         */
        fun parse(text: String): Money? {
            if (!PATTERN.matches(text)) return null
            val whole = text.substringBefore('.')
            val fraction = text.substringAfter('.', missingDelimiterValue = "").padEnd(2, '0')
            val cents = whole.toLong() * 100 + fraction.toLong()
            return if (cents > 0) Money(cents) else null
        }
    }
}
