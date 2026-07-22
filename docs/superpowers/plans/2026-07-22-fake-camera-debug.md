# Fake Camera for Debug Builds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the face-check step run end-to-end on a bare emulator with no camera hardware, the way `FakeMemberCardReader` already does for NFC.

**Architecture:** Extract a `FaceCamera` interface (preview `View` factory + framing guidance + capture) resolved once per screen entry by a `suspend FaceCameraFactory`. Release binds the real CameraX implementation; debug binds a switching factory that returns `FakeFaceCamera` when the existing master `fakeEnabled` dev toggle is on. The screen resolves the factory through a Hilt `@EntryPoint`, so `FaceCheckViewModel`'s constructor is unchanged.

**Tech Stack:** Kotlin 2.3.10, Jetpack Compose, Hilt 2.60+, CameraX 1.5.0, ML Kit face detection, DataStore Preferences, JUnit4 + MockK + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-22-fake-camera-debug-design.md` (commit `31940f6`)

**Branch:** `feat/fake-camera-debug`

## Global Constraints

- Build with the Android Studio JBR. Every Gradle command in this plan assumes `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` has been set in the shell first.
- AGP 9 / Gradle 9.4 / Kotlin 2.3.10: no `kotlin.android` plugin, Hilt ≥ 2.60, compileSdk 37.
- Android Lint is the in-build static-analysis gate with `abortOnError = true`. New code must pass Lint.
- Hilt forbids two `@Binds` for the same interface — per-variant swaps go in source-set-specific modules (`src/debug` / `src/release`), never a runtime `if`.
- All user-facing text lives in `res/values/strings.xml` and reaches the UI only as resource IDs inside `UiMessage` (Principle III / FR-029). No `ViewModel` in this codebase imports `R`; keep it that way.
- Every dev-only artefact lives under `src/debug/java/com/mediplus/faceverify/dev/`, except the two variant `CameraModule`s in `core/di`.
- Release builds must contain no `dev/` code and behave exactly as today.
- Captured frame bytes live in memory only and are zeroed via `TransientFrame.clear()` (FR-017). Never log, persist, or copy them elsewhere.
- The JVM unit suite is **154 tests, 0 failures** before this work. It must stay green after every task.

---

### Task 1: Extract the `FaceCamera` seam

Pure refactor. `CameraController` becomes `CameraXFaceCamera` behind an interface, and the screen gets it from Hilt instead of constructing it. No behaviour change — the existing test suite plus both assembles are the gate.

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/core/camera/FaceCamera.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/camera/CameraXFaceCamera.kt`
- Delete: `app/src/main/java/com/mediplus/faceverify/core/camera/CameraController.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/di/CameraModule.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/facecheck/FaceCheckScreen.kt:168-216`

**Interfaces:**
- Consumes: `TransientFrame(bytes: ByteArray)`, `FramingGuidance` (both already in `core/camera`).
- Produces:
  - `enum class CameraAvailability { AVAILABLE, NO_CAMERA }`
  - `interface FaceCamera` with `suspend fun isAvailable(): CameraAvailability`, `fun createPreviewView(context: Context): View`, `fun bind(lifecycleOwner: LifecycleOwner, previewView: View, onGuidance: (FramingGuidance) -> Unit)`, `suspend fun capture(): TransientFrame?`, `fun release()`
  - `interface FaceCameraFactory { suspend fun create(): FaceCamera }`
  - `interface FaceCameraEntryPoint { fun faceCameraFactory(): FaceCameraFactory }`
  - `class CameraXFaceCamera @Inject constructor(context: Context) : FaceCamera`
  - `class RealFaceCameraFactory @Inject constructor(context: Context) : FaceCameraFactory`

- [ ] **Step 1: Create the interface file**

Create `app/src/main/java/com/mediplus/faceverify/core/camera/FaceCamera.kt`:

