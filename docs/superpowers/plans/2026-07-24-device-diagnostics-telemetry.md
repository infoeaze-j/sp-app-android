# Device Diagnostics Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the back office pull a permission-free snapshot of a device's current state on demand, via a poll-then-report loop that fires on login and on a foreground interval.

**Architecture:** A new `core/diagnostics/` seam (`DeviceDiagnostics` → `DeviceStateSnapshot`) mirrors the existing hardware seams (`core/nfc`, `core/camera`). A `DiagnosticsRepository` (via `apiCall`/`AppResult`) polls `GET /diagnostics/poll` and posts `POST /diagnostics`. A `PollAndReportDiagnosticsUseCase` owns the dedup rule; a `DiagnosticsPoller` (a `ProcessLifecycleOwner` observer, Singleton) runs the loop while the app is foregrounded and the session is `Active`. Debug/release DI split follows the `Camera`/`Nfc`/`Update` module pattern.

**Tech Stack:** Kotlin 2.3.10, Hilt ≥2.60, Retrofit + kotlinx.serialization, Coroutines/StateFlow, AndroidX Lifecycle (adds `lifecycle-process`). Tests: JUnit4 + MockK + Turbine + MockWebServer + `kotlinx-coroutines-test`.

## Global Constraints

Every task's requirements implicitly include these (verbatim from the spec and CLAUDE.md/constitution):

- **`JAVA_HOME` for Gradle:** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` before any `./gradlew` call (PowerShell).
- **No platform types above `core`.** No `android.*`/`androidx.*` framework type may reach a ViewModel, use case, or domain model. All `BatteryManager`/`ConnectivityManager`/`Build`/etc. access lives inside `core/diagnostics/AndroidDeviceDiagnostics`.
- **Injected dispatchers only** — `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher` from `DispatchersModule`. Never reference `Dispatchers.*` directly. Unit tests use `MainDispatcherRule`.
- **No user-facing text, no `UiMessage`, no phase enum, no screen.** Diagnostics are invisible to the operator by design. No `strings.xml` additions.
- **DTOs never leave `data/remote`.** Map wire DTOs → domain `DeviceStateSnapshot` inside the repository.
- **Never log or persist identity/biometric data.** The snapshot holds only non-identifying device state; never log snapshot contents.
- **No new `AppResult`/`AppError`/`BusinessCode` variants.** Reuse `Success`/`TransientFailure`/`Timeout`. Failures are swallowed (best-effort).
- **Same-origin:** `diagnostics/poll` and `diagnostics` are relative paths on `BuildConfig.BASE_URL`; the bearer token rides via the existing `AuthInterceptor`.
- **detekt (CLI, 1.23.7, `config/detekt/detekt.yml`, `maxIssues: 0`):** functions ≤ 50 lines, line length ≤ 120, `ReturnCount` ≤ 4, no bare `TODO`/`FIXME`. `main` baseline is already red (~48 pre-existing issues) — compare against baseline, do not add new ones. detekt is **not** a Gradle task; run the CLI as CI does.
- **Test-first, ≥ 80% coverage on changed logic, success *and* failure paths.** Real Android-hardware impls (`AndroidDeviceDiagnostics`) follow the `NdefMemberCardReader` precedent: **not** JVM-unit-tested, device-gated instead. All pure logic (repository, use case, poller, fakes) **is** unit-tested.

---

## File Structure

**New (main):**
- `core/diagnostics/DeviceStateSnapshot.kt` — domain snapshot data class + nested group classes.
- `core/diagnostics/DeviceDiagnostics.kt` — seam interface (`suspend fun snapshot(): DeviceStateSnapshot`).
- `core/diagnostics/AndroidDeviceDiagnostics.kt` — real impl (device-gated), split into private builders.
- `core/diagnostics/DiagnosticsPoller.kt` — `ProcessLifecycleOwner` observer + testable loop.
- `data/remote/DiagnosticsApi.kt` — Retrofit interface + wire DTOs (`@Serializable`, package-private in spirit).
- `data/repository/DiagnosticsRepository.kt` — interface + `DiagnosticsRepositoryImpl`.
- `domain/usecase/PollAndReportDiagnosticsUseCase.kt` — poll→dedup→report; `PollOutcome` enum.

**New (debug):**
- `dev/diagnostics/FakeDeviceDiagnostics.kt`, `dev/diagnostics/SwitchingDeviceDiagnostics.kt`
- `dev/repository/SwitchingDiagnosticsRepository.kt`, `dev/repository/FakeDiagnosticsRepository.kt`
- `core/di/DiagnosticsModule.kt` (debug variant)

**New (release):**
- `core/di/DiagnosticsModule.kt` (release variant)

**Modified:**
- `core/di/ApiModule.kt` — add `provideDiagnosticsApi`.
- `core/di/RepositoryModule.kt` (release + debug) — bind `DiagnosticsRepository`.
- `dev/DevScenarios.kt`, `dev/DevSettings.kt`, `dev/DevSettingsStore.kt`, `dev/ui/DevSettingsScreen.kt`, `dev/ui/DevSettingsViewModel.kt` — add `DiagnosticsScenario`.
- `FaceVerifyApp.kt` — inject + `bind()` the poller.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — add `androidx.lifecycle:lifecycle-process`.
- `docs/openapi.yaml` — document the two placeholder endpoints.
- `CLAUDE.md` — record the feature + device-gated `AndroidDeviceDiagnostics`.

---

## Task 1: Snapshot model, seam interface, and the real Android reader (device-gated)

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/core/diagnostics/DeviceStateSnapshot.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/diagnostics/DeviceDiagnostics.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/diagnostics/AndroidDeviceDiagnostics.kt`
- Modify: `docs/openapi.yaml`

**Interfaces:**
- Produces: `data class DeviceStateSnapshot(...)` (fields below); `interface DeviceDiagnostics { suspend fun snapshot(): DeviceStateSnapshot }`; `class AndroidDeviceDiagnostics @Inject constructor(@ApplicationContext context, @IoDispatcher dispatcher) : DeviceDiagnostics`.

