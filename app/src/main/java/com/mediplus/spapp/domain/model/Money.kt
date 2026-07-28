package com.mediplus.spapp.domain.model

/**
 * A positive amount of money in minor units, decoupled from any currency — the currency is carried
 * separately because the back office supplies the list of allowed ones, each with its own
 * [Currency.minorUnitExponent].
 *
 * Minor units rather than a decimal type so no floating-point rounding can ever reach the wire.
 * How many minor units make a major one is *not* assumed: scaling every amount by 100 is wrong for
 * JPY (exponent 0) and for KWD (exponent 3), so both [parse] and [format] take the exponent the
 * back office reported for the selected currency.
 */
data class Money(val minorUnits: Long) {

    /**
     * Renders the amount in the same form [parse] accepts for the same [minorUnitExponent], so what
     * the operator reviews is exactly what will be submitted and the string can be fed straight back
     * into the amount field. Locale-independent for the same reason [parse] is: the device locale
     * must never change what the operator is shown to be agreeing to.
     */
    fun format(minorUnitExponent: Int = DEFAULT_MINOR_UNIT_EXPONENT): String {
        val exponent = minorUnitExponent.coerceIn(0, MAX_MINOR_UNIT_EXPONENT)
        if (exponent == 0) return minorUnits.toString()
        val scale = scaleFor(exponent)
        return "${minorUnits / scale}.${(minorUnits % scale).toString().padStart(exponent, '0')}"
    }

    companion object {

        /** What to assume when the back office reports a currency without an exponent. */
        const val DEFAULT_MINOR_UNIT_EXPONENT: Int = 2

        /** No live ISO 4217 currency exceeds four minor digits; anything larger is clamped. */
        const val MAX_MINOR_UNIT_EXPONENT: Int = 4

        /**
         * Parses operator input. Returns null for anything that is not a strictly positive amount
         * within [minorUnitExponent] decimal places — empty, zero, negative, over-precise, or
         * malformed. A zero-exponent currency accepts no decimal separator at all.
         *
         * Deliberately locale-independent: `.` is the only accepted decimal separator and only
         * ASCII digits are accepted, so the device locale can never change what gets transmitted.
         * Whitespace is rejected rather than trimmed, so the caller never has to wonder whether the
         * string it holds is the string that was parsed.
         */
        fun parse(text: String, minorUnitExponent: Int = DEFAULT_MINOR_UNIT_EXPONENT): Money? {
            val exponent = minorUnitExponent.coerceIn(0, MAX_MINOR_UNIT_EXPONENT)
            if (!patternFor(exponent).matches(text)) return null
            val whole = text.substringBefore('.')
            val fraction = text.substringAfter('.', missingDelimiterValue = "").padEnd(exponent, '0')
            val minor = whole.toLong() * scaleFor(exponent) + (fraction.toLongOrNull() ?: 0L)
            return if (minor > 0) Money(minor) else null
        }

        /**
         * Capping whole + minor digits at 17 keeps the scaled value inside [Long] with room to
         * spare, so the digit cap doubles as the overflow guard. At the common exponent of 2 that
         * is the same 15 whole digits the amount keypad has always allowed.
         */
        private const val MAX_TOTAL_DIGITS = 17
        private const val RADIX = 10L

        private fun patternFor(exponent: Int): Regex {
            val whole = MAX_TOTAL_DIGITS - exponent
            return if (exponent == 0) {
                Regex("""^\d{1,$whole}$""")
            } else {
                Regex("""^\d{1,$whole}(\.\d{1,$exponent})?$""")
            }
        }

        private fun scaleFor(exponent: Int): Long {
            var scale = 1L
            repeat(exponent) { scale *= RADIX }
            return scale
        }
    }
}