```kotlin
package com.mediplus.faceverify.core.camera

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Whether this device can run the live face check at all. */
enum class CameraAvailability { AVAILABLE, NO_CAMERA }

/**
 * The camera the face-check screen drives (FR-016, FR-017). The implementation owns CameraX setup
 * and teardown, so no `androidx.camera` type ever reaches the ViewModel — the same containment
 * [com.mediplus.faceverify.core.nfc.MemberCardReader] gives the NFC path.
 *
 * The preview surface is created by the camera itself rather than the screen, so an alternative
 * implementation (the debug fake) can supply a placeholder that needs no hardware.
 */
interface FaceCamera {

    /** Whether a usable camera exists. Checked before binding; NO_CAMERA halts the step. */
    suspend fun isAvailable(): CameraAvailability

    /** The view to place in the layout. Must be passed back to [bind] unchanged. */
    fun createPreviewView(context: Context): View

    /** Start the preview and the framing analysis, reporting guidance until [release]. */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    )

    /**
     * Capture one frame. The bytes live only in the returned [TransientFrame] (FR-017).
     * Returns null when the capture fails — the caller must surface that, not ignore it.
     */
    suspend fun capture(): TransientFrame?

    /** Stop analysis and drop hardware handles. Idempotent. */
    fun release()
}

/**
 * Resolves which [FaceCamera] to use, once per screen entry.
 *
 * A factory rather than a switching decorator (unlike [com.mediplus.faceverify.core.nfc.MemberCardReader]):
 * [FaceCamera.createPreviewView] and [FaceCamera.bind] are synchronous, so they cannot read the
 * suspending dev-settings store per call. Deciding once also means the implementation cannot swap
 * mid-capture, and each entry gets a fresh instance with no leftover CameraX state.
 */
interface FaceCameraFactory {
    suspend fun create(): FaceCamera
}

/**
 * The camera is a UI-layer concern the ViewModel never calls, so the screen resolves the factory
 * directly rather than having it injected into [com.mediplus.faceverify.ui.facecheck.FaceCheckViewModel].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FaceCameraEntryPoint {
    fun faceCameraFactory(): FaceCameraFactory
}
```

- [ ] **Step 2: Create the real implementation**

Create `app/src/main/java/com/mediplus/faceverify/core/camera/CameraXFaceCamera.kt`. This folds in everything `CameraController` did, converts the `takePicture` callback into a suspend function, and takes ownership of the analysis executor:

```kotlin
package com.mediplus.faceverify.core.camera

import android.content.Context
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real CameraX camera: preview, framing analysis, and single-frame capture bound to a
 * lifecycle so the hardware is released automatically. Every [ImageProxy] is closed promptly to
 * keep memory bounded (Principle IV).
 */
class CameraXFaceCamera @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FaceCamera {

    private var imageCapture: ImageCapture? = null
    private var analysisExecutor: ExecutorService? = null

    override suspend fun isAvailable(): CameraAvailability =
        runCatching { cameraProvider().hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
            .getOrDefault(false)
            .let { if (it) CameraAvailability.AVAILABLE else CameraAvailability.NO_CAMERA }

    override fun createPreviewView(context: Context): View = PreviewView(context)

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    ) {
        val surface = previewView as PreviewView
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(surface.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(executor, FaceFramingAnalyzer(onGuidance)) }
            val capture = ImageCapture.Builder().build()
            imageCapture = capture

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis,
                capture,
            )
        }, ContextCompat.getMainExecutor(context))
    }

    override suspend fun capture(): TransientFrame? {
        val capture = imageCapture ?: return null
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes = image.toBytes()
                        image.close()
                        cont.resume(TransientFrame(bytes))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(null)
                    }
                },
            )
        }
    }

    override fun release() {
        analysisExecutor?.shutdown()
        analysisExecutor = null
        imageCapture = null
    }

    private suspend fun cameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }
}

/** Release builds always get the real camera. */
class RealFaceCameraFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FaceCameraFactory {
    override suspend fun create(): FaceCamera = CameraXFaceCamera(context)
}

private fun ImageProxy.toBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
```

- [ ] **Step 3: Delete the old controller**

```powershell
Remove-Item app\src\main\java\com\mediplus\faceverify\core\camera\CameraController.kt
```

- [ ] **Step 4: Add the DI module**

One module in `src/main`, used by both variants. Task 5 replaces it with the
per-variant pair once there is something to vary — until then a variant split
would mean two byte-identical files.