> **Note on testing:** `AndroidDeviceDiagnostics` reads Android framework services and follows the established `NdefMemberCardReader`/`CameraXFaceCamera` convention: **no JVM unit test** (there is no Robolectric in this project's test stack, and adding one is out of scope). Its verification is compilation (`assembleDebug`) plus the device-gated manual check recorded in Task 6. The pure logic that consumes it is fully tested in Tasks 3–5.

- [ ] **Step 1: Write the snapshot model**

Create `DeviceStateSnapshot.kt`. Every field is readable with no runtime permission.

```kotlin
package com.mediplus.faceverify.core.diagnostics

/**
 * A point-in-time, non-identifying snapshot of device state, collected only when the back office
 * asks for it (docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md).
 *
 * Every field here is readable without a runtime-permission grant. Deliberately excludes anything
 * identifying or permission-gated (IMEI/serial/MAC/ANDROID_ID, location, cellular generation) — this
 * is low-entropy device *state*, never a device fingerprint, per the constitution's non-revealing rule.
 */
data class DeviceStateSnapshot(
    val battery: BatteryState,
    val network: NetworkState,
    val storage: StorageState,
    val memory: MemoryState,
    val display: DisplayState,
    val device: DeviceInfo,
    val app: AppInfo,
    val environment: EnvironmentState,
    val thermal: ThermalState,
    val uptime: UptimeState,
)

data class BatteryState(
    val levelPercent: Int,
    val isCharging: Boolean,
    val plug: BatteryPlug,
    val health: String,
    val temperatureDeciC: Int,
    val voltageMv: Int,
    val powerSaveMode: Boolean,
)

enum class BatteryPlug { AC, USB, WIRELESS, NONE }

data class NetworkState(
    val transport: NetworkTransport,
    val isMetered: Boolean,
    val isValidated: Boolean,
)

enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, VPN, NONE }

data class StorageState(val internalFreeBytes: Long, val internalTotalBytes: Long)

data class MemoryState(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)

data class DisplayState(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val rotationDegrees: Int,
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
)

data class AppInfo(val versionName: String, val versionCode: Int)

data class EnvironmentState(val locale: String, val timeZoneId: String, val airplaneMode: Boolean)

/** [headroom] is null below API 30, [status] below API 29 (each unavailable there). */
data class ThermalState(val headroom: Float?, val status: Int?)

data class UptimeState(val uptimeMillis: Long, val elapsedRealtimeMillis: Long)
```

- [ ] **Step 2: Write the seam interface**

Create `DeviceDiagnostics.kt`.

```kotlin
package com.mediplus.faceverify.core.diagnostics

/**
 * Reads a [DeviceStateSnapshot] off the main thread. The single seam through which any
 * `android.os`/`android.net` framework access happens — no platform type reaches a use case or
 * ViewModel (mirrors [com.mediplus.faceverify.core.nfc.MemberCardReader]).
 */
interface DeviceDiagnostics {
    suspend fun snapshot(): DeviceStateSnapshot
}
```

- [ ] **Step 3: Write the real Android reader**

Create `AndroidDeviceDiagnostics.kt`. Keep every function ≤ 50 lines (detekt) — one private builder per group. Full implementation:

```kotlin
package com.mediplus.faceverify.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import android.os.StatFs
import android.os.Environment

/**
 * The real [DeviceDiagnostics]. Device-gated like [com.mediplus.faceverify.core.nfc.NdefMemberCardReader]:
 * exercised on hardware/emulator, not in the JVM suite.
 */
class AndroidDeviceDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val currentVersion: CurrentAppVersion,
) : DeviceDiagnostics {

    override suspend fun snapshot(): DeviceStateSnapshot = withContext(dispatcher) {
        DeviceStateSnapshot(
            battery = buildBattery(),
            network = buildNetwork(),
            storage = buildStorage(),
            memory = buildMemory(),
            display = buildDisplay(),
            device = buildDevice(),
            app = AppInfo(currentVersion.name, currentVersion.code),
            environment = buildEnvironment(),
            thermal = buildThermal(),
            uptime = UptimeState(SystemClock.uptimeMillis(), SystemClock.elapsedRealtime()),
        )
    }

    private fun buildBattery(): BatteryState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return BatteryState(
            levelPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            isCharging = bm.isCharging,
            plug = plugOf(plugged),
            health = (sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0).toString(),
            temperatureDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            powerSaveMode = pm.isPowerSaveMode,
        )
    }

    private fun plugOf(plugged: Int): BatteryPlug = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryPlug.AC
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryPlug.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPlug.WIRELESS
        else -> BatteryPlug.NONE
    }

    private fun buildNetwork(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return NetworkState(
            transport = transportOf(caps),
            isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    private fun transportOf(caps: NetworkCapabilities?): NetworkTransport = when {
        caps == null -> NetworkTransport.NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.NONE
    }

    private fun buildStorage(): StorageState {
        val stat = StatFs(Environment.getDataDirectory().path)
        return StorageState(internalFreeBytes = stat.availableBytes, internalTotalBytes = stat.totalBytes)
    }

    private fun buildMemory(): MemoryState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return MemoryState(info.availMem, info.totalMem, info.lowMemory)
    }

    private fun buildDisplay(): DisplayState {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        val display = wm.defaultDisplay
        return DisplayState(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRateHz = display?.refreshRate ?: 0f,
            rotationDegrees = (display?.rotation ?: 0) * ROTATION_STEP_DEGREES,
        )
    }

    private fun buildDevice(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        brand = Build.BRAND,
        device = Build.DEVICE,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
    )

    private fun buildEnvironment(): EnvironmentState = EnvironmentState(
        locale = Locale.getDefault().toLanguageTag(),
        timeZoneId = TimeZone.getDefault().id,
        airplaneMode = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0,
        ) != 0,
    )

    // getThermalHeadroom is API 30 (R); currentThermalStatus is API 29 (Q). Guard each to its own
    // floor so Lint's NewApi check stays green (abortOnError=true).
    private fun buildThermal(): ThermalState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else null
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
        } else {
            null
        }
        return ThermalState(headroom = headroom, status = status)
    }

    private companion object {
        const val ROTATION_STEP_DEGREES = 90
        const val THERMAL_FORECAST_SECONDS = 10
    }
}
```

- [ ] **Step 4: Document the placeholder endpoints in `docs/openapi.yaml`**

Add (adjust indentation to the file's style) under `paths:`:

```yaml
  /diagnostics/poll:
    get:
      summary: >-
        Does the back office want a device diagnostics snapshot right now?
        App-invented placeholder (see the /members/verify precedent) — reconcile when the server
        publishes its shape. Authenticated (bearer). Same-origin with BASE_URL.
      security:
        - bearerAuth: []
      responses:
        '200':
          description: A snapshot is requested; body carries the request id to echo back.
          content:
            application/json:
              schema:
                type: object
                required: [requestId]
                properties:
                  requestId: { type: string }
        '204':
          description: Nothing requested; the client does nothing.
  /diagnostics:
    post:
      summary: >-
        Report the full permission-free device snapshot, tagged with the poll's requestId.
        App-invented placeholder. Authenticated (bearer). Same-origin with BASE_URL.
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [requestId, snapshot]
              properties:
                requestId: { type: string }
                snapshot: { type: object, description: Non-identifying device state groups. }
      responses:
        '200': { description: Accepted. }
```

- [ ] **Step 5: Verify it compiles**

Run (PowerShell):
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (No binding yet — nothing injects `DeviceDiagnostics`, so the Hilt graph is untouched.)

- [ ] **Step 6: Commit**

```
git add app/src/main/java/com/mediplus/faceverify/core/diagnostics docs/openapi.yaml
git commit -m "feat: add device diagnostics snapshot model, seam, and real reader"
```

---

## Task 2: Diagnostics API, DTOs, and repository (MockWebServer-tested)

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/data/remote/DiagnosticsApi.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/data/repository/DiagnosticsRepository.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt`
- Modify: `app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/data/repository/DiagnosticsRepositoryTest.kt`

**Interfaces:**
- Consumes: `DeviceStateSnapshot` (Task 1); `apiCall` (`core/network/ApiCall.kt`); `AppResult`, `AppError.Transient`, `TransientKind`.
- Produces: `interface DiagnosticsApi`; `interface DiagnosticsRepository { suspend fun poll(): AppResult<String?>; suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> }`; `class DiagnosticsRepositoryImpl @Inject constructor(api: DiagnosticsApi, @IoDispatcher dispatcher)`.

- [ ] **Step 1: Write the failing repository test**

Create `DiagnosticsRepositoryTest.kt`. Copy the MockWebServer setup idiom from an existing repository test if one exists; otherwise this is self-contained.

```kotlin
package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.diagnostics.BatteryPlug
import com.mediplus.faceverify.core.diagnostics.BatteryState
import com.mediplus.faceverify.core.diagnostics.DeviceInfo
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.diagnostics.DisplayState
import com.mediplus.faceverify.core.diagnostics.EnvironmentState
import com.mediplus.faceverify.core.diagnostics.MemoryState
import com.mediplus.faceverify.core.diagnostics.NetworkState
import com.mediplus.faceverify.core.diagnostics.NetworkTransport
import com.mediplus.faceverify.core.diagnostics.AppInfo
import com.mediplus.faceverify.core.diagnostics.StorageState
import com.mediplus.faceverify.core.diagnostics.ThermalState
import com.mediplus.faceverify.core.diagnostics.UptimeState
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.remote.DiagnosticsApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class DiagnosticsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DiagnosticsApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DiagnosticsApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo() = DiagnosticsRepositoryImpl(api, UnconfinedTestDispatcher())

    private fun snapshot() = DeviceStateSnapshot(
        battery = BatteryState(80, true, BatteryPlug.USB, "2", 250, 4100, false),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(1_000L, 2_000L),
        memory = MemoryState(500L, 4_000L, lowMemory = false),
        display = DisplayState(1080, 2400, 420, 60f, 0),
        device = DeviceInfo("Google", "Pixel", "google", "raven", 34, "14"),
        app = AppInfo("1.0", 1),
        environment = EnvironmentState("en-ZA", "Africa/Johannesburg", airplaneMode = false),
        thermal = ThermalState(0.3f, 0),
        uptime = UptimeState(1_000L, 2_000L),
    )

    @Test
    fun `poll 200 yields the request id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"requestId":"req-7"}"""))
        val result = repo().poll()
        assertEquals(AppResult.Success("req-7"), result)
    }

    @Test
    fun `poll 204 yields null - nothing requested`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(AppResult.Success(null), repo().poll())
    }

    @Test
    fun `poll 404 yields null - endpoint not deployed, fail open`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AppResult.Success(null), repo().poll())
    }

    @Test
    fun `poll 500 is a transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo().poll() is AppResult.TransientFailure)
    }

    @Test
    fun `report posts requestId and snapshot and succeeds on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = repo().report("req-7", snapshot())
        assertEquals(AppResult.Success(Unit), result)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"requestId\":\"req-7\""))
        assertTrue(body.contains("\"transport\":\"WIFI\""))
        assertTrue(body.contains("\"levelPercent\":80"))
    }

    @Test
    fun `report 500 is a transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(repo().report("req-7", snapshot()) is AppResult.TransientFailure)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.data.repository.DiagnosticsRepositoryTest"
