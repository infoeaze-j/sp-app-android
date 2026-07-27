package com.mediplus.faceverify.core.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Locks the generated Material 3 "Vibrant" palette (seed #EF5D22) and proves the strict-M3
 * accessibility promise. Regenerate this and Color.kt together if the palette ever changes.
 * See docs/superpowers/specs/2026-07-27-material3-orange-theme-design.md.
 */
class FaceVerifyThemeColorsTest {

    @Test
    fun `light scheme exposes the generated vibrant palette`() {
        assertEquals(Color(0xFFAA3700), LightColors.primary)
        assertEquals(Color(0xFFFFFFFF), LightColors.onPrimary)
        assertEquals(Color(0xFFFFDBCF), LightColors.primaryContainer)
        assertEquals(Color(0xFF822800), LightColors.onPrimaryContainer)
        assertEquals(Color(0xFF7E5537), LightColors.secondary)
        assertEquals(Color(0xFF7E571D), LightColors.tertiary)
        assertEquals(Color(0xFFFFF8F6), LightColors.surface)
    }

    @Test
    fun `dark scheme exposes the generated vibrant palette`() {
        assertEquals(Color(0xFFFFB59C), DarkColors.primary)
        assertEquals(Color(0xFF5C1A00), DarkColors.onPrimary)
        assertEquals(Color(0xFF822800), DarkColors.primaryContainer)
        assertEquals(Color(0xFFFFDBCF), DarkColors.onPrimaryContainer)
        assertEquals(Color(0xFFF1BC96), DarkColors.secondary)
        assertEquals(Color(0xFFF1BD7A), DarkColors.tertiary)
        assertEquals(Color(0xFF1E100B), DarkColors.surface)
    }

    @Test
    fun `key role pairs clear WCAG AA contrast in both schemes`() {
        val pairs = listOf(
            LightColors.onPrimary to LightColors.primary,
            LightColors.onPrimaryContainer to LightColors.primaryContainer,
            LightColors.onSurface to LightColors.surface,
            DarkColors.onPrimary to DarkColors.primary,
            DarkColors.onPrimaryContainer to DarkColors.primaryContainer,
            DarkColors.onSurface to DarkColors.surface,
        )
        pairs.forEach { (fg, bg) ->
            val ratio = contrastRatio(fg, bg)
            assertTrue("contrast $ratio below 4.5:1 for $fg on $bg", ratio >= 4.5)
        }
    }

    @Test
    fun `scheme is wired, not left at Material defaults`() {
        assertNotEquals(lightColorScheme().primary, LightColors.primary)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
}