Create `app/src/main/java/com/mediplus/faceverify/core/di/CameraModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the real CameraX camera factory for every variant. Task 5 moves this into the release and
 * debug source sets (like NfcModule) once debug has an emulated camera to substitute.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: RealFaceCameraFactory): FaceCameraFactory
}
```

- [ ] **Step 5: Rewrite the screen's camera composable**

In `app/src/main/java/com/mediplus/faceverify/ui/facecheck/FaceCheckScreen.kt`, replace the whole `CameraCapture` composable (lines 168-216) with:

```kotlin
@Composable
private fun CameraCapture(
    phase: FacePhase.Capturing,
    onGuidance: (FramingGuidance) -> Unit,
    onCapture: (com.mediplus.faceverify.core.camera.TransientFrame) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val factory = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, FaceCameraEntryPoint::class.java)
            .faceCameraFactory()
    }
    val camera = produceState<FaceCamera?>(initialValue = null, factory) {
        value = factory.create()
    }.value

    if (camera == null) {
        LoadingState(modifier = modifier)
        return
    }

    val previewView = remember(camera) { camera.createPreviewView(context) }

    DisposableEffect(camera, previewView) {
        camera.bind(lifecycleOwner, previewView, onGuidance)
        onDispose { camera.release() }
    }

    Column(modifier = modifier.fillMaxSize().padding(spacing.md)) {
        val previewDescription = stringResource(R.string.face_camera_preview_desc)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = previewDescription },
            )
        }
        Text(
            text = stringResource(phase.guidance.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(
            onClick = { scope.launch { camera.capture()?.let(onCapture) } },
            enabled = phase.canCapture,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.face_capture_button)) }
    }
}
```

- [ ] **Step 6: Fix the imports**

In the same file, **remove** these imports:

```kotlin
import androidx.camera.view.PreviewView
import com.mediplus.faceverify.core.camera.CameraController
import com.mediplus.faceverify.core.camera.FaceFramingAnalyzer
import java.util.concurrent.Executors
```

and **add**:

```kotlin
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import com.mediplus.faceverify.core.camera.FaceCamera
import com.mediplus.faceverify.core.camera.FaceCameraEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
```

`import androidx.core.content.ContextCompat` stays — `hasCameraPermission()` at the bottom of the file still uses it.

- [ ] **Step 7: Verify both variants build**

Run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug assembleRelease
```

Expected: `BUILD SUCCESSFUL`. A Hilt error naming `FaceCameraFactory` means a variant `CameraModule` is missing or misnamed.

- [ ] **Step 8: Verify the existing suite is still green**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 154 tests, 0 failures. Nothing should have changed — no test references `CameraController`.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/mediplus/faceverify/core/camera/ app/src/main/java/com/mediplus/faceverify/core/di/CameraModule.kt app/src/main/java/com/mediplus/faceverify/ui/facecheck/FaceCheckScreen.kt
git commit -m "refactor: put the face camera behind a FaceCamera seam"
```

---

### Task 2: Camera-unavailable halt and visible capture failure

Adds the two UI states the fake will exercise. Both are states the **real** camera can also reach on device — `FaceCheckScreen.kt` currently drops a failed capture silently.

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/facecheck/FaceCheckViewModel.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/facecheck/FaceCheckScreen.kt`
- Modify: `app/src/main/res/values/strings.xml:77`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/facecheck/FaceCheckViewModelTest.kt`

**Interfaces:**
- Consumes: `FaceCamera.isAvailable()`, `CameraAvailability` (Task 1).
- Produces: `FacePhase.CameraUnavailableHalt` (a `data object`), `FaceCheckViewModel.onCameraUnavailable()`, `FaceCheckViewModel.onCaptureFailed()`. The ViewModel constructor is unchanged.

- [ ] **Step 1: Write the failing tests**

Append these two tests to `app/src/test/java/com/mediplus/faceverify/ui/facecheck/FaceCheckViewModelTest.kt`, before the closing brace:

```kotlin
    @Test
    fun `an unavailable camera halts the step`() {
        vm.onConsent(true)
        vm.onCameraUnavailable()
        assertEquals(FacePhase.CameraUnavailableHalt, vm.uiState.value.phase)
    }

    @Test
    fun `a failed capture is surfaced and retryable`() {
        vm.onConsent(true)
        vm.onCaptureFailed()
        val phase = vm.uiState.value.phase as FacePhase.Failed
        assertTrue(phase.canRetry)
    }
```