```
Expected: FAIL — `DiagnosticsApi` / `DiagnosticsRepositoryImpl` unresolved.

- [ ] **Step 3: Write the API + DTOs**

Create `DiagnosticsApi.kt`.

```kotlin
package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Device diagnostics telemetry: poll for a request, then report a snapshot
 * (docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md).
 *
 * App-invented placeholder — reconcile with the back office when it publishes its shape. Both calls
 * are authenticated; the bearer token rides via AuthInterceptor. DTOs never leave this package.
 */
interface DiagnosticsApi {

    @GET("diagnostics/poll")
    suspend fun poll(): Response<DiagnosticsPollResponse>

    @POST("diagnostics")
    suspend fun report(@Body body: DiagnosticsReport): Response<Unit>
}

@Serializable
data class DiagnosticsPollResponse(val requestId: String)

@Serializable
data class DiagnosticsReport(val requestId: String, val snapshot: DeviceSnapshotDto)

@Serializable
data class DeviceSnapshotDto(
    val battery: BatteryDto,
    val network: NetworkDto,
    val storage: StorageDto,
    val memory: MemoryDto,
    val display: DisplayDto,
    val device: DeviceDto,
    val app: AppDto,
    val environment: EnvironmentDto,
    val thermal: ThermalDto,
    val uptime: UptimeDto,
)

