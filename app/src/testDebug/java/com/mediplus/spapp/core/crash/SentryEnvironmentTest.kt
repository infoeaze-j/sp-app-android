package com.mediplus.spapp.core.crash

import com.mediplus.spapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The environment tag separates dev churn from field crashes. The wizard left it unset, which the
 * SDK defaults to "production" — so an emulator run was indistinguishable from a clinic device.
 */
class SentryEnvironmentTest {

    @Test
    fun `debug builds report as development`() {
        assertEquals("development", BuildConfig.SENTRY_ENVIRONMENT)
    }
}