- [ ] **Step 2: Run them to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.ui.facecheck.FaceCheckViewModelTest"
```

Expected: compilation failure — `unresolved reference: onCameraUnavailable` and `unresolved reference: CameraUnavailableHalt`.

- [ ] **Step 3: Add the phase and the two handlers**

In `FaceCheckViewModel.kt`, add to the `FacePhase` sealed interface, after `DiscrepancyHalt`:

```kotlin
    data object CameraUnavailableHalt : FacePhase
```

Add these two functions to the class, after `onGuidance`:

```kotlin
    /** No usable camera on this device. Terminal: unlike the card scan, there is no fallback. */
    fun onCameraUnavailable() {
        _uiState.value = FaceCheckUiState(FacePhase.CameraUnavailableHalt)
    }

    /**
     * The capture itself failed before anything was submitted. Mapped as a transient device failure
     * so the operator sees the generic retryable message rather than a dead button.
     */
    fun onCaptureFailed() {
        _uiState.value = FaceCheckUiState(
            FacePhase.Failed(
                message = errorMapper.toUserMessage(AppError.Transient(TransientKind.UNKNOWN)),
                lockout = lockout,
                canRetry = true,
            ),
        )
    }
```

Add the import (`AppError` is already imported):

```kotlin
import com.mediplus.faceverify.core.result.TransientKind
```

- [ ] **Step 4: Run the tests to verify they pass**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.ui.facecheck.FaceCheckViewModelTest"
```

Expected: PASS, 9 tests. The build will still fail Kotlin's exhaustiveness check in `FaceCheckScreen.kt` — that is Step 5.

- [ ] **Step 5: Add the strings**

In `app/src/main/res/values/strings.xml`, immediately after the `face_camera_preview_desc` line:

```xml
    <string name="face_camera_unavailable_title">No camera found</string>
    <string name="face_camera_unavailable_body">This device has no usable camera, so the face check cannot be completed.</string>
```

- [ ] **Step 6: Render the halt and wire the two callbacks**

In `FaceCheckScreen.kt`, add the new branch to the `when` in `FaceCheckScreen`, after the `DiscrepancyHalt` branch:

```kotlin
        FacePhase.CameraUnavailableHalt -> TerminalMessage(
            R.string.face_camera_unavailable_title,
            R.string.face_camera_unavailable_body,
            modifier,
        )
```

Thread two new callbacks from `FaceCheckRoute` down. In `FaceCheckRoute`, add to the `FaceCheckScreen(...)` call:

```kotlin
        onCaptureFailed = viewModel::onCaptureFailed,
        onCameraUnavailable = viewModel::onCameraUnavailable,
```

Add the matching parameters to `FaceCheckScreen` (after `onCapture`):

```kotlin
    onCaptureFailed: () -> Unit,
    onCameraUnavailable: () -> Unit,
```

and pass them through its `Capturing` branch:

```kotlin
        is FacePhase.Capturing -> CaptureContent(phase, onGuidance, onCapture, onCaptureFailed, onCameraUnavailable, modifier)
```

Add the same two parameters to `CaptureContent` (after `onCapture`) and forward them:

```kotlin
    CameraCapture(phase, onGuidance, onCapture, onCaptureFailed, onCameraUnavailable, modifier)
```

- [ ] **Step 7: Use them in `CameraCapture`**

In `CameraCapture`, add the two parameters after `onCapture`:

```kotlin
    onCaptureFailed: () -> Unit,
    onCameraUnavailable: () -> Unit,
```

Replace the `produceState` block and the `if (camera == null)` guard with an availability-aware version:

```kotlin
    val session = produceState<Pair<FaceCamera, CameraAvailability>?>(initialValue = null, factory) {
        val created = factory.create()
        value = created to created.isAvailable()
    }.value

    if (session == null) {
        LoadingState(modifier = modifier)
        return
    }

    val (camera, availability) = session

    DisposableEffect(camera) {
        onDispose { camera.release() }
    }

    if (availability == CameraAvailability.NO_CAMERA) {
        LaunchedEffect(camera) { onCameraUnavailable() }
        LoadingState(modifier = modifier)
        return
    }
```

