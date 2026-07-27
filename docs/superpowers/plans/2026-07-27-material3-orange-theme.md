# Material 3 Orange Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's blue/teal Material 3 palette with a warm-orange one seeded from the brand color `#EF5D22`, guarded by a new color test.

**Architecture:** The app is already Material 3. This is a palette-only swap in two files (`core/ui/theme/Color.kt`, `core/ui/theme/Theme.kt`) plus one new JVM unit test. All 56 color values are the locked output of Material's official `SchemeVibrant` algorithm and must be reproduced exactly. No screen, ViewModel, navigation, string, typography, or spacing change.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose Material 3, JUnit4. Palette source: `@material/material-color-utilities` `SchemeVibrant`, seed `#EF5D22`, contrast level `0.0`.

**Spec:** `docs/superpowers/specs/2026-07-27-material3-orange-theme-design.md`

## Global Constraints

- **JAVA_HOME must be set for every Gradle command:** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell).
- **Design tokens only** — no hardcoded colors outside `core/ui/theme/`. This change does not touch any screen.
- **Test-first (constitution)** — the failing test is written and observed failing before the palette is swapped.
- **detekt numeric rules** — line length ≤ 120, functions ≤ 50 lines, `ReturnCount` ≤ 4, `maxIssues: 0`, `warningsAsErrors`. detekt is CI-only (not a Gradle task); `Color.kt` currently carries baseline `MagicNumber` findings.
- **Palette is locked** — the 56 values in Task 2 are authoritative. Do not round, re-derive, or "improve" them; the test guards them.

---

### Task 1: Expose the color schemes to tests (prep)

