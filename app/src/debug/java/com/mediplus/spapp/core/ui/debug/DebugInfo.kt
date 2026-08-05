package com.mediplus.spapp.core.ui.debug

import com.mediplus.spapp.BuildConfig

/** One label/value row in the debug panel. */
data class DebugInfoEntry(val label: String, val value: String)

/**
 * The facts the debug overlay reports about the running build.
 *
 * Read straight off [BuildConfig] rather than mirrored into a constant here, so the panel can never
 * disagree with the stack it is describing — the point of the panel is to settle "which back office
 * is this install actually pointed at?" on a clinic device with no debugger attached.
 */
object DebugInfo {
    fun entries(): List<DebugInfoEntry> = listOf(
        DebugInfoEntry(label = "Base URL", value = BuildConfig.BASE_URL),
    )
}