@Serializable
data class BatteryDto(
    val levelPercent: Int,
    val isCharging: Boolean,
    val plug: String,
    val health: String,
    val temperatureDeciC: Int,
    val voltageMv: Int,
    val powerSaveMode: Boolean,
)

@Serializable
data class NetworkDto(val transport: String, val isMetered: Boolean, val isValidated: Boolean)

@Serializable
data class StorageDto(val internalFreeBytes: Long, val internalTotalBytes: Long)

@Serializable
data class MemoryDto(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)

@Serializable
data class DisplayDto(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val rotationDegrees: Int,
)

@Serializable
data class DeviceDto(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
)

@Serializable
data class AppDto(val versionName: String, val versionCode: Int)

@Serializable
data class EnvironmentDto(val locale: String, val timeZoneId: String, val airplaneMode: Boolean)

@Serializable
data class ThermalDto(val headroom: Float?, val status: Int?)

@Serializable
data class UptimeDto(val uptimeMillis: Long, val elapsedRealtimeMillis: Long)

/** Domain snapshot → wire DTO. Kept in this package so DTOs never leak upward. */
fun DeviceStateSnapshot.toDto(): DeviceSnapshotDto = DeviceSnapshotDto(
    battery = BatteryDto(
        battery.levelPercent, battery.isCharging, battery.plug.name, battery.health,
        battery.temperatureDeciC, battery.voltageMv, battery.powerSaveMode,
    ),
    network = NetworkDto(network.transport.name, network.isMetered, network.isValidated),
    storage = StorageDto(storage.internalFreeBytes, storage.internalTotalBytes),
    memory = MemoryDto(memory.availBytes, memory.totalBytes, memory.lowMemory),
    display = DisplayDto(
        display.widthPx, display.heightPx, display.densityDpi, display.refreshRateHz, display.rotationDegrees,
    ),
    device = DeviceDto(
        device.manufacturer, device.model, device.brand, device.device, device.sdkInt, device.release,
    ),
    app = AppDto(app.versionName, app.versionCode),
    environment = EnvironmentDto(environment.locale, environment.timeZoneId, environment.airplaneMode),
    thermal = ThermalDto(thermal.headroom, thermal.status),
    uptime = UptimeDto(uptime.uptimeMillis, uptime.elapsedRealtimeMillis),
)
```

- [ ] **Step 4: Write the repository**

Create `DiagnosticsRepository.kt`.

```kotlin
package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.DiagnosticsApi
import com.mediplus.faceverify.data.remote.DiagnosticsReport
import com.mediplus.faceverify.data.remote.toDto
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Poll-then-report telemetry access. Callers see only [AppResult] and domain types.
 * `poll()` returns `Success(requestId)` when the back office wants a snapshot, `Success(null)` when
 * it does not — including a 404 from a backend that has not deployed the endpoint yet (fail open).
 */
interface DiagnosticsRepository {
    suspend fun poll(): AppResult<String?>
    suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit>
}

