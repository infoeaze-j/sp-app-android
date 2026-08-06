package com.mediplus.spapp.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed contract. Anything not explicitly permitted must be refused, so that a future SDK
 * upgrade or a newly added endpoint cannot leak by default. Companion to [LoggingRedactionTest],
 * which pins the same guarantee for logcat.
 */
class ScrubRulesTest {

    private val memberNumber = "634743753"
    private val baseUrl = "https://bio.infoeaze.com/api/v1"

    @Test
    fun `a member number in a path segment is templated away`() {
        val scrubbed = ScrubRules.templateUrl("$baseUrl/members/$memberNumber/services")

        assertEquals("$baseUrl/members/{id}/services", scrubbed)
        assertFalse(scrubbed.contains(memberNumber))
    }

    @Test
    fun `the enrollments endpoint is templated too`() {
        assertEquals(
            "$baseUrl/members/{id}/enrollments",
            ScrubRules.templateUrl("$baseUrl/members/$memberNumber/enrollments"),
        )
    }

    @Test
    fun `the query string is dropped whole`() {
        assertEquals(
            "$baseUrl/app/releases/latest",
            ScrubRules.templateUrl("$baseUrl/app/releases/latest?versionCode=5"),
        )
    }

    @Test
    fun `the endpoint shape and host survive so the failing call is still identifiable`() {
        val scrubbed = ScrubRules.templateUrl("$baseUrl/members/$memberNumber/services")

        assertTrue(scrubbed.startsWith("https://bio.infoeaze.com"))
        assertTrue(scrubbed.endsWith("/services"))
    }

    @Test
    fun `short numeric segments and version segments are left alone`() {
        assertEquals("$baseUrl/auth/session", ScrubRules.templateUrl("$baseUrl/auth/session"))
        assertEquals(
            "https://host/api/v1/diagnostics/requests/pending",
            ScrubRules.templateUrl("https://host/api/v1/diagnostics/requests/pending"),
        )
    }

    @Test
    fun `a segment that merely contains digits is not templated`() {
        assertEquals("https://host/v1/abc1234def", ScrubRules.templateUrl("https://host/v1/abc1234def"))
    }

    @Test
    fun `allowed categories pass`() {
        listOf("navigation", "app.lifecycle", "ui.lifecycle", "network.event", "http").forEach {
            assertTrue("expected $it to be allowed", ScrubRules.isAllowedCategory(it))
        }
    }

    @Test
    fun `logcat and user interaction categories are refused`() {
        assertFalse(ScrubRules.isAllowedCategory("logcat"))
        assertFalse(ScrubRules.isAllowedCategory("ui.click"))
    }

    @Test
    fun `an unrecognised category is refused - this is the fail-closed property`() {
        assertFalse(ScrubRules.isAllowedCategory("some.future.sdk.category"))
        assertFalse(ScrubRules.isAllowedCategory(""))
        assertFalse(ScrubRules.isAllowedCategory(null))
    }

    @Test
    fun `a long digit run in a message is redacted`() {
        assertEquals(
            "failed to parse member {redacted}",
            ScrubRules.redactDigitRuns("failed to parse member $memberNumber"),
        )
    }

    @Test
    fun `short numbers in messages survive`() {
        assertEquals("HTTP 401 after 3 retries", ScrubRules.redactDigitRuns("HTTP 401 after 3 retries"))
    }

    @Test
    fun `null text stays null`() {
        assertNull(ScrubRules.redactDigitRuns(null))
    }

    @Test
    fun `only url method and status are permitted http breadcrumb data`() {
        assertEquals(setOf("url", "method", "status_code"), ScrubRules.allowedHttpDataKeys)
    }
}
