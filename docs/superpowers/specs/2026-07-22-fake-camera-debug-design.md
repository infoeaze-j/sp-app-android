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
  scenarios, a dev scenario picker, and the two missing UI states the fake makes
  reachable (no camera, capture failure).
- **Out of scope:**
  - Faking the camera **permission** grant — an OS-level decision the fake
    cannot intercept without a second seam. `PermissionDeniedState` still
    requires denying the real prompt.
  - **Reordering the consent gate.** Checking camera availability *before* the
    consent prompt would arguably be better (don't ask a patient to consent to a
    check the device cannot perform), but it changes release behaviour and
    existing test expectations for no dev-tooling benefit. `ConsentPrompt`
    remains the initial phase; availability is checked when the capture step is
    entered.
  - Changes to `FaceFramingAnalyzer` thresholds.
- **Release builds:** contain none of this code and behave exactly as today.

## Decisions (locked during brainstorming)

1. **Fake the whole camera**, not just the captured bytes — preview surface,
   framing guidance, and capture. Partial fakes still need working camera
   hardware, which defeats the purpose.
2. **A switching *factory*, not a switching decorator.** `SwitchingMemberCardReader`
   can delegate per call because every `MemberCardReader` method is `suspend`, so
   it reads `DevSettingsStore.current()` inside each one. `FaceCamera` cannot:
   `createPreviewView` and `bind` are synchronous. The choice is therefore made
   once, when the capture step is entered, by a `suspend FaceCameraFactory.create()`.
3. **A View-factory interface**, so `core/camera` gains no Compose dependency and
   the ViewModel holds no `View` references (this screen is LeakCanary-watched).
4. **Follows the existing master `fakeEnabled` toggle**, exactly like
   `SwitchingMemberCardReader`. No dedicated camera switch: the camera fake and
   the back-office fakes will almost always be used together. A per-subsystem
   override can be introduced later if *real camera + fake back office* turns out
   to be needed on a physical device — the factory is the natural place for it.
5. **No camera is a terminal halt.** Unlike the card scan, which falls back to
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
       debug: SwitchingFaceCameraFactory ──► FakeFaceCamera     (fakeEnabled ON)
                        │                └──► CameraXFaceCamera  (OFF → real CameraX)
       release:                              CameraXFaceCamera
```

### DI mechanism — source-set-specific `CameraModule`

Identical to the existing `NfcModule` / `RepositoryModule` split, for the same
reason (Hilt forbids two `@Binds` for one interface):

- **Add** `src/release/.../core/di/CameraModule.kt` — binds `RealFaceCameraFactory`.
- **Add** `src/debug/.../core/di/CameraModule.kt` — binds `SwitchingFaceCameraFactory`.

The camera is a **purely UI-layer concern**: the screen creates it, binds it to
its own lifecycle, releases it on dispose, and reports outcomes to the ViewModel
through callbacks. The ViewModel never calls it. So `FaceCameraFactory` is
resolved in the composable via a Hilt `@EntryPoint`:

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaceCameraEntryPoint { fun faceCameraFactory(): FaceCameraFactory }
```

retrieved with `EntryPointAccessors.fromApplication(context)`. This is a new
pattern for the codebase — which uses only `@AndroidEntryPoint` on its two
activities today — but it is Hilt's sanctioned mechanism for a
non-ViewModel-scoped dependency, it is about ten lines in one file, and it
leaves `FaceCheckViewModel`'s constructor unchanged, so no existing test needs
editing.

### Components