class DiagnosticsRepositoryImpl @Inject constructor(
    private val api: DiagnosticsApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : DiagnosticsRepository {

    override suspend fun poll(): AppResult<String?> =
        apiCall(dispatcher, { api.poll() }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.requestId)
                // 204 (no content) and 404 (not deployed) both mean "nothing requested".
                response.code() == HttpURLConnection.HTTP_NO_CONTENT ||
                    response.code() == HttpURLConnection.HTTP_NOT_FOUND -> AppResult.Success(null)
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        apiCall(dispatcher, { api.report(DiagnosticsReport(requestId, snapshot.toDto())) }) { response ->
            if (response.isSuccessful) {
                AppResult.Success(Unit)
            } else {
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        }
}
```

- [ ] **Step 5: Provide the API and bind the real repository**

In `core/di/ApiModule.kt`, add the import `import com.mediplus.faceverify.data.remote.DiagnosticsApi` and the provider:

```kotlin
    @Provides
    @Singleton
    fun provideDiagnosticsApi(retrofit: Retrofit): DiagnosticsApi = retrofit.create()
```

In `app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`, add the import
`import com.mediplus.faceverify.data.repository.DiagnosticsRepository` and
`import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl`, then:

```kotlin
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository
```

(The debug switching binding is added in Task 5; nothing injects `DiagnosticsRepository` into the graph until Task 6, so debug still builds.)

- [ ] **Step 6: Run the test to verify it passes**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.data.repository.DiagnosticsRepositoryTest"
```
Expected: PASS (6 tests).

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/mediplus/faceverify/data app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt app/src/test/java/com/mediplus/faceverify/data/repository/DiagnosticsRepositoryTest.kt
git commit -m "feat: add diagnostics poll/report API and repository"
```

---

## Task 3: Poll-and-report use case with dedup (unit-tested)

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/domain/usecase/PollAndReportDiagnosticsUseCase.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/domain/usecase/PollAndReportDiagnosticsUseCaseTest.kt`

**Interfaces:**
- Consumes: `DiagnosticsRepository` (Task 2), `DeviceDiagnostics` (Task 1), `AppResult`.
- Produces: `class PollAndReportDiagnosticsUseCase @Inject constructor(repository, diagnostics) { suspend operator fun invoke(): PollOutcome }`; `enum class PollOutcome { NothingRequested, Reported, AlreadyHandled, PollFailed, ReportFailed }`.

- [ ] **Step 1: Write the failing test**

Create `PollAndReportDiagnosticsUseCaseTest.kt`.

```kotlin
package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.diagnostics.AppInfo
import com.mediplus.faceverify.core.diagnostics.BatteryPlug
import com.mediplus.faceverify.core.diagnostics.BatteryState
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceInfo
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.diagnostics.DisplayState
import com.mediplus.faceverify.core.diagnostics.EnvironmentState
import com.mediplus.faceverify.core.diagnostics.MemoryState
import com.mediplus.faceverify.core.diagnostics.NetworkState
import com.mediplus.faceverify.core.diagnostics.NetworkTransport
import com.mediplus.faceverify.core.diagnostics.StorageState
import com.mediplus.faceverify.core.diagnostics.ThermalState
import com.mediplus.faceverify.core.diagnostics.UptimeState
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PollAndReportDiagnosticsUseCaseTest {

    private val snapshot = DeviceStateSnapshot(
        battery = BatteryState(80, true, BatteryPlug.USB, "2", 250, 4100, false),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(1L, 2L),
        memory = MemoryState(1L, 2L, false),
        display = DisplayState(1, 2, 3, 60f, 0),
        device = DeviceInfo("m", "mo", "b", "d", 34, "14"),
        app = AppInfo("1.0", 1),
        environment = EnvironmentState("en-ZA", "UTC", false),
        thermal = ThermalState(null, null),
        uptime = UptimeState(1L, 2L),
    )

    private val diagnostics = mockk<DeviceDiagnostics> { coEvery { snapshot() } returns snapshot }

    private fun transient() = AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

    @Test
    fun `nothing requested - no snapshot, no report`() = runTest {
        val repo = mockk<DiagnosticsRepository>(relaxed = true) {
            coEvery { poll() } returns AppResult.Success(null)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.NothingRequested, useCase())
        coVerify(exactly = 0) { diagnostics.snapshot() }
        coVerify(exactly = 0) { repo.report(any(), any()) }
    }

    @Test
    fun `fresh request id - collects and reports`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returns AppResult.Success(Unit)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.Reported, useCase())
        coVerify(exactly = 1) { repo.report("req-1", snapshot) }
    }

    @Test
    fun `same request id twice - reports once`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returns AppResult.Success(Unit)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.Reported, useCase())
        assertEquals(PollOutcome.AlreadyHandled, useCase())
        coVerify(exactly = 1) { repo.report("req-1", snapshot) }
    }

    @Test
    fun `poll failure - no report`() = runTest {
        val repo = mockk<DiagnosticsRepository>(relaxed = true) {
            coEvery { poll() } returns transient()
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.PollFailed, useCase())
        coVerify(exactly = 0) { repo.report(any(), any()) }
    }

    @Test
    fun `report failure - not recorded, retried next time`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returnsMany listOf(transient(), AppResult.Success(Unit))
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.ReportFailed, useCase())
        assertEquals(PollOutcome.Reported, useCase())
        coVerify(exactly = 2) { repo.report("req-1", snapshot) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCaseTest"
```
Expected: FAIL — `PollAndReportDiagnosticsUseCase` / `PollOutcome` unresolved.

- [ ] **Step 3: Write the use case**

Create `PollAndReportDiagnosticsUseCase.kt`.

```kotlin
package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import javax.inject.Inject

/** The outcome of one poll tick. Purely diagnostic — nothing user-facing. */
enum class PollOutcome { NothingRequested, Reported, AlreadyHandled, PollFailed, ReportFailed }

/**
 * One poll-then-report cycle. If the back office has a fresh `requestId` for this device, collect a
 * snapshot and report it, then remember the id so the same request is answered exactly once per
 * process. Any transport failure is reported back as a non-fatal [PollOutcome]; the caller simply
 * retries on the next interval. A failed report is *not* recorded, so it is retried.
 *
 * Holds `lastHandledRequestId` in memory. The [DiagnosticsPoller] is the sole caller and invokes
 * this sequentially on a single dispatcher, so the field needs no synchronization.
 */
class PollAndReportDiagnosticsUseCase @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val diagnostics: DeviceDiagnostics,
) {
    private var lastHandledRequestId: String? = null

    suspend operator fun invoke(): PollOutcome = when (val poll = repository.poll()) {
        is AppResult.Success -> handle(poll.data)
        else -> PollOutcome.PollFailed
    }

    private suspend fun handle(requestId: String?): PollOutcome = when {
        requestId == null -> PollOutcome.NothingRequested
        requestId == lastHandledRequestId -> PollOutcome.AlreadyHandled
        else -> report(requestId)
    }

    private suspend fun report(requestId: String): PollOutcome =
        when (repository.report(requestId, diagnostics.snapshot())) {
            is AppResult.Success -> {
                lastHandledRequestId = requestId
                PollOutcome.Reported
            }
            else -> PollOutcome.ReportFailed
        }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCaseTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/mediplus/faceverify/domain/usecase/PollAndReportDiagnosticsUseCase.kt app/src/test/java/com/mediplus/faceverify/domain/usecase/PollAndReportDiagnosticsUseCaseTest.kt
git commit -m "feat: add poll-and-report diagnostics use case with dedup"
```

---

## Task 4: Foreground poller loop (unit-tested) + lifecycle dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/mediplus/faceverify/core/diagnostics/DiagnosticsPoller.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/core/diagnostics/DiagnosticsPollerTest.kt`

**Interfaces:**
- Consumes: `PollAndReportDiagnosticsUseCase` (Task 3), `SessionManager` (`core/session`), `SessionState.Active`, `@MainDispatcher`.
- Produces: `class DiagnosticsPoller` (Singleton) with `fun bind()` (registers with `ProcessLifecycleOwner`) and internal `suspend fun pollWhileActive()` (the tested loop). Poll interval constant `POLL_INTERVAL_MILLIS = 15 * 60 * 1000L`.

- [ ] **Step 1: Add the `lifecycle-process` dependency**

In `gradle/libs.versions.toml`, under `[libraries]` (reuse the existing `lifecycle` version ref):

```toml
androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
```

In `app/build.gradle.kts`, in `dependencies { }` next to the other lifecycle deps:

```kotlin
    implementation(libs.androidx.lifecycle.process)
```

- [ ] **Step 2: Write the failing poller test**

Create `DiagnosticsPollerTest.kt`. The loop is tested directly (the `ProcessLifecycleOwner` registration in `bind()` is a thin, untested shim).

```kotlin
package com.mediplus.faceverify.core.diagnostics

import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.backgroundScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsPollerTest {

    private val calls = AtomicInteger(0)
    private val useCase = mockk<PollAndReportDiagnosticsUseCase> {
        coEvery { this@mockk.invoke() } answers { calls.incrementAndGet(); PollOutcome.NothingRequested }
    }

    private fun sessionManager(state: MutableStateFlow<SessionState>): SessionManager =
        mockk(relaxed = true) { every { sessionState } returns state }

    @Test
    fun `polls immediately when session becomes active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()
        assertEquals(1, calls.get())
    }

    @Test
    fun `repeats every interval while active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()                                   // immediate poll -> 1
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS + 1)   // -> 2
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS + 1)   // -> 3
        assertEquals(3, calls.get())
    }

    @Test
    fun `stops polling when session leaves active`() = runTest {
        val state = MutableStateFlow(SessionState.Active)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()                                   // -> 1
        state.value = SessionState.None                // collectLatest cancels the inner loop
        advanceTimeBy(DiagnosticsPoller.POLL_INTERVAL_MILLIS * 3)
        assertEquals(1, calls.get())
    }

    @Test
    fun `resumes when session becomes active again`() = runTest {
        val state = MutableStateFlow(SessionState.None)
        val poller = DiagnosticsPoller(useCase, sessionManager(state), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { poller.pollWhileActive() }
        runCurrent()
        assertEquals(0, calls.get())
        state.value = SessionState.Active
        runCurrent()
        assertEquals(1, calls.get())
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.core.diagnostics.DiagnosticsPollerTest"
```
Expected: FAIL — `DiagnosticsPoller` unresolved.

- [ ] **Step 4: Write the poller**

Create `DiagnosticsPoller.kt`. The constructor takes a `CoroutineContext` so the test injects `backgroundScope`'s context (a `TestDispatcher`); production supplies `@MainDispatcher`.

```kotlin
package com.mediplus.faceverify.core.diagnostics

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mediplus.faceverify.core.di.MainDispatcher
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the diagnostics poll loop while the app is foregrounded and the session is
 * [SessionState.Active] (approach A of the design). A `ProcessLifecycleOwner` observer: `onStart`
 * (app foregrounded) launches the loop, `onStop` cancels it. Best-effort throughout — the use case
 * swallows all failures, so nothing here can affect the patient journey.
 *
 * The loop is foreground-only by construction: it lives on the process lifecycle, not on any screen.
 */
@Singleton
class DiagnosticsPoller @Inject constructor(
    private val pollAndReport: PollAndReportDiagnosticsUseCase,
    private val sessionManager: SessionManager,
    @param:MainDispatcher private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loop: Job? = null

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        loop = scope.launch { pollWhileActive() }
    }

    override fun onStop(owner: LifecycleOwner) {
        loop?.cancel()
        loop = null
    }

    /**
     * Visible for testing. Polls immediately whenever the session is Active, then every
     * [POLL_INTERVAL_MILLIS]; `collectLatest` cancels the inner loop the moment the session leaves
     * Active, and restarts it if the session becomes Active again.
     */
    internal suspend fun pollWhileActive() {
        sessionManager.sessionState.collectLatest { state ->
            if (state == SessionState.Active) {
                while (true) {
                    pollAndReport()
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 15L * 60L * 1000L
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.core.diagnostics.DiagnosticsPollerTest"
```
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/mediplus/faceverify/core/diagnostics/DiagnosticsPoller.kt app/src/test/java/com/mediplus/faceverify/core/diagnostics/DiagnosticsPollerTest.kt
git commit -m "feat: add foreground diagnostics poller loop"
```

---

## Task 5: Debug fake stack + DI bindings (both variants)

**Files:**
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/diagnostics/FakeDeviceDiagnostics.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/diagnostics/SwitchingDeviceDiagnostics.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeDiagnosticsRepository.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingDiagnosticsRepository.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/core/di/DiagnosticsModule.kt`
- Create: `app/src/release/java/com/mediplus/faceverify/core/di/DiagnosticsModule.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/repository/FakeDiagnosticsRepositoryTest.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/repository/SwitchingDiagnosticsRepositoryTest.kt`

**Interfaces:**
- Consumes: `DevSettingsStore`, `DevSettings`, `DiagnosticsRepository`, `DiagnosticsRepositoryImpl`, `DeviceDiagnostics`, `AndroidDeviceDiagnostics`, `AppResult`.
- Produces: `enum class DiagnosticsScenario { OFF, REQUESTED_ONCE, ALWAYS_REQUESTED, POLL_FAILS, REPORT_FAILS }` (default `OFF`); the four fake/switching classes; both `DiagnosticsModule`s.

- [ ] **Step 1: Add the scenario enum + wire it into DevSettings**

In `dev/DevScenarios.kt` add:

```kotlin
/**
 * The emulated diagnostics telemetry. OFF = back office wants nothing; REQUESTED_ONCE = one
 * request id, then silent; ALWAYS_REQUESTED = a new id every poll; POLL_FAILS / REPORT_FAILS
 * exercise the swallowed-failure paths.
 */
enum class DiagnosticsScenario { OFF, REQUESTED_ONCE, ALWAYS_REQUESTED, POLL_FAILS, REPORT_FAILS }
```

In `dev/DevSettings.kt`: add the field `val diagnostics: DiagnosticsScenario = DiagnosticsScenario.OFF,` to `DevSettings` (after `update`); add the key `val DIAGNOSTICS = stringPreferencesKey("dev_scenario_diagnostics")` to `DevPrefKeys`; and in `toDevSettings()` add `diagnostics = this[DevPrefKeys.DIAGNOSTICS].toEnumOr(defaults.diagnostics),`.

In `dev/DevSettingsStore.kt`: add `suspend fun setDiagnostics(scenario: DiagnosticsScenario)` to the interface, and the impl:

```kotlin
    override suspend fun setDiagnostics(scenario: DiagnosticsScenario) =
        edit { it[DevPrefKeys.DIAGNOSTICS] = scenario.name }
```

- [ ] **Step 2: Write the fake device diagnostics**

Create `dev/diagnostics/FakeDeviceDiagnostics.kt`. A canned, obviously-fake snapshot (real reader also works on emulator; this just keeps the fake stack self-contained).

```kotlin
package com.mediplus.faceverify.dev.diagnostics

import com.mediplus.faceverify.core.diagnostics.AppInfo
import com.mediplus.faceverify.core.diagnostics.BatteryPlug
import com.mediplus.faceverify.core.diagnostics.BatteryState
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceInfo
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.diagnostics.DisplayState
import com.mediplus.faceverify.core.diagnostics.EnvironmentState
import com.mediplus.faceverify.core.diagnostics.MemoryState
import com.mediplus.faceverify.core.diagnostics.NetworkState
import com.mediplus.faceverify.core.diagnostics.NetworkTransport
import com.mediplus.faceverify.core.diagnostics.StorageState
import com.mediplus.faceverify.core.diagnostics.ThermalState
import com.mediplus.faceverify.core.diagnostics.UptimeState
import javax.inject.Inject

/** A fixed, unmistakably-fake snapshot for the dev stack. */
class FakeDeviceDiagnostics @Inject constructor() : DeviceDiagnostics {
    override suspend fun snapshot(): DeviceStateSnapshot = DeviceStateSnapshot(
        battery = BatteryState(42, isCharging = false, BatteryPlug.NONE, "GOOD", 300, 3900, powerSaveMode = false),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(8_000_000_000L, 64_000_000_000L),
        memory = MemoryState(2_000_000_000L, 6_000_000_000L, lowMemory = false),
        display = DisplayState(1080, 2340, 440, 60f, 0),
        device = DeviceInfo("FakeCo", "Emulator", "generic", "emu", 34, "14"),
        app = AppInfo("dev", 0),
        environment = EnvironmentState("en-ZA", "Africa/Johannesburg", airplaneMode = false),
        thermal = ThermalState(0.1f, 0),
        uptime = UptimeState(123_456L, 234_567L),
    )
}
```

- [ ] **Step 3: Write the switching device diagnostics**

Create `dev/diagnostics/SwitchingDeviceDiagnostics.kt` (mirrors `SwitchingApkInstaller`).

```kotlin
package com.mediplus.faceverify.dev.diagnostics

import com.mediplus.faceverify.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject

/** Debug-only router: fake snapshot when the master toggle is on, else the real reader. */
class SwitchingDeviceDiagnostics @Inject constructor(
    private val real: AndroidDeviceDiagnostics,
    private val fake: FakeDeviceDiagnostics,
    private val store: DevSettingsStore,
) : DeviceDiagnostics {
    override suspend fun snapshot(): DeviceStateSnapshot =
        (if (store.current().fakeEnabled) fake else real).snapshot()
}
```

- [ ] **Step 4: Write the failing fake-repository test**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/repository/FakeDiagnosticsRepositoryTest.kt`.

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.DiagnosticsScenario
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDiagnosticsRepositoryTest {

    @Test
    fun `off - poll returns null`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.OFF)
        assertEquals(AppResult.Success(null), repo.poll())
    }

    @Test
    fun `requested once - first poll has id, second is null`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.REQUESTED_ONCE)
        val first = repo.poll()
        assertTrue(first is AppResult.Success && first.data != null)
        assertEquals(AppResult.Success(null), repo.poll())
    }

    @Test
    fun `always requested - each poll has a distinct id`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.ALWAYS_REQUESTED)
        val a = (repo.poll() as AppResult.Success).data
        val b = (repo.poll() as AppResult.Success).data
        assertNotEquals(a, b)
    }

    @Test
    fun `poll fails - transient`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.POLL_FAILS)
        assertTrue(repo.poll() is AppResult.TransientFailure)
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.dev.repository.FakeDiagnosticsRepositoryTest"
```
Expected: FAIL — `FakeDiagnosticsRepository` unresolved.

- [ ] **Step 6: Write the fake repository**

Create `dev/repository/FakeDiagnosticsRepository.kt`. Deterministic ids (no `Math.random`/`Date.now`).

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import com.mediplus.faceverify.dev.DiagnosticsScenario

/** Canned poll/report outcomes for the dev stack, driven by [DiagnosticsScenario]. */
class FakeDiagnosticsRepository(private val scenario: DiagnosticsScenario) : DiagnosticsRepository {

    private var counter = 0

    override suspend fun poll(): AppResult<String?> = when (scenario) {
        DiagnosticsScenario.OFF -> AppResult.Success(null)
        DiagnosticsScenario.REQUESTED_ONCE ->
            if (counter++ == 0) AppResult.Success("dev-req-1") else AppResult.Success(null)
        DiagnosticsScenario.ALWAYS_REQUESTED -> AppResult.Success("dev-req-${counter++}")
        DiagnosticsScenario.POLL_FAILS ->
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        DiagnosticsScenario.REPORT_FAILS -> AppResult.Success("dev-req-1")
    }

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        if (scenario == DiagnosticsScenario.REPORT_FAILS) {
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        } else {
            AppResult.Success(Unit)
        }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.dev.repository.FakeDiagnosticsRepositoryTest"
```
Expected: PASS (4 tests).

- [ ] **Step 8: Write the switching repository + its test**

Create `dev/repository/SwitchingDiagnosticsRepository.kt`.

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.diagnostics.DeviceStateSnapshot
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepository
import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.faceverify.dev.DevSettingsStore
import javax.inject.Inject

/** Debug-only router: canned scenario when the master toggle is on, else the real backend. */
class SwitchingDiagnosticsRepository @Inject constructor(
    private val real: DiagnosticsRepositoryImpl,
    private val store: DevSettingsStore,
) : DiagnosticsRepository {

    override suspend fun poll(): AppResult<String?> = pick().poll()

    override suspend fun report(requestId: String, snapshot: DeviceStateSnapshot): AppResult<Unit> =
        pick().report(requestId, snapshot)

    private suspend fun pick(): DiagnosticsRepository {
        val settings = store.current()
        return if (settings.fakeEnabled) FakeDiagnosticsRepository(settings.diagnostics) else real
    }
}
```

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/repository/SwitchingDiagnosticsRepositoryTest.kt`.

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DiagnosticsScenario
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingDiagnosticsRepositoryTest {

    @Test
    fun `fake on - uses the canned scenario, never the real backend`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl>()
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = true, diagnostics = DiagnosticsScenario.OFF)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        assertEquals(AppResult.Success(null), repo.poll())
        coVerify(exactly = 0) { real.poll() }
    }

    @Test
    fun `fake off - delegates to the real backend`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl> { coEvery { poll() } returns AppResult.Success("real") }
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = false)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        assertEquals(AppResult.Success("real"), repo.poll())
        coVerify(exactly = 1) { real.poll() }
    }
}
```

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.dev.repository.SwitchingDiagnosticsRepositoryTest"
```
Expected: PASS (2 tests).

- [ ] **Step 9: Create the DI modules and the debug repository binding**

Create `app/src/release/java/com/mediplus/faceverify/core/di/DiagnosticsModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: the real system-service-backed diagnostics reader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDeviceDiagnostics(impl: AndroidDeviceDiagnostics): DeviceDiagnostics
}
```

Create `app/src/debug/java/com/mediplus/faceverify/core/di/DiagnosticsModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import com.mediplus.faceverify.dev.diagnostics.SwitchingDeviceDiagnostics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the reader through the switching decorator (fake vs real, per the dev toggle). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDeviceDiagnostics(impl: SwitchingDeviceDiagnostics): DeviceDiagnostics
}
```

In `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`, add the imports
`import com.mediplus.faceverify.data.repository.DiagnosticsRepository` and
`import com.mediplus.faceverify.dev.repository.SwitchingDiagnosticsRepository`, then:

```kotlin
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: SwitchingDiagnosticsRepository): DiagnosticsRepository
```

- [ ] **Step 10: Add a Dev UI row for the scenario**

In `dev/ui/DevSettingsViewModel.kt`, add a setter that mirrors the existing ones (e.g. `onUpdateChanged`):

```kotlin
    fun onDiagnosticsChanged(scenario: DiagnosticsScenario) {
        viewModelScope.launch { store.setDiagnostics(scenario) }
    }
