package com.mediplus.spapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money.parse is the only gate between operator keystrokes and what is transmitted, so the
 * invalid column matters as much as the valid one: anything it lets through is charged.
 *
 * The minor-unit exponent is the back office's to state (`currencies[].minorUnitExponent`), and
 * these cases pin down that it is honoured rather than assumed: scaling by 100 regardless of
 * currency is wrong for JPY (0) and for KWD (3), and getting it wrong charges 100× or 1/10×.
 */
class MoneyTest {

    @Test
    fun `whole numbers become cents`() {
        assertEquals(Money(15_000), Money.parse("150"))
    }

    @Test
    fun `one decimal place is padded to two`() {
        assertEquals(Money(15_050), Money.parse("150.5"))
    }

    @Test
    fun `two decimal places are exact`() {
        assertEquals(Money(15_000), Money.parse("150.00"))
    }

    @Test
    fun `the smallest positive amount is one cent`() {
        assertEquals(Money(1), Money.parse("0.01"))
    }

    @Test
    fun `zero is rejected`() {
        assertNull(Money.parse("0"))
        assertNull(Money.parse("0.00"))
    }

    @Test
    fun `negatives are rejected`() {
        assertNull(Money.parse("-1"))
        assertNull(Money.parse("-1.50"))
    }

    @Test
    fun `more than two decimal places is rejected`() {
        assertNull(Money.parse("1.234"))
    }

    @Test
    fun `empty and non-numeric input is rejected`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("."))
        assertNull(Money.parse("1."))
    }

    @Test
    fun `a comma is never a decimal separator`() {
        assertNull(Money.parse("1,50"))
    }

    @Test
    fun `surrounding whitespace is rejected rather than trimmed`() {
        assertNull(Money.parse(" 150 "))
    }

    @Test
    fun `an absurdly long number is rejected instead of overflowing`() {
        assertNull(Money.parse("1234567890123456"))
    }

    @Test
    fun `format always shows two decimal places`() {
        assertEquals("150.00", Money(15_000).format())
        assertEquals("150.50", Money(15_050).format())
        assertEquals("150.05", Money(15_005).format())
        assertEquals("0.01", Money(1).format())
    }

    /**
     * The summary shows [Money.format] and the amount field is refilled from it, so a formatted
     * amount that didn't parse back to itself would let the reviewed number drift from the sent one.
     */
    @Test
    fun `format round-trips through parse`() {
        listOf("150", "150.5", "0.01", "1234567890.99").forEach { input ->
            val parsed = requireNonNull(Money.parse(input), input)
            assertEquals(parsed, Money.parse(parsed.format()))
        }
    }

    // ---- currency minor units ----

    @Test
    fun `a zero-exponent currency parses whole units and takes no decimal separator`() {
        assertEquals(Money(150), Money.parse("150", minorUnitExponent = 0))
        assertNull(Money.parse("150.5", minorUnitExponent = 0))
        assertNull(Money.parse("150.00", minorUnitExponent = 0))
    }

    @Test
    fun `a zero-exponent currency formats without a decimal point`() {
        assertEquals("150", Money(150).format(minorUnitExponent = 0))
    }

    @Test
    fun `a three-exponent currency accepts three decimal places and pads short ones`() {
        assertEquals(Money(150_500), Money.parse("150.5", minorUnitExponent = 3))
        assertEquals(Money(150_505), Money.parse("150.505", minorUnitExponent = 3))
        assertNull(Money.parse("150.5055", minorUnitExponent = 3))
        assertEquals("150.505", Money(150_505).format(minorUnitExponent = 3))
    }

    @Test
    fun `an out-of-range exponent is clamped rather than throwing`() {
        assertEquals(Money.parse("150", minorUnitExponent = 0), Money.parse("150", minorUnitExponent = -1))
        assertEquals(Money(150).format(minorUnitExponent = 0), Money(150).format(minorUnitExponent = -1))
        assertEquals(Money(1).format(minorUnitExponent = 4), Money(1).format(minorUnitExponent = 99))
    }

    @Test
    fun `the digit cap tightens as the exponent grows, so nothing overflows`() {
        assertNull(Money.parse("1".repeat(15), minorUnitExponent = 3))
        assertEquals(Money(11_111_111_111_111_000), Money.parse("1".repeat(14), minorUnitExponent = 3))
    }

    @Test
    fun `format round-trips through parse at every exponent`() {
        listOf(0, 2, 3).forEach { exponent ->
            listOf(Money(1), Money(150), Money(150_505)).forEach { money ->
                assertEquals(money, Money.parse(money.format(exponent), exponent))
            }
        }
    }

    private fun requireNonNull(money: Money?, input: String): Money =
        money ?: throw AssertionError("expected $input to parse")
}
