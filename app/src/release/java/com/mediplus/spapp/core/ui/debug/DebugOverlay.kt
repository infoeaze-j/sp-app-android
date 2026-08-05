package com.mediplus.spapp.core.ui.debug

import androidx.compose.runtime.Composable

/**
 * Release counterpart of the debug build's bug button: nothing.
 *
 * The same per-source-set split the DI modules under `core/di` use, for the same reason — the debug
 * affordance is absent from a release build by construction rather than by a runtime
 * `BuildConfig.DEBUG` check that leaves the code in the APK.
 */
@Composable
fun DebugOverlay() = Unit