Then change the binding effect so it no longer owns release (the effect above does):

```kotlin
    DisposableEffect(camera, previewView) {
        camera.bind(lifecycleOwner, previewView, onGuidance)
        onDispose { }
    }
```

And make the capture button report failure:

```kotlin
        Button(
            onClick = {
                scope.launch {
                    val frame = camera.capture()
                    if (frame != null) onCapture(frame) else onCaptureFailed()
                }
            },
            enabled = phase.canCapture,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.face_capture_button)) }
```

Add the imports:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import com.mediplus.faceverify.core.camera.CameraAvailability
```

`FaceCheckScreen.kt` currently calls `androidx.compose.runtime.LaunchedEffect` fully-qualified in two places; leave those as they are rather than widening this diff.

- [ ] **Step 8: Verify the whole suite and both builds**

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

Expected: `BUILD SUCCESSFUL`, 156 tests, 0 failures.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/mediplus/faceverify/ui/facecheck/ app/src/main/res/values/strings.xml app/src/test/java/com/mediplus/faceverify/ui/facecheck/FaceCheckViewModelTest.kt
git commit -m "feat: halt on no camera and surface capture failures"
```

---

### Task 3: `CameraScenario` and its dev-settings plumbing

Adds the persisted setting and its picker. No behaviour change yet — nothing reads the value until Task 4.

**Files:**
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`
- Modify: `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsViewModelTest.kt`

**Interfaces:**
- Produces: `enum class CameraScenario { SUCCESS, NEVER_GOOD, CAPTURE_ERROR, NO_CAMERA_HARDWARE }`, `DevSettings.camera: CameraScenario` (default `SUCCESS`), `DevSettingsStore.setCamera(scenario: CameraScenario)`, `DevPrefKeys.CAMERA`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsViewModelTest.kt`, before the closing brace:

```kotlin
    @Test
    fun `setCamera persists to the store`() = runTest {
        val store = TestDevSettingsStore()
        val vm = DevSettingsViewModel(store, InMemorySessionManager())

        vm.setCamera(CameraScenario.NO_CAMERA_HARDWARE)
        advanceUntilIdle()

        assertEquals(CameraScenario.NO_CAMERA_HARDWARE, store.current().camera)
    }
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsViewModelTest"
```

Expected: compilation failure — `unresolved reference: CameraScenario`.

- [ ] **Step 3: Add the enum**

Append to `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`:

```kotlin
/**
 * The emulated camera. Like [CardScenario] and unlike the back-office scenarios, this fakes *device
 * hardware* rather than a server response, so it also covers the no-hardware state.
 */
enum class CameraScenario { SUCCESS, NEVER_GOOD, CAPTURE_ERROR, NO_CAMERA_HARDWARE }
```

- [ ] **Step 4: Add the setting and its key**

In `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`, add the field to the `DevSettings` data class after `card`:

```kotlin
    val camera: CameraScenario = CameraScenario.SUCCESS,
```

Add the key to `DevPrefKeys` after `CARD`:

```kotlin
    val CAMERA = stringPreferencesKey("dev_scenario_camera")
```

Add the mapping in `Preferences.toDevSettings()` after the `card` line:

```kotlin
        camera = this[DevPrefKeys.CAMERA].toEnumOr(defaults.camera),
```

- [ ] **Step 5: Add the store method**

In `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`, add to the `DevSettingsStore` interface after `setCard`:

```kotlin
    suspend fun setCamera(scenario: CameraScenario)
```

and to `DataStoreDevSettingsStore` after `setCard`:

```kotlin
    override suspend fun setCamera(scenario: CameraScenario) =
        edit { it[DevPrefKeys.CAMERA] = scenario.name }
```

In `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`, add after `setCard`:

```kotlin
    override suspend fun setCamera(scenario: CameraScenario) { state.value = state.value.copy(camera = scenario) }
```

- [ ] **Step 6: Add the ViewModel method**

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`, add after `setCard`:

```kotlin
    fun setCamera(scenario: CameraScenario) = launchEdit { store.setCamera(scenario) }
```

and the import:

```kotlin
import com.mediplus.faceverify.dev.CameraScenario
```

- [ ] **Step 7: Add the picker**

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`, add the parameter after `onCard`:

