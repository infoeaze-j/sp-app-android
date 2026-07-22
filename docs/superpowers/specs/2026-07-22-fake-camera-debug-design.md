# Design: Fake camera for debug builds (dev-only)

**Date:** 2026-07-22
**Status:** Approved, pending implementation plan
**Feature area:** developer tooling for `001-identity-verification-enrollment`

## Goal

Let the face-check step run end-to-end on a bare emulator with **no camera
hardware at all**, the same way `FakeMemberCardReader` already lets the member
card scan run with no NFC hardware. Today the face step is the last remaining
device gate in the debug journey: `2026-07-20-fake-back-office-design.md`
deliberately left "device sensors stay real", and the NFC half of that
limitation was lifted by the member-card work. This lifts the camera half.

## Scope

- **In scope:** a `FaceCamera` seam, a debug `FakeFaceCamera` covering four
  scenarios, a dedicated dev toggle and scenario picker, and the two missing UI
  states the fake makes reachable (no camera, capture failure).
- **Out of scope:** faking the camera **permission** grant (an OS-level decision
  the fake cannot intercept without a second seam — `PermissionDeniedState`
  still requires denying the real prompt); changes to `FaceFramingAnalyzer`
  thresholds; per-subsystem override toggles for NFC or the back office.
- **Release builds:** contain none of this code and behave exactly as today.

## Decisions (locked during brainstorming)

1. **Fake the whole camera**, not just the captured bytes — preview surface,
   framing guidance, and capture. Partial fakes still need working camera
   hardware, which defeats the purpose.
2. **A switching *factory*, not a switching decorator.** `SwitchingMemberCardReader`
   can delegate per call because every `MemberCardReader` method is `suspend`, so
   it reads `DevSettingsStore.current()` inside each one. `FaceCamera` cannot:
   `createPreviewView` and `bind` are synchronous. The choice is therefore made
   once, at screen entry, by a `suspend FaceCameraFactory.create()`.
3. **A View-factory interface**, so `core/camera` gains no Compose dependency and
   the ViewModel holds no `View` references (this screen is LeakCanary-watched).
4. **A dedicated `fakeCameraEnabled` toggle**, independent of the master
   `fakeEnabled`. This is a deliberate divergence from the NFC fake: the camera
   has never been exercised on a physical device, and the back-office contracts
   are still placeholders, so *real camera + fake back office* must be reachable.
5. **Default ON**, matching `DevSettings.fakeEnabled` — a fresh debug install
   runs the whole journey on a bare emulator.
6. **No camera is a terminal halt.** Unlike the card scan, which falls back to
   manual entry, the face check has no fallback: it *is* the verification.

## Architecture

### The seam

```kotlin
// core/camera/FaceCamera.kt  (src/main)
enum class CameraAvailability { AVAILABLE, NO_CAMERA }

interface FaceCamera {
    suspend fun isAvailable(): CameraAvailability
    fun createPreviewView(context: Context): View
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    )
    suspend fun capture(): TransientFrame?   // null = capture failed
    fun release()
}

interface FaceCameraFactory { suspend fun create(): FaceCamera }
```

Resolving the implementation once per screen entry, rather than per call, is
also more correct than the NFC decorator: the implementation cannot swap
mid-capture, and each entry gets a fresh instance, so the real implementation's
`imageCapture` state does not leak across screens the way a `@Singleton` would.

```
FaceCheckScreen → FaceCameraFactory
                        │
       debug: SwitchingFaceCameraFactory ──► FakeFaceCamera     (fakeCameraEnabled ON)
                        │                └──► CameraXFaceCamera  (OFF → real CameraX)
       release:                              CameraXFaceCamera
```

### DI mechanism — source-set-specific `CameraModule`

Identical to the existing `NfcModule` / `RepositoryModule` split, for the same
reason (Hilt forbids two `@Binds` for one interface):