| Source set | File | Change |
|---|---|---|
| main | `core/camera/FaceCamera.kt` | new — interface, `CameraAvailability`, factory interface, `@EntryPoint` accessor |
| main | `core/camera/CameraXFaceCamera.kt` | new — `CameraController` folded in; `@Inject constructor(@ApplicationContext)`; owns the `PreviewView`, `FaceFramingAnalyzer`, and analysis executor. Also holds `RealFaceCameraFactory`, the one-line factory returning a fresh `CameraXFaceCamera` |
| main | `core/camera/CameraController.kt` | deleted |
| main | `ui/facecheck/FaceCheckScreen.kt` | resolve the factory; `AndroidView(factory = { camera.createPreviewView(it) })`; render the new halt; surface capture failure |
| main | `ui/facecheck/FaceCheckViewModel.kt` | `+ FacePhase.CameraUnavailableHalt`, `+ onCameraUnavailable()`, `+ onCaptureFailed()`. Constructor unchanged |
| main | `res/values/strings.xml` | `face_camera_unavailable_title/_body` |
| release | `core/di/CameraModule.kt` | new — binds `RealFaceCameraFactory` |
| debug | `core/di/CameraModule.kt` | new — binds `SwitchingFaceCameraFactory` |
| debug | `dev/camera/FakeFaceCamera.kt` | new |
| debug | `dev/camera/SwitchingFaceCameraFactory.kt` | new |
| debug | `dev/DevScenarios.kt` | `+ enum CameraScenario` |
| debug | `dev/DevSettings.kt` | `+ camera = SUCCESS`, 1 `DevPrefKeys` entry |
| debug | `dev/DevSettingsStore.kt` | `+ setCamera` |
| debug | `dev/ui/DevSettingsScreen.kt`, `DevSettingsViewModel.kt` | one new `ScenarioPicker` |
| debug | `dev/FakeData.kt` | `+ faceFrameBytes` |

`CameraScenario` mirrors `CardScenario` in intent: like it, and unlike the
back-office scenario enums, it fakes *device hardware* rather than a server
response, so it also covers a no-hardware state.

```kotlin
enum class CameraScenario { SUCCESS, NEVER_GOOD, CAPTURE_ERROR, NO_CAMERA_HARDWARE }
```

## State machine

`FacePhase` gains exactly one state, `CameraUnavailableHalt`. The initial phase
stays `ConsentPrompt`.

```
ConsentPrompt ──granted──> Capturing ──> Submitting ──> Verified
      │                        │
      │                        ├──isAvailable()==NO_CAMERA──> CameraUnavailableHalt
      │                        └──capture()==null──────────> Failed(canRetry = true)
      └──declined──> ConsentWithheldHalt
```

Availability resolves inside the capture step, screen-locally: the composable
holds `produceState<FaceCamera?>(null) { value = factory.create() }` and renders
the existing `LoadingState` until it resolves, then calls
`viewModel.onCameraUnavailable()` if the camera reports `NO_CAMERA`. No extra
ViewModel phase is needed for the resolving window.

`CameraUnavailableHalt` renders through the existing `TerminalMessage`
composable, with its assertive live region, alongside the consent-withheld and
discrepancy halts. `onCaptureFailed()` produces
`FacePhase.Failed(errorMapper.toUserMessage(AppError.Transient(TransientKind.UNKNOWN)), lockout, canRetry = true)`,
routing a failed capture through the existing `ErrorMapper` rather than new
strings — no `ViewModel` in this codebase imports `R`, and a capture failure is
just another transient error from the ViewModel's point of view. This closes
the silent dead-end at `FaceCheckScreen.kt:210`, where a failed capture is
currently dropped with no user-visible result.

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

## Trap

**`TransientFrame.clear()` zeroes its backing array in place**
(`TransientFrame.kt:23`). If the fake hands out `FakeData.faceFrameBytes`
directly, the first capture zeroes the shared constant and every later capture in
the process returns all-zero bytes — a retry would silently differ from the first
attempt. The fake must return `FakeData.faceFrameBytes.copyOf()`.

## Error handling & isolation

The fake produces only `FramingGuidance` values and a `TransientFrame?` — types
the screen already handles. The two genuinely new UI states
(`CameraUnavailableHalt`, capture-failure `Failed`) are states the **real**
camera can also reach on device; the fake is what makes them testable. Every dev
artefact is `src/debug`-only, plus the `src/release` `CameraModule`.

Because the camera fake follows the master `fakeEnabled` toggle, the fake camera
and the fake back office are always on or off together — synthetic frame bytes
can never reach a real `/face/verify`.

## Testing

All JVM-unit, no device required.

- **`FakeFaceCameraTest`** (`testDebug`) — one test per scenario over
  `isAvailable()` and `capture()`, plus an explicit test that two successive
  `capture()` calls return equal, non-zero bytes (see Trap).
- **`FaceCheckViewModelTest`** (`test`) — two new tests: `onCameraUnavailable()`
  reaches `CameraUnavailableHalt`, and `onCaptureFailed()` yields a retryable
  `Failed`. Existing tests are untouched: the constructor and the initial phase
  both stay as they are.
- **`DataStoreDevSettingsStoreTest` / `DevSettingsViewModelTest`** (`testDebug`)
  — round-trip the new `camera` setting, including the unknown-enum-name
  fallback `toEnumOr` already provides.
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