Make the two `ColorScheme` values visible to the unit-test source set so the guard test can assert against them. This is a visibility-only change with no runtime effect; it is a separate step because the failing test in Task 2 must compile against it.

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/core/ui/theme/Theme.kt`

**Interfaces:**
- Produces: `internal val LightColors: ColorScheme` and `internal val DarkColors: ColorScheme` in package `com.mediplus.faceverify.core.ui.theme` (currently `private`).

- [ ] **Step 1: Widen visibility from `private` to `internal`**

In `Theme.kt`, change the two declarations:

```kotlin
private val LightColors = lightColorScheme(
```
to
```kotlin
internal val LightColors = lightColorScheme(
```

and

```kotlin
private val DarkColors = darkColorScheme(
```
to
```kotlin
internal val DarkColors = darkColorScheme(
```

Leave the bodies unchanged for now (they are wired fully in Task 3).

- [ ] **Step 2: Verify it still compiles**

Run (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/core/ui/theme/Theme.kt
git commit -m "refactor: expose theme ColorSchemes as internal for testing"
```

---

### Task 2: Add the failing palette + contrast test (TDD red)

Write the guard test first and watch it fail against the current blue palette.

**Files:**
- Create: `app/src/test/java/com/mediplus/faceverify/core/ui/theme/FaceVerifyThemeColorsTest.kt`

**Interfaces:**
- Consumes: `LightColors`, `DarkColors` (Task 1).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/core/ui/theme/FaceVerifyThemeColorsTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test and confirm it fails**

Run (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --tests "*.FaceVerifyThemeColorsTest"
```
Expected: FAIL. The value assertions fail because the current palette is still blue
(e.g. `expected:<Color(...AA3700...)> but was:<Color(...00658F...)>` for `LightColors.primary`).

- [ ] **Step 3: Commit the failing test**

```bash
git add app/src/test/java/com/mediplus/faceverify/core/ui/theme/FaceVerifyThemeColorsTest.kt
git commit -m "test: lock generated orange palette and role contrast (red)"
```

---

### Task 3: Swap the palette to green the test

Replace the palette constants and wire every role, turning the Task 2 test green.

**Files:**
- Modify (full replace): `app/src/main/java/com/mediplus/faceverify/core/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/ui/theme/Theme.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: 56 `internal val Color` constants consumed by `Theme.kt` (names listed below).

- [ ] **Step 1: Replace `Color.kt` entirely**

Overwrite `app/src/main/java/com/mediplus/faceverify/core/ui/theme/Color.kt` with:

```kotlin
@file:Suppress("MagicNumber") // Color-token definitions: hex literals are the value, by design.

package com.mediplus.faceverify.core.ui.theme

import androidx.compose.ui.graphics.Color

// Material 3 "Vibrant" tonal scheme seeded from the brand orange #EF5D22.
// Generated by @material/material-color-utilities (SchemeVibrant, contrast 0.0).
// Locked and guarded by FaceVerifyThemeColorsTest — regenerate both together.
// Spec: docs/superpowers/specs/2026-07-27-material3-orange-theme-design.md

// --- Light: primary / secondary / tertiary ---
internal val Primary = Color(0xFFAA3700)
internal val OnPrimary = Color(0xFFFFFFFF)
internal val PrimaryContainer = Color(0xFFFFDBCF)
internal val OnPrimaryContainer = Color(0xFF822800)
internal val Secondary = Color(0xFF7E5537)
internal val OnSecondary = Color(0xFFFFFFFF)
internal val SecondaryContainer = Color(0xFFFFDCC5)
internal val OnSecondaryContainer = Color(0xFF633E22)
internal val Tertiary = Color(0xFF7E571D)
internal val OnTertiary = Color(0xFFFFFFFF)
internal val TertiaryContainer = Color(0xFFFFDDB5)
internal val OnTertiaryContainer = Color(0xFF633F05)

// --- Light: error ---
internal val Error = Color(0xFFBA1A1A)
internal val OnError = Color(0xFFFFFFFF)
internal val ErrorContainer = Color(0xFFFFDAD6)
internal val OnErrorContainer = Color(0xFF93000A)

// --- Light: neutral surfaces ---
internal val Background = Color(0xFFFFF8F6)
internal val OnBackground = Color(0xFF271813)
internal val Surface = Color(0xFFFFF8F6)
internal val OnSurface = Color(0xFF271813)
internal val SurfaceVariant = Color(0xFFFDDBD1)
internal val OnSurfaceVariant = Color(0xFF58423A)

// --- Light: utility ---
internal val Outline = Color(0xFF8C7169)
internal val OutlineVariant = Color(0xFFDFC0B6)
internal val Scrim = Color(0xFF000000)
internal val InverseSurface = Color(0xFF3D2D27)
internal val InverseOnSurface = Color(0xFFFFEDE8)
internal val InversePrimary = Color(0xFFFFB59C)

// --- Dark: primary / secondary / tertiary ---
internal val PrimaryDark = Color(0xFFFFB59C)
internal val OnPrimaryDark = Color(0xFF5C1A00)
internal val PrimaryContainerDark = Color(0xFF822800)
internal val OnPrimaryContainerDark = Color(0xFFFFDBCF)
internal val SecondaryDark = Color(0xFFF1BC96)
internal val OnSecondaryDark = Color(0xFF49290E)
internal val SecondaryContainerDark = Color(0xFF633E22)
internal val OnSecondaryContainerDark = Color(0xFFFFDCC5)
internal val TertiaryDark = Color(0xFFF1BD7A)
internal val OnTertiaryDark = Color(0xFF462B00)
internal val TertiaryContainerDark = Color(0xFF633F05)
internal val OnTertiaryContainerDark = Color(0xFFFFDDB5)

// --- Dark: error ---
internal val ErrorDark = Color(0xFFFFB4AB)
internal val OnErrorDark = Color(0xFF690005)
internal val ErrorContainerDark = Color(0xFF93000A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

// --- Dark: neutral surfaces ---
internal val BackgroundDark = Color(0xFF1E100B)
internal val OnBackgroundDark = Color(0xFFF9DDD4)
internal val SurfaceDark = Color(0xFF1E100B)
internal val OnSurfaceDark = Color(0xFFF9DDD4)
internal val SurfaceVariantDark = Color(0xFF58423A)
internal val OnSurfaceVariantDark = Color(0xFFDFC0B6)

// --- Dark: utility ---
internal val OutlineDark = Color(0xFFA78B81)
internal val OutlineVariantDark = Color(0xFF58423A)
internal val ScrimDark = Color(0xFF000000)
internal val InverseSurfaceDark = Color(0xFFF9DDD4)
internal val InverseOnSurfaceDark = Color(0xFF3D2D27)
internal val InversePrimaryDark = Color(0xFFAA3700)
```

- [ ] **Step 2: Wire every role in `Theme.kt`**

Replace the `LightColors` initializer body (keep the `internal val LightColors = lightColorScheme(` line from Task 1):

```kotlin
internal val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
)
```

Replace the `DarkColors` initializer body:

```kotlin
internal val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = ScrimDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
)
```

The `FaceVerifyTheme` composable, `isSystemInDarkTheme()` default, `LocalSpacing` provider, and `AppTypography` reference are unchanged.

- [ ] **Step 3: Run the guard test and confirm it passes**

Run (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --tests "*.FaceVerifyThemeColorsTest"
```
Expected: PASS (4 tests).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/core/ui/theme/Color.kt app/src/main/java/com/mediplus/faceverify/core/ui/theme/Theme.kt
git commit -m "feat: recolor Material 3 theme to orange seeded from #EF5D22"
```

---

### Task 4: Full-gate verification

Prove the whole app still builds, all tests pass, and static analysis is not worse.

**Files:** none (verification only).

- [ ] **Step 1: Full unit suite**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all ~300 tests pass (a palette swap touches no logic).

- [ ] **Step 2: Lint + assemble**

```powershell
./gradlew lintDebug assembleDebug
```
Expected: `BUILD SUCCESSFUL`, no new Lint errors.

- [ ] **Step 3: detekt (only if the CLI is installed locally; otherwise rely on CI)**

```powershell
detekt --input app/src/main/java --config config/detekt/detekt.yml
```
Expected: `Color.kt` reports **zero** `MagicNumber` findings (the `@file:Suppress` clears
them), so the file no longer contributes to the pre-existing baseline. Confirm no *other*
file regressed. If the CLI is not installed, note that and defer to CI.

- [ ] **Step 4: No commit** — verification only. If anything failed, stop and debug before proceeding.

---

### Task 5: Emulator visual smoke (device-gated — run when an emulator/device is available)

Confirm the recolor reads correctly on screen. This is manual and requires a running emulator; if none is available, mark it as deferred (consistent with CLAUDE.md's device-gated items) and note it in the final report rather than claiming it passed.

**Files:** none.

- [ ] **Step 1: Install the debug build**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew installDebug
```

- [ ] **Step 2: Capture a screenshot (PowerShell — `exec-out` corrupts PNGs, per CLAUDE.md)**

```powershell
adb shell am start -n com.mediplus.faceverify/.MainActivity
adb shell screencap -p /sdcard/faceverify_theme.png
adb pull /sdcard/faceverify_theme.png "$env:USERPROFILE\Downloads\faceverify_theme.png"
```

- [ ] **Step 3: Eyeball the result**

Open the PNG. Confirm: the app bar / primary buttons render orange (`#AA3700` family, not blue),
button labels are legible (white on orange), and neutral backgrounds are warm off-white
(`#FFF8F6`). Spot-check dark mode if convenient (system dark setting). No commit.

---

## Notes for the implementer

- **Warmer neutrals are intended:** backgrounds move from cool `#FCFCFF` to warm `#FFF8F6`. Not a bug.
- **Error colors are unchanged by design:** the M3 error ramp is hue-independent, so `Error*` values match the previous palette. `SignInScreen`, `AddServiceScreen`, `StateViews`, and `AddServiceSummaryDrawer` (which read `colorScheme.error`) look the same.
- **Do not touch** `Type.kt`, `Spacing.kt`, any screen, the debug "FaceVerify Dev" launcher, or dynamic-color wiring (there is none — keep it that way).