- **Add** `src/release/.../core/di/CameraModule.kt` — binds `RealFaceCameraFactory`.
- **Add** `src/debug/.../core/di/CameraModule.kt` — binds `SwitchingFaceCameraFactory`.

`FaceCameraFactory` is injected into `FaceCheckViewModel` and exposed as a plain
property the screen reads. The project uses no `@EntryPoint` anywhere today —
only `@AndroidEntryPoint` on the two activities — and introducing that pattern
for a single dependency is not worth it. The ViewModel never touches a `View`:
it holds the *factory*, and the screen owns the `FaceCamera` instance in a
`remember` / `DisposableEffect` that calls `release()` on dispose.

### Components

| Source set | File | Change |
|---|---|---|
| main | `core/camera/FaceCamera.kt` | new — interface, `CameraAvailability`, factory |
| main | `core/camera/CameraXFaceCamera.kt` | new — `CameraController` folded in; `@Inject constructor(@ApplicationContext)`; owns the `PreviewView`, `FaceFramingAnalyzer`, and analysis executor. Also holds `RealFaceCameraFactory`, the one-line factory that returns a fresh `CameraXFaceCamera` |
| main | `core/camera/CameraController.kt` | deleted |
| main | `ui/facecheck/FaceCheckScreen.kt` | resolve the factory; `AndroidView(factory = { camera.createPreviewView(it) })`; render the new halt; surface capture failure |
| main | `ui/facecheck/FaceCheckViewModel.kt` | `+ FacePhase.CheckingCamera`, `+ FacePhase.CameraUnavailableHalt`, `+ onCameraAvailability()`, `+ onCaptureFailed()` |
| main | `res/values/strings.xml` | `face_camera_unavailable_title/_body`, `face_capture_failed_title/_body` |
| release | `core/di/CameraModule.kt` | new — binds `RealFaceCameraFactory` |
| debug | `core/di/CameraModule.kt` | new — binds `SwitchingFaceCameraFactory` |
| debug | `dev/camera/FakeFaceCamera.kt` | new |
| debug | `dev/camera/SwitchingFaceCameraFactory.kt` | new |
| debug | `dev/DevScenarios.kt` | `+ enum CameraScenario` |
| debug | `dev/DevSettings.kt` | `+ fakeCameraEnabled = true`, `+ camera = SUCCESS`, 2 `DevPrefKeys` |
| debug | `dev/DevSettingsStore.kt` | `+ setFakeCameraEnabled`, `+ setCamera` |
| debug | `dev/ui/DevSettingsScreen.kt`, `DevSettingsViewModel.kt` | new switch + `ScenarioPicker` |
| debug | `dev/FakeData.kt` | `+ faceFrameBytes` |

`CameraScenario` mirrors `CardScenario` in intent: like it, and unlike the
back-office scenario enums, it fakes *device hardware* rather than a server
response, so it also covers a no-hardware state.

```kotlin
enum class CameraScenario { SUCCESS, NEVER_GOOD, CAPTURE_ERROR, NO_CAMERA_HARDWARE }
```

## State machine

`FacePhase` gains two states, and the initial state changes from `ConsentPrompt`
to `CheckingCamera`, so an operator is never asked to take patient consent for a
check the device cannot perform.

```
CheckingCamera ──AVAILABLE──> ConsentPrompt ──> Capturing ──> Submitting ──> Verified
      │                             │                │
      │                             │                └──capture()==null──> Failed(canRetry=true)
      │                             └──declined──> ConsentWithheldHalt
      └──NO_CAMERA──> CameraUnavailableHalt
```

`CameraUnavailableHalt` renders through the existing `TerminalMessage`
composable, with its assertive live region, alongside the consent-withheld and
discrepancy halts. `onCaptureFailed()` produces
`FacePhase.Failed(UiMessage(face_capture_failed_title, face_capture_failed_body), lockout, canRetry = true)`,
closing the silent dead-end at `FaceCheckScreen.kt:210`, where a failed capture
is currently dropped with no user-visible result.