```
(Add the import `import com.mediplus.faceverify.dev.DiagnosticsScenario`.)

In `dev/ui/DevSettingsScreen.kt`, add a scenario selector row next to the `UpdateScenario` row, following that row's exact composable pattern (enum dropdown/segmented control) bound to `state.diagnostics` / `onDiagnosticsChanged`. Match the surrounding code; do not introduce a new widget style.

- [ ] **Step 11: Full debug build to validate the Hilt graph**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL (both `DeviceDiagnostics` and `DiagnosticsRepository` now have debug bindings, though nothing injects them into a component yet — Task 6).

- [ ] **Step 12: Commit**

```
git add app/src/debug app/src/release app/src/testDebug
git commit -m "feat: add debug fake stack and DI bindings for diagnostics"
```

---

## Task 6: Wire the poller into the app lifecycle + docs

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/FaceVerifyApp.kt`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `DiagnosticsPoller` (Task 4).

- [ ] **Step 1: Inject and bind the poller in the Application**

Replace `FaceVerifyApp.kt` with:

```kotlin
package com.mediplus.faceverify

import android.app.Application
import com.mediplus.faceverify.core.diagnostics.DiagnosticsPoller
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. The [DiagnosticsPoller] is bound to the process lifecycle here
 * so it runs only while the app is foregrounded and the session is active.
 */
@HiltAndroidApp
class FaceVerifyApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
    }
}
```

