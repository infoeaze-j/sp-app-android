package com.mediplus.spapp.core.ui.debug

import com.mediplus.spapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug panel exists to answer "which back office is this build actually talking to?" on a
 * device nobody can attach a debugger to, so the value it reports has to be the one the network
 * stack was built with — not a copy that can drift.
 */
class DebugInfoTest {

    @Test
    fun `reports the base URL the build is wired to`() {
        val baseUrl = DebugInfo.entries().single { it.label == "Base URL" }

        assertEquals(BuildConfig.BASE_URL, baseUrl.value)
    }

    @Test
    fun `every entry is labelled and populated`() {
        DebugInfo.entries().forEach { entry ->
            assertTrue("blank label: $entry", entry.label.isNotBlank())
            assertTrue("blank value: $entry", entry.value.isNotBlank())
        }
    }

    /** Two rows under one label would make the panel ambiguous as facts are added to it. */
    @Test
    fun `labels are unique`() {
        val labels = DebugInfo.entries().map { it.label }

        assertEquals(labels.size, labels.distinct().size)
    }
}