## Scenario behaviour

| `CameraScenario` | `isAvailable()` | guidance from `bind()` | `capture()` |
|---|---|---|---|
| `SUCCESS` | `AVAILABLE` | `NO_FACE` → *(latency)* → `GOOD` | synthetic frame |
| `NEVER_GOOD` | `AVAILABLE` | `NO_FACE` → *(latency)* → `FACE_TOO_SMALL`, parks | synthetic frame (unreachable — the button stays disabled) |
| `CAPTURE_ERROR` | `AVAILABLE` | `NO_FACE` → *(latency)* → `GOOD` | `null` |
| `NO_CAMERA_HARDWARE` | `NO_CAMERA` | never bound | never called |

Guidance is emitted from a coroutine on the lifecycle scope, spaced by
`DevSettings.latencyMillis`, so the screen moves through its states exactly as
on device. `release()` cancels that job.

`FakeFaceCamera.createPreviewView` returns a plain `View` with a flat background
and the existing `face_camera_preview_desc` content description. It needs no
text of its own: the screen already renders the live guidance string directly
beneath the preview (`FaceCheckScreen.kt:197`).

## Traps

1. **`TransientFrame.clear()` zeroes its backing array in place**
   (`TransientFrame.kt:23`). If the fake hands out `FakeData.faceFrameBytes`
   directly, the first capture zeroes the shared constant and every later capture
   in the process returns all-zero bytes — a retry would silently differ from the
   first attempt. The fake must return `FakeData.faceFrameBytes.copyOf()`.
2. **Fake camera ON with fake back office OFF** sends synthetic bytes to the real
   `/face/verify`. That combination is legitimate for exercising transport and
   error mapping, but the decision it returns is meaningless. Document it on the
   toggle; do not guard against it.

## Error handling & isolation

The fake produces only `FramingGuidance` values and a `TransientFrame?` — types
the screen already handles. The two genuinely new UI states
(`CameraUnavailableHalt`, capture-failure `Failed`) are states the **real**
camera can also reach on device; the fake is what makes them testable. Every dev
artefact is `src/debug`-only, plus the `src/release` `CameraModule`.

## Testing

All JVM-unit, no device required.

- **`FakeFaceCameraTest`** (`testDebug`) — one test per scenario over
  `isAvailable()` and `capture()`, plus an explicit test that two successive
  `capture()` calls return equal, non-zero bytes (trap 1).
- **`FaceCheckViewModelTest`** (`test`) — `CheckingCamera` is the initial phase;
  `NO_CAMERA` reaches `CameraUnavailableHalt`; `onCaptureFailed()` yields a
  retryable `Failed`. Existing tests need a 4th constructor argument
  (`mockk<FaceCameraFactory>()`), and those that assume `ConsentPrompt` is
  initial need an `onCameraAvailability(AVAILABLE)` call first.
- **`DataStoreDevSettingsStoreTest` / `DevSettingsViewModelTest`** (`testDebug`)
  — round-trip the two new settings, including the unknown-enum-name fallback
  `toEnumOr` already provides.
- **Build gate:** the source-set `CameraModule` split must not break Hilt
  resolution. Verify `assembleDebug`, `assembleRelease`, and the full JVM unit
  suite (154 tests green today) before building the dev UI.

`FaceFramingAnalyzer` and `CameraXFaceCamera` remain device-only and untested
here, exactly as now. The fake does not change that; it means the *screen* no
longer depends on them.

## Build-toolchain note

AGP 9 / Gradle 9.4 / Kotlin 2.3.10 constraints apply (see the
`build-toolchain-constraints` memory): no `kotlin.android` plugin, Hilt ≥ 2.60,
compileSdk 37, Android Lint is the in-build static-analysis gate
(`abortOnError = true`). New debug code must pass Lint. Build with the Android
Studio JBR (`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`).