- [ ] **Step 2: Build both variants to validate the full graph**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew assembleDebug assembleRelease
```
Expected: BUILD SUCCESSFUL for both (the poller pulls `DiagnosticsRepository` + `DeviceDiagnostics` into the graph; both variants have bindings).

- [ ] **Step 3: Run the full unit suite**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest
```
Expected: PASS (existing ~279 + the new diagnostics tests; no regressions).

- [ ] **Step 4: Run lint**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew lintDebug
```
Expected: BUILD SUCCESSFUL (Android Lint gate, `abortOnError=true`).

- [ ] **Step 5: Run detekt (CLI, as CI does) and compare against baseline**

detekt is not a Gradle task. Run the 1.23.7 CLI over `app/src/main/java` with `config/detekt/detekt.yml` (the same invocation CI uses). `main` is already ~48 issues; confirm your change adds **zero** new ones (the new code was written to the numeric limits: functions ≤ 50 lines, lines ≤ 120, `ReturnCount` ≤ 4, no bare `TODO`). If detekt flags a new-code function over 50 lines, split it further.

- [ ] **Step 6: Update `CLAUDE.md`**

In the "Current state to be aware of" section, add a bullet:

```markdown
- **Device diagnostics telemetry** (design: `docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md`):
  poll-then-report. `DiagnosticsPoller` (a `ProcessLifecycleOwner` observer) polls `GET /diagnostics/poll`
  on login + every 15 min while foregrounded; on a fresh `requestId` it collects a **permission-free**
  `DeviceStateSnapshot` (battery/network/storage/memory/display/build/app/locale/thermal/uptime — no
  hardware IDs, no location) and POSTs it to `/diagnostics`, deduping on the last-handled `requestId`.
  Best-effort throughout (all failures swallowed; no `UiMessage`, no screen). Both endpoints are
  app-invented placeholders, authenticated, same-origin with `BASE_URL`.