```kotlin
    onCamera: (CameraScenario) -> Unit,
```

Add the picker immediately after the card one:

```kotlin
        ScenarioPicker("Camera (emulated)", CameraScenario.entries, settings.camera, onCamera)
```

and the import:

```kotlin
import com.mediplus.faceverify.dev.CameraScenario
```

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`, add after `onCard = vm::setCard,`:

```kotlin
                        onCamera = vm::setCamera,
```

- [ ] **Step 8: Run the test to verify it passes**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsViewModelTest"
```

Expected: PASS, 3 tests.

- [ ] **Step 9: Verify the whole suite**

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 157 tests, 0 failures. `DataStoreDevSettingsStoreTest` asserts `DevSettings()` round-trips, which covers the new default and the `toEnumOr` fallback for an unknown persisted name.

- [ ] **Step 10: Commit**

```powershell
git add app/src/debug/java/com/mediplus/faceverify/dev/ app/src/testDebug/java/com/mediplus/faceverify/dev/
git commit -m "feat(dev): add the CameraScenario dev setting"
```

---

### Task 4: `FakeFaceCamera`

The fake itself, fully unit-tested. Not yet wired — Task 5 does that.

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/camera/FakeFaceCamera.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeFaceCameraTest.kt`

**Interfaces:**
- Consumes: `FaceCamera`, `CameraAvailability`, `FramingGuidance`, `TransientFrame` (Task 1); `CameraScenario`, `DevSettingsStore` (Task 3).
- Produces: `class FakeFaceCamera @Inject constructor(store: DevSettingsStore) : FaceCamera`, `FakeData.faceFrameBytes: ByteArray`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeFaceCameraTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.camera.CameraAvailability
import com.mediplus.faceverify.dev.camera.FakeFaceCamera
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FakeFaceCameraTest {

    private fun camera(scenario: CameraScenario) =
        FakeFaceCamera(TestDevSettingsStore(DevSettings(camera = scenario, latencyMillis = 0L)))

    @Test
    fun `success reports an available camera`() = runTest {
        assertEquals(CameraAvailability.AVAILABLE, camera(CameraScenario.SUCCESS).isAvailable())
    }

    @Test
    fun `no camera hardware reports unavailable`() = runTest {
        assertEquals(
            CameraAvailability.NO_CAMERA,
            camera(CameraScenario.NO_CAMERA_HARDWARE).isAvailable(),
        )
    }

    @Test
    fun `success captures a non-empty frame`() = runTest {
        val frame = camera(CameraScenario.SUCCESS).capture()

        assertNotNull(frame)
        assertFalse(frame!!.isCleared)
    }

    @Test
    fun `capture error returns no frame`() = runTest {
        assertNull(camera(CameraScenario.CAPTURE_ERROR).capture())
    }

    /**
     * TransientFrame.clear() zeroes its array in place. If the fake handed out the shared constant,
     * clearing the first frame would blank every later capture in the process.
     */
    @Test
    fun `each capture returns independent bytes`() = runTest {
        val cam = camera(CameraScenario.SUCCESS)
        val expected = FakeData.faceFrameBytes.copyOf()

        val first = cam.capture()!!
        first.clear()
        val second = cam.capture()!!

        assertArrayEquals(expected, FakeData.faceFrameBytes)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(expected),
            second.asBase64(),
        )
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeFaceCameraTest"
```

Expected: compilation failure — `unresolved reference: FakeFaceCamera`.

- [ ] **Step 3: Add the canned frame bytes**

In `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`, add inside the `FakeData` object, after `memberNumber`:

```kotlin
    /**
     * The bytes the emulated capture returns. Content is irrelevant — [FakeFaceRepository] never
     * inspects it — but it must be non-empty and non-zero so a cleared frame is distinguishable.
     * Hand out `.copyOf()`: TransientFrame.clear() zeroes its array in place.
     */
    val faceFrameBytes: ByteArray = ByteArray(64) { (it + 1).toByte() }
```

- [ ] **Step 4: Write the fake**

Create `app/src/debug/java/com/mediplus/faceverify/dev/camera/FakeFaceCamera.kt`:

