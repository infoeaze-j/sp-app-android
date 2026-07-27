# Material 3 recolor — orange accent seeded from `#EF5D22`

**Date:** 2026-07-27
**Status:** Design approved, pending spec review
**Scope:** Two theme files + one new test. No behavior, navigation, or layout changes.

## Goal

Replace the app's clinical blue/teal palette with a warm-orange Material 3 palette
seeded from the brand color `#EF5D22`, keeping the app fully Material 3 and accessible.

## Context

The app is **already Material 3** — `core/ui/theme/Theme.kt` builds `MaterialTheme` from
`lightColorScheme`/`darkColorScheme`, and `Type.kt` uses M3 `Typography()`. There is **no
framework migration**; this change is a palette swap only. `Type.kt` and `Spacing.kt` are
untouched.

Screens read only a few roles directly (`primary`, `error`, `onSurfaceVariant`), but the M3
components (buttons, `TopAppBar`, cards, text fields) pull many more roles under the hood, so a
partial recolor would leave un-harmonized fallbacks. We therefore define the **full M3 role set**.

## Decisions (settled during brainstorming)

| Decision | Choice |
|---|---|
| Recolor scope | Full harmonized scheme (all roles, light + dark) |
| Dynamic color (Material You) | **Off** — fixed brand palette on every device (unchanged from today) |
| Accent usage | **Strict M3 tone** — `#EF5D22` is the seed; role tones are derived by the algorithm |
| Tonal style | **Vibrant** — a real M3 scheme that keeps saturation so the primary reads clearly orange |

`TonalSpot` (M3 default) was rejected as too muted (`#8F4C34`, brownish); `Fidelity` as too bold;
`Expressive` because it rotates the hue to blue.

## Palette (generated, not hand-picked)

Values are the output of Material's official `@material/material-color-utilities`
`SchemeVibrant`, seed `#EF5D22`, contrast level `0.0`. These are **locked** — the
implementation must reproduce them exactly, and a test guards them against drift.

### Light

```
primary              #AA3700    onPrimary              #FFFFFF
primaryContainer     #FFDBCF    onPrimaryContainer     #822800
secondary            #7E5537    onSecondary            #FFFFFF
secondaryContainer   #FFDCC5    onSecondaryContainer   #633E22
tertiary             #7E571D    onTertiary             #FFFFFF
tertiaryContainer    #FFDDB5    onTertiaryContainer    #633F05
error                #BA1A1A    onError                #FFFFFF
errorContainer       #FFDAD6    onErrorContainer       #93000A
background           #FFF8F6    onBackground           #271813
surface              #FFF8F6    onSurface              #271813
surfaceVariant       #FDDBD1    onSurfaceVariant       #58423A
outline              #8C7169    outlineVariant         #DFC0B6
scrim                #000000    inverseSurface         #3D2D27
inverseOnSurface     #FFEDE8    inversePrimary         #FFB59C
```

### Dark

```
primary              #FFB59C    onPrimary              #5C1A00
primaryContainer     #822800    onPrimaryContainer     #FFDBCF
secondary            #F1BC96    onSecondary            #49290E
secondaryContainer   #633E22    onSecondaryContainer   #FFDCC5
tertiary             #F1BD7A    onTertiary             #462B00
tertiaryContainer    #633F05    onTertiaryContainer    #FFDDB5
error                #FFB4AB    onError                #690005
errorContainer       #93000A    onErrorContainer       #FFDAD6
background           #1E100B    onBackground           #F9DDD4
surface              #1E100B    onSurface              #F9DDD4
surfaceVariant       #58423A    onSurfaceVariant       #DFC0B6
outline              #A78B81    outlineVariant         #58423A
scrim                #000000    inverseSurface         #F9DDD4
inverseOnSurface     #3D2D27    inversePrimary         #AA3700
```

Note: `error`/`onError`/`errorContainer`/`onErrorContainer` land on the same values already in
use — the M3 error ramp is hue-independent, so error styling is visually unchanged.