```

And in the device-gated list, add: `the real AndroidDeviceDiagnostics reader against real hardware sensors (battery/thermal/network transitions)`.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/mediplus/faceverify/FaceVerifyApp.kt CLAUDE.md
git commit -m "feat: run the diagnostics poller on the process lifecycle"
```

---

## Verification checklist (end state)

- [ ] `./gradlew assembleDebug assembleRelease` — both succeed.
- [ ] `./gradlew testDebugUnitTest` — full suite green, including `DiagnosticsRepositoryTest`, `PollAndReportDiagnosticsUseCaseTest`, `DiagnosticsPollerTest`, `FakeDiagnosticsRepositoryTest`, `SwitchingDiagnosticsRepositoryTest`.
- [ ] `./gradlew lintDebug` — clean.
- [ ] detekt CLI — no new issues vs the `main` baseline.
- [ ] No `android.*`/`androidx.*` framework type imported outside `core/diagnostics` (grep the use case, repository, DTOs).
- [ ] No `strings.xml` change, no `UiMessage`, no new screen.
- [ ] **Device-gated (manual, not in this plan's automated scope):** on an emulator with `DiagnosticsScenario = REQUESTED_ONCE` and `fakeEnabled` off, log in and confirm a `POST /diagnostics` fires once with a real snapshot; toggle Wi-Fi/airplane and confirm the reported `network.transport` reflects it; confirm no second report until a new `requestId`.
