package com.mediplus.faceverify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money.parse is the only gate between operator keystrokes and what is transmitted, so the
 * invalid column matters as much as the valid one: anything it lets through is charged.
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
}