## Changes

### 1. `core/ui/theme/Color.kt`
Replace all 32 existing brand constants (16 light + 16 dark) with the full role set above
(56 constants: 28 light + 28 dark). Keep the existing idiom — `internal val Name = Color(0xFFRRGGBB)` — and the
light/dark split with a `Dark` suffix on the dark constants. Group with brief comments
(primary / secondary / tertiary / error / neutral surfaces / utility).

### 2. `core/ui/theme/Theme.kt`
Extend `lightColorScheme(...)` and `darkColorScheme(...)` to pass every newly defined role
(`secondaryContainer`, `onSecondaryContainer`, the full `tertiary` family, `outline`,
`outlineVariant`, `scrim`, `inverseSurface`, `inverseOnSurface`, `inversePrimary`). The
`@Composable FaceVerifyTheme` signature, the `isSystemInDarkTheme()` default, the
`LocalSpacing` provider, and the (absent) dynamic-color path are all unchanged.

### 3. New test: `app/src/test/java/.../core/ui/theme/FaceVerifyThemeColorsTest.kt`
Test-first, per the constitution. A JVM test (no Android runtime needed — `Color` is a plain
value) asserting:

1. **Palette lock** — the `LightColors`/`DarkColors` schemes expose the exact generated values
   for at least `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`, `secondary`,
   `tertiary`, `surface` in both schemes. Guards against accidental edits.
2. **Accessibility (the strict-M3 promise)** — a WCAG relative-luminance contrast helper in the
   test verifies `onPrimary`↔`primary`, `onPrimaryContainer`↔`primaryContainer`, and
   `onSurface`↔`surface` each clear **4.5:1** in both light and dark. (Generated values already
   measure 6.45–16.32:1, so this is a durable guard, not a tight fit.)
3. **No unset roles** — a smoke assertion that the primary family is non-default (distinct from a
   bare `lightColorScheme()`), proving the scheme is actually wired.

To make `LightColors`/`DarkColors` visible to the test without exposing them app-wide, mark them
`internal` (they are currently `private`) so the `testDebug`/`test` source set can read them via
the module's internal visibility. No public API is added.

## Testing & verification

- `./gradlew testDebugUnitTest --tests "*FaceVerifyThemeColorsTest"` — new test green.
- `./gradlew testDebugUnitTest` — full suite still green (~300 tests; a color swap should not
  affect any).
- `./gradlew lintDebug` and `./gradlew assembleDebug` — build + lint clean.
- detekt CLI over `app/src/main/java` — `Color.kt` already carries baseline magic-number
  findings; swapping hex-for-hex in the same idiom adds no new *category*. Confirm the weighted
  count does not increase versus the `main` baseline (48).
- Emulator smoke: `assembleDebug`, install, screenshot sign-in → face-check → add-service using
  the PowerShell `adb shell screencap` + `adb pull` workflow (CLAUDE.md — `exec-out` corrupts
  PNGs under PowerShell). Confirm buttons/app bar are orange and text is legible in light mode;
  spot-check dark mode.

## Out of scope / non-goals

- Typography, spacing, shapes, motion — unchanged.
- Dynamic color / Material You — deliberately not enabled.
- The debug **"FaceVerify Dev"** launcher icon and dev-settings UI — unaffected.
- Any screen, ViewModel, navigation, or string change.
- Re-baselining the pre-existing detekt findings on `Color.kt`.

## Risks

- **Palette drift if regenerated later.** Mitigated by the palette-lock test. If the palette is
  ever intentionally regenerated, update both `Color.kt` and the test together; the generation
  method (library + scheme + seed + contrast level) is recorded above.
- **Warmer neutral surfaces.** Backgrounds shift from cool `#FCFCFF` to warm `#FFF8F6`; this is
  intended harmonization, but worth confirming in the emulator smoke pass.