```kotlin
package com.mediplus.faceverify.dev.camera

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mediplus.faceverify.core.camera.CameraAvailability
import com.mediplus.faceverify.core.camera.FaceCamera
import com.mediplus.faceverify.core.camera.FramingGuidance
import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.dev.CameraScenario
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Emulated camera: lets the whole face-check step run on an emulator or a camera-less device.
 * The preview is a flat placeholder and the framing guidance is scripted, so the screen still moves
 * through no-face → good → capture exactly as on device.
 *
 * It never renders text of its own: the screen already shows the live guidance string directly
 * beneath the preview.
 */
class FakeFaceCamera @Inject constructor(
    private val store: DevSettingsStore,
) : FaceCamera {

    private var guidanceJob: Job? = null

    override suspend fun isAvailable(): CameraAvailability = when (store.current().camera) {
        CameraScenario.NO_CAMERA_HARDWARE -> CameraAvailability.NO_CAMERA
        else -> CameraAvailability.AVAILABLE
    }

    override fun createPreviewView(context: Context): View =
        View(context).apply { setBackgroundColor(PLACEHOLDER_COLOR) }

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
        onGuidance: (FramingGuidance) -> Unit,
    ) {
        guidanceJob?.cancel()
        guidanceJob = lifecycleOwner.lifecycleScope.launch {
            val settings = store.current()
            onGuidance(FramingGuidance.NO_FACE) // operator is still positioning the patient
            delay(settings.latencyMillis)
            onGuidance(
                when (settings.camera) {
                    CameraScenario.NEVER_GOOD -> FramingGuidance.FACE_TOO_SMALL
                    else -> FramingGuidance.GOOD
                },
            )
        }
    }

    override suspend fun capture(): TransientFrame? {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.camera) {
            CameraScenario.CAPTURE_ERROR -> null
            // copyOf: TransientFrame.clear() zeroes the array, which would blank the shared constant.
            else -> TransientFrame(FakeData.faceFrameBytes.copyOf())
        }
    }

    override fun release() {
        guidanceJob?.cancel()
        guidanceJob = null
    }

    private companion object {
        const val PLACEHOLDER_COLOR = Color.DKGRAY
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeFaceCameraTest"
```

Expected: PASS, 5 tests. `bind` and `createPreviewView` are deliberately untested here — both need real Android framework objects, and `testOptions.unitTests.isReturnDefaultValues = true` would make such a test assert nothing.

- [ ] **Step 6: Commit**

```powershell
git add app/src/debug/java/com/mediplus/faceverify/dev/camera/ app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt app/src/testDebug/java/com/mediplus/faceverify/dev/FakeFaceCameraTest.kt
git commit -m "feat(dev): add FakeFaceCamera"
```

---

### Task 5: Wire the fake behind the master toggle

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/camera/SwitchingFaceCameraFactory.kt`
- Delete: `app/src/main/java/com/mediplus/faceverify/core/di/CameraModule.kt`
- Create: `app/src/release/java/com/mediplus/faceverify/core/di/CameraModule.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/core/di/CameraModule.kt`

**Interfaces:**
- Consumes: `FaceCameraFactory`, `RealFaceCameraFactory` (Task 1); `FakeFaceCamera` (Task 4); `DevSettingsStore` (Task 3).
- Produces: `class SwitchingFaceCameraFactory @Inject constructor(...) : FaceCameraFactory`.

- [ ] **Step 1: Write the switching factory**

Create `app/src/debug/java/com/mediplus/faceverify/dev/camera/SwitchingFaceCameraFactory.kt`:

```kotlin
package com.mediplus.faceverify.dev.camera

import com.mediplus.faceverify.core.camera.FaceCamera
import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject
import javax.inject.Provider

/**
 * Debug-only router: emulate the camera when the master fake toggle is on, else use real CameraX.
 *
 * Follows `fakeEnabled` rather than a dedicated switch — the camera fake and the fake back office
 * are used together, so synthetic frame bytes can never reach a real /face/verify. If real camera
 * plus fake back office is ever needed on a device, add the override here.
 */
class SwitchingFaceCameraFactory @Inject constructor(
    private val real: RealFaceCameraFactory,
    private val fake: Provider<FakeFaceCamera>,
    private val store: DevSettingsStore,
) : FaceCameraFactory {

    override suspend fun create(): FaceCamera =
        if (store.current().fakeEnabled) fake.get() else real.create()
}
```

`Provider<FakeFaceCamera>` rather than a direct injection: `create()` must hand back a fresh instance per screen entry, matching what `RealFaceCameraFactory` does.

- [ ] **Step 2: Split the DI module per variant**

Delete the single shared module — the two variants now bind different factories:

```powershell
Remove-Item app\src\main\java\com\mediplus\faceverify\core\di\CameraModule.kt
```

Create `app/src/release/java/com/mediplus/faceverify/core/di/CameraModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release: binds the real CameraX camera factory. Lives in the variant source set (like NfcModule)
 * because debug substitutes a switchable emulated camera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: RealFaceCameraFactory): FaceCameraFactory
}
```

Create `app/src/debug/java/com/mediplus/faceverify/core/di/CameraModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.dev.camera.SwitchingFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the face camera through the switching factory (emulated vs real CameraX). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: SwitchingFaceCameraFactory): FaceCameraFactory
}
```

- [ ] **Step 3: Verify the full suite and both builds**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

Expected: `BUILD SUCCESSFUL`, 162 tests, 0 failures.

- [ ] **Step 4: Confirm release builds exclude the fake**

```powershell
Select-String -Path (Get-ChildItem app\build\outputs\ -Recurse -Filter *.apk).FullName -Pattern "FakeFaceCamera" -Quiet
```

Expected: no match (`False` or empty). A match means a `dev/` class leaked into release — check that nothing in `src/main` or `src/release` imports it.

- [ ] **Step 5: Commit**

```powershell
git add -A app/src/debug/java/com/mediplus/faceverify/dev/camera/ app/src/debug/java/com/mediplus/faceverify/core/di/ app/src/release/java/com/mediplus/faceverify/core/di/ app/src/main/java/com/mediplus/faceverify/core/di/
git commit -m "feat(dev): route the face camera through the switching factory"
```

- [ ] **Step 6: Manual smoke test on an emulator with no camera**

Create or edit an AVD with **Camera → Front: None**, install the debug build, then for each `CameraScenario` in the "FaceVerify Dev" launcher, walk the journey to the face check and confirm:

| Scenario | Expected |
|---|---|
| `SUCCESS` | Placeholder preview, guidance goes to "Looks good — capture now", Capture enabled, submits, journey continues |
| `NEVER_GOOD` | Guidance parks on "Move a little closer.", Capture stays disabled |
| `CAPTURE_ERROR` | Capture enabled; tapping it shows the generic error with a working Retry |
| `NO_CAMERA_HARDWARE` | "No camera found" terminal message, no preview, no buttons |

This is the deliverable's real acceptance check — the unit tests cover the fake's logic, not the screen wiring.

---

## Notes for the implementer

- **Capture-failure messaging routes through `ErrorMapper`.** `onCaptureFailed()`
  maps `AppError.Transient(TransientKind.UNKNOWN)`, which already yields
  `err_generic_title` / `err_generic_body` with a Retry action. No `ViewModel` in
  this codebase imports `R`, and routing the message through `ErrorMapper` keeps
  that invariant while matching how every other failure on this screen is
  produced. Only the two `face_camera_unavailable_*` strings are new.

  *(Historical note: this began as a deviation — the spec originally called for
  `face_capture_failed_title` / `_body`. The spec was amended to match the shipped
  code in commit `856e872`, so the two documents now agree and there is no
  outstanding deviation.)*
- **Where the seam differs from NFC, and why.** `SwitchingMemberCardReader` is a decorator that re-reads the toggle on every call, because all its methods are `suspend`. `FaceCamera` has synchronous methods, so the equivalent is a factory that decides once. Do not try to make it a decorator.
- **`FaceCheckViewModel`'s constructor must not change.** If a task tempts you to inject the camera into it, the design deliberately avoided that — the screen owns the camera's lifecycle, and an unchanged constructor is why no existing test needed editing.
- **Test counts** in the expected output assume the suite is at 154 before Task 1. If your baseline differs, the deltas are +2 (Task 2), +1 (Task 3), +5 (Task 4).
