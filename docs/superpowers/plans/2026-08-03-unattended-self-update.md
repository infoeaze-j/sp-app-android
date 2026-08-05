# Unattended self-update — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make published updates install on a field device that nobody opens — silently on the Sunmi V3, and with one operator tap on the Sunmi V2s — after the app has been launched once by a human.

**Architecture:** Lift the whole update orchestration out of `UpdateViewModel` into a `@Singleton UpdateCoordinator` that owns a `Mutex` and the `StateFlow<UpdatePhase>`, so a UI trigger and a WorkManager trigger run the *same* code path with one set of explicit states. The installer always asks the platform for a confirmation-free commit and reacts to whatever the platform answers, rather than branching on `SDK_INT`; when the platform demands confirmation and no human is present, a high-priority notification carries the confirmation intent and the suspended `install()` returns rather than hanging.

**Tech Stack:** Kotlin 2.3.10, AGP 9.2.1 (built-in Kotlin), Hilt 2.60.1, Coroutines 1.10.2, Compose (BOM 2025.10.01), WorkManager (new), androidx.hilt hilt-work (new). Tests: JUnit4 + MockK + Turbine, JVM only (no Robolectric in this project).

**Spec:** `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md`

---

## Global Constraints

Copied from the spec and from `CLAUDE.md`. Every task's requirements implicitly include these.

- **Fleet floor is API 30.** Sunmi V2s on Android 11 (API 30), Sunmi V3 on Android 13 (API 33). `minSdk` stays **24** — raising it is explicitly out of scope (spec, Open items).
- **The install branch must never be a `Build.VERSION.SDK_INT` test.** Always request silent; react to the platform's answer. `SDK_INT` guards are permitted *only* for API availability of a method that does not exist below that level.
- **`AppResult<T>` is the universal outcome type** — `Success`, `BusinessRejection`, `TransientFailure`, `Timeout`.
- **No user-facing free text.** `UiMessage` holds only `@StringRes` IDs; all strings live in `app/src/main/res/values/strings.xml`; `ErrorMapper` is the single `AppError → UiMessage` mapping.
- **No platform type may reach a ViewModel or the coordinator.** `android.content.Intent`, `PackageInstaller`, `NotificationManager` stay behind `core/update` seams. `BroadcastReceiver`s are platform-side and may hold them.
- **Dispatchers are injected** — `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`. Unit tests use `MainDispatcherRule` (`com.mediplus.spapp.util.MainDispatcherRule`).
- **Every flow state is explicit** — add a `UpdatePhase` variant rather than overloading one.
- **Design tokens only** in Compose — `LocalSpacing`, theme typography/colours. No hardcoded dp or colours.
- **Test-first, ≥ 80% coverage on changed code, explicit success *and* denial-path tests.**
- **detekt limits (`config/detekt/detekt.yml`, `maxIssues: 0`):** functions ≤ **50** lines, line length ≤ **120**, `ReturnCount` ≤ **4**, `TooManyFunctions` fires at **11** functions in a class (so keep classes at ≤ 10), no bare `TODO`/`FIXME`.
- **detekt baseline on `main` is 15 weighted issues** — see the table in `CLAUDE.md`. Your change must not raise it. One of the 15 is `TooManyFunctions` on `UpdateViewModel`; Task 3 removes it.
- **detekt is not a Gradle task.** Run the CLI exactly as CI does (see Verification commands below).
- **`JAVA_HOME` is not set on this machine.** Every Gradle/apksigner invocation must set it first.

### Verification commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

```bash
./gradlew testDebugUnitTest                # full JVM suite
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"
./gradlew lintDebug                        # abortOnError = true
./gradlew assembleDebug
```

detekt (download once into the scratchpad, then reuse):

```bash
./detekt-cli-1.23.7/bin/detekt-cli --config config/detekt/detekt.yml \
  --input app/src/main/java --build-upon-default-config
```

---

## Task 1: Move the phase types into `core/update` and add the new states

The coordinator will own `UpdatePhase`, so the types must stop living inside the ViewModel file. This
task is a pure move plus three new declarations — **no behaviour changes at all**, which is what makes
it a clean standalone commit and keeps Task 3's diff readable.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdatePhase.kt`
- Create: `app/src/main/java/com/mediplus/spapp/core/update/Presence.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/ui/update/UpdateViewModel.kt` (delete the moved types, add imports)
- Modify: `app/src/main/java/com/mediplus/spapp/ui/update/UpdateHost.kt` (add imports)
- Modify: `app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt` (add imports)

**Interfaces:**
- Consumes: nothing.
- Produces: `com.mediplus.spapp.core.update.UpdatePhase` (sealed interface, variants `Idle`, `CheckFailed(message: UiMessage)`, `UpdateAvailable(info: UpdateInfo, forced: Boolean)`, `PermissionNeeded(info: UpdateInfo, forced: Boolean)`, `Downloading(bytesSoFar: Long, totalBytes: Long, forced: Boolean)`, `BackingUp(forced: Boolean)`, `Installing(forced: Boolean)`, `ConfirmationPending(info: UpdateInfo, forced: Boolean)`, `Restarting`, `Failed(message: UiMessage, info: UpdateInfo, forced: Boolean, retry: RetryTarget)`); `com.mediplus.spapp.core.update.RetryTarget` (`DOWNLOAD`, `INSTALL`); `com.mediplus.spapp.core.update.Presence` (`Foreground`, `Headless`); `com.mediplus.spapp.core.update.UpdateAttempt` (`COMPLETED`, `RETRYABLE`).

- [ ] **Step 1: Create `core/update/UpdatePhase.kt`**

Cut `RetryTarget`, `UpdatePhase` and `RESTARTING_SETTLE_MILLIS` out of `ui/update/UpdateViewModel.kt`
and paste them here, adding the `ConfirmationPending` variant.

```kotlin
package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.domain.model.UpdateInfo

/** How long the restarting overlay lingers before recovering to Idle when the process survives. */
internal const val RESTARTING_SETTLE_MILLIS = 1_500L

/** Which stage a failed update attempt re-enters. INSTALL keeps the verified download. */
enum class RetryTarget { DOWNLOAD, INSTALL }

/**
 * Every observable state of the self-update flow, explicit per convention. A successful install
 * has no lasting phase: the system kills the process mid-install, so success manifests as process
 * death. [Restarting] renders only when we survive the commit (the fake dev installer, or the rare
 * real case), and then settles back to [Idle] rather than freezing on the overlay.
 *
 * Lives in `core/update` rather than beside the ViewModel because the flow is driven from two
 * places — the UI and the background worker — and both observe this one type.
 */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class CheckFailed(val message: UiMessage) : UpdatePhase
    data class UpdateAvailable(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class PermissionNeeded(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long, val forced: Boolean) : UpdatePhase
    data class BackingUp(val forced: Boolean) : UpdatePhase
    data class Installing(val forced: Boolean) : UpdatePhase

    /**
     * The platform demanded a confirmation while nobody was present, so a notification now carries
     * it. The APK is downloaded and verified; only the operator's tap is outstanding.
     */
    data class ConfirmationPending(val info: UpdateInfo, val forced: Boolean) : UpdatePhase

    data object Restarting : UpdatePhase
    data class Failed(
        val message: UiMessage,
        val info: UpdateInfo,
        val forced: Boolean,
        val retry: RetryTarget,
    ) : UpdatePhase
}
```

- [ ] **Step 2: Create `core/update/Presence.kt`**

```kotlin
package com.mediplus.spapp.core.update

/**
 * Whether a human is looking at the app right now. The install flow needs this exactly once: when
 * the platform refuses a confirmation-free commit, a foregrounded app can raise the system dialog
 * directly, while a headless one must post a notification instead (design §3).
 */
enum class Presence { Foreground, Headless }

/**
 * How an update attempt ended, from the point of view of "is it worth trying again soon".
 * [RETRYABLE] is reserved for transient transport failures and timeouts, where WorkManager's
 * exponential backoff is the right response. Every definite answer — up to date, a business
 * rejection, a corrupt APK, a commit awaiting confirmation — is [COMPLETED]: the next periodic run
 * is the correct cadence, and hammering a server that gave a clear answer helps nobody.
 */
enum class UpdateAttempt { COMPLETED, RETRYABLE }
```

- [ ] **Step 3: Delete the moved declarations from `ui/update/UpdateViewModel.kt` and import them**

Remove lines 29–56 (the `RESTARTING_SETTLE_MILLIS` const, `RetryTarget`, and `UpdatePhase`). Add:

```kotlin
import com.mediplus.spapp.core.update.RESTARTING_SETTLE_MILLIS
import com.mediplus.spapp.core.update.RetryTarget
import com.mediplus.spapp.core.update.UpdatePhase
```

- [ ] **Step 4: Add the imports to `ui/update/UpdateHost.kt` and the test**

In `UpdateHost.kt` add:

```kotlin
import com.mediplus.spapp.core.update.UpdatePhase
```

In `app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt` add:

```kotlin
import com.mediplus.spapp.core.update.RetryTarget
import com.mediplus.spapp.core.update.UpdatePhase
```

`UpdateHost.kt` does not reference `RetryTarget`, so it needs only the one import. `when (phase)` in
`UpdatePhaseSurface` is now non-exhaustive because of the new `ConfirmationPending` variant — the
compiler will say so. Add a temporary branch that renders nothing; Task 6 replaces it:

```kotlin
        // Rendered in Task 6, once the phase can actually be produced.
        is UpdatePhase.ConfirmationPending -> Unit
```

- [ ] **Step 5: Run the suite — nothing should have changed**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, same test count as before this task.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/UpdatePhase.kt \
        app/src/main/java/com/mediplus/spapp/core/update/Presence.kt \
        app/src/main/java/com/mediplus/spapp/ui/update/UpdateViewModel.kt \
        app/src/main/java/com/mediplus/spapp/ui/update/UpdateHost.kt \
        app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt
git commit -m "refactor: move the update phase types into core/update"
```

---

## Task 2: Reuse an APK that is already downloaded and verified

Spec §5. `resumableBytes()` deletes any file at or past the declared size, so a download that
*finished* but has not been installed is thrown away and fetched again in full. That was rare when
installs only happened with the app open. It becomes the common case here, because "downloaded,
waiting for a confirmation tap" is exactly where the headless flow parks.

The digest stays the sole authority on whether bytes may be installed, so this is a pure saving with
no new trust.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/data/repository/ApkTransfer.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/data/repository/UpdateRepository.kt:89-108` (`downloadAndVerify`)
- Test: `app/src/test/java/com/mediplus/spapp/data/repository/ApkTransferTest.kt` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `internal fun alreadyVerified(target: java.io.File, info: UpdateInfo): Boolean` in
  `com.mediplus.spapp.data.repository`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/spapp/data/repository/ApkTransferTest.kt`:

```kotlin
package com.mediplus.spapp.data.repository

import com.mediplus.spapp.domain.model.UpdateInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The file-and-digest arithmetic behind the resumable download. [alreadyVerified] is what stops a
 * finished-but-uninstalled APK being re-fetched every time the headless flow wakes up — the state
 * the unattended design parks in whenever the platform is waiting on a confirmation tap.
 */
class ApkTransferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun info(sizeBytes: Long, sha256: String) = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = "https://backoffice.example.com/api/v1/app/releases/7/binary",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        minSupportedVersionCode = 1,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileOf(name: String, bytes: ByteArray): File =
        tempFolder.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `a finished file whose size and digest both match is already verified`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes)

        assertTrue(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes))))
    }

    @Test
    fun `an uppercase published digest still matches`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes)

        assertTrue(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes).uppercase())))
    }

    @Test
    fun `a file of the right size but the wrong bytes is not verified`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", ByteArray(64) { 0 })

        assertFalse(alreadyVerified(target, info(64, sha256Hex(bytes))))
    }

    @Test
    fun `a partial file is not verified and is never digested as if complete`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes.copyOf(30))

        assertFalse(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes))))
    }

    @Test
    fun `a missing file is not verified`() {
        val bytes = ByteArray(64) { it.toByte() }

        assertFalse(
            alreadyVerified(File(tempFolder.root, "absent.apk"), info(64, sha256Hex(bytes))),
        )
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.ApkTransferTest"`
Expected: FAIL — compilation error, `Unresolved reference: alreadyVerified`.

- [ ] **Step 3: Implement `alreadyVerified` in `ApkTransfer.kt`**

Add below `resumableBytes` (around line 75):

```kotlin
/**
 * Whether [target] is already the finished, verified download for [info] — the same test [verified]
 * applies after a transfer, applied before starting one. Without it a completed-but-uninstalled APK
 * is deleted by [resumableBytes] and fetched again in full, which is the *normal* state of the
 * headless flow whenever it is parked waiting for a confirmation tap.
 *
 * Nothing is trusted that was not trusted before: the digest is still what decides.
 */
internal fun alreadyVerified(target: File, info: UpdateInfo): Boolean =
    target.length() == info.sizeBytes && digestOf(target).equals(info.sha256, ignoreCase = true)

private fun digestOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}
```

`File.length()` returns `0` for a missing file, so the absent case falls out of the size comparison
without touching the stream.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.ApkTransferTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Wire the short circuit into `downloadAndVerify`**

In `app/src/main/java/com/mediplus/spapp/data/repository/UpdateRepository.kt`, inside
`downloadAndVerify`, immediately after the sibling-prune line and before the `try`:

```kotlin
        // Partials for a build that is no longer the one on offer. This is the only place that
        // knows which build is actually being fetched, which is why the launch-time prune can't.
        cacheDir.listFiles()?.forEach { if (it != target) it.delete() }
        // Already fetched and already verified — the state the headless flow sits in while a
        // confirmation notification is outstanding. Re-fetching it would be pure waste.
        if (alreadyVerified(target, info)) {
            onProgress(info.sizeBytes, info.sizeBytes)
            return@withContext AppResult.Success(DownloadedApk(target, info.latestVersionCode))
        }
        try {
```

- [ ] **Step 6: Update the KDoc on the `downloadAndVerify` interface method**

In the same file, append to the KDoc block at lines 43–54:

```kotlin
     * A file that is already complete and already matches the published digest is returned as-is,
     * without a request — the normal state whenever an install is waiting on an operator tap.
```

- [ ] **Step 7: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/data/repository/ApkTransfer.kt \
        app/src/main/java/com/mediplus/spapp/data/repository/UpdateRepository.kt \
        app/src/test/java/com/mediplus/spapp/data/repository/ApkTransferTest.kt
git commit -m "perf: reuse an update APK that is already downloaded and verified"
```

---

## Task 3: Extract `UpdateCoordinator` and `UpdatePipeline`

Spec §1. The orchestration moves into a `@Singleton` so the UI and the worker share one code path,
one `StateFlow`, and one `Mutex`. **Behaviour is unchanged in this task** — including the backup
gate, which Task 4 removes separately so its denial-path tests stand on their own.

The work is split across two classes on purpose. Everything in one class lands at 12+ functions and
trips `TooManyFunctions` (threshold 11), which is one of the 15 baseline detekt issues this
refactor is meant to *remove*, not relocate.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdateCoordinator.kt`
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/ui/update/UpdateViewModel.kt` (becomes an adapter)
- Test: `app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt` (create — inherits the cases from `UpdateViewModelTest`)
- Test: `app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt` (rewrite — adapter behaviour only)

**Interfaces:**
- Consumes: `UpdatePhase`, `RetryTarget`, `Presence`, `UpdateAttempt`, `RESTARTING_SETTLE_MILLIS` (Task 1).
- Produces:
  - `class UpdateCoordinator @Inject constructor(checkForUpdate: CheckForUpdateUseCase, updateRepository: UpdateRepository, installer: ApkInstaller, backupStore: ApkBackupStore, errorMapper: ErrorMapper, currentVersion: CurrentAppVersion, pipeline: UpdatePipeline)` with
    `val phase: StateFlow<UpdatePhase>`,
    `suspend fun runUpdate(presence: Presence): UpdateAttempt`,
    `suspend fun accept()`, `fun dismiss()`, `suspend fun retry()`, `suspend fun returnedFromSettings()`,
    `fun needsLegacyWritePermission(): Boolean`.
  - `internal fun interface PhaseSink { fun emit(phase: UpdatePhase) }`
  - `internal data class PipelineResult(val attempt: UpdateAttempt, val downloaded: DownloadedApk?)`
  - `class UpdatePipeline @Inject constructor(updateRepository: UpdateRepository, installer: ApkInstaller, backupStore: ApkBackupStore, errorMapper: ErrorMapper, currentVersion: CurrentAppVersion)` with
    `internal suspend fun run(info: UpdateInfo, forced: Boolean, from: RetryTarget, kept: DownloadedApk?, sink: PhaseSink): PipelineResult`.
  - `internal fun messageFor(errorMapper: ErrorMapper, result: AppResult<*>): UiMessage` — a top-level function in `UpdatePipeline.kt`, deliberately not a class member so it does not count against `TooManyFunctions`.

- [ ] **Step 1: Write the failing coordinator test**

Create `app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt`. This is
`UpdateViewModelTest` retargeted at the coordinator, plus the two cases the coordinator adds
(mutual exclusion, and headless auto-accept).

```kotlin
package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The self-update orchestration, now shared by the UI and the background worker
 * (design: 2026-08-03-unattended-self-update-design.md). Inherits the rules the ViewModel used to
 * own — housekeeping runs once, the check fails open, forced updates cannot be dismissed, retries
 * re-enter at the right stage — and adds the two the coordinator introduces: only one attempt runs
 * at a time, and a headless attempt accepts on the operator's behalf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = mockk<UpdateRepository>()
    private val installer = mockk<ApkInstaller>()
    private val backupStore = mockk<ApkBackupStore>()
    private val errorMapper = DefaultErrorMapper()
    private val currentVersion = CurrentAppVersion(code = 5, name = "1.4")

    private lateinit var apkFile: File

    @Before
    fun setUp() {
        apkFile = tempFolder.newFile("update-v7.apk")
        coJustRun { repository.pruneObsoleteDownloads() }
        coJustRun { installer.abandonStaleSessions() }
        coJustRun { backupStore.pruneStaleBackups(any()) }
        coEvery { installer.canRequestInstalls() } returns true
        every { backupStore.needsLegacyWritePermission() } returns false
    }

    private fun coordinator() = UpdateCoordinator(
        checkForUpdate = CheckForUpdateUseCase(repository, currentVersion, BASE_URL),
        updateRepository = repository,
        installer = installer,
        backupStore = backupStore,
        errorMapper = errorMapper,
        currentVersion = currentVersion,
        pipeline = UpdatePipeline(repository, installer, backupStore, errorMapper, currentVersion),
    )

    private fun info(latest: Int = 7, minSupported: Int = 1) = UpdateInfo(
        latestVersionCode = latest,
        latestVersionName = "1.6",
        apkUrl = "${BASE_URL}app/releases/$latest/binary",
        sha256 = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
        sizeBytes = 100,
        minSupportedVersionCode = minSupported,
    )

    private fun serverSays(vararg results: AppResult<UpdateInfo?>) {
        coEvery { repository.fetchVersionInfo() } returnsMany results.toList()
    }

    private fun downloadSucceeds() {
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            onProgress(100, 100)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
    }

    private fun backupSucceeds() {
        coEvery { backupStore.backupCurrentApk(any()) } returns AppResult.Success(Unit)
    }

    private fun businessMessage(code: BusinessCode) =
        errorMapper.toUserMessage(AppError.Business(code))

    @Test
    fun `up to date stays idle and runs launch housekeeping once`() = runTest {
        serverSays(AppResult.Success(null), AppResult.Success(null))
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Foreground)
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
        coVerify(exactly = 1) { backupStore.pruneStaleBackups(5) }
        coVerify(exactly = 1) { installer.abandonStaleSessions() }
        coVerify(exactly = 1) { repository.pruneObsoleteDownloads() }
    }

    @Test
    fun `a failed check surfaces a dismissible notice and is retryable work`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(UpdateAttempt.RETRYABLE, attempt)
        val phase = coordinator.phase.value as UpdatePhase.CheckFailed
        assertEquals(
            errorMapper.toUserMessage(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            phase.message,
        )
    }

    @Test
    fun `a timed out check is retryable work`() = runTest {
        serverSays(AppResult.Timeout)
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.RETRYABLE, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `nothing to install is completed work, not retryable`() = runTest {
        serverSays(AppResult.Success(null))
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.COMPLETED, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `dismissing the check notice returns to idle`() = runTest {
        serverSays(AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.dismiss()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `retrying a failed check re-checks`() = runTest {
        serverSays(
            AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY)),
            AppResult.Success(null),
        )
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.retry()
        advanceUntilIdle()

        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
        coVerify(exactly = 2) { repository.fetchVersionInfo() }
    }

    @Test
    fun `a foreground attempt stops at the offer and waits for the operator`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        assertEquals(UpdatePhase.UpdateAvailable(info(), forced = false), coordinator.phase.value)
        coVerify(exactly = 0) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `a headless attempt accepts on the operator's behalf`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerifyOrder {
            repository.downloadAndVerify(info(), any())
            backupStore.backupCurrentApk(currentVersion)
            installer.install(apkFile)
        }
    }

    @Test
    fun `a second attempt is skipped while one is already running`() = runTest {
        serverSays(AppResult.Success(null), AppResult.Success(null))
        val coordinator = coordinator()
        coEvery { repository.fetchVersionInfo() } coAnswers {
            kotlinx.coroutines.delay(1_000)
            AppResult.Success(null)
        }

        val first = launch { coordinator.runUpdate(Presence.Headless) }
        runCurrent()
        val second = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()
        first.join()

        // The overlapping call returns immediately rather than queueing a duplicate attempt.
        assertEquals(UpdateAttempt.COMPLETED, second)
        coVerify(exactly = 1) { repository.fetchVersionInfo() }
    }

    @Test
    fun `a forced update cannot be dismissed`() = runTest {
        serverSays(AppResult.Success(info(latest = 7, minSupported = 7)))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.dismiss()

        assertEquals(
            UpdatePhase.UpdateAvailable(info(latest = 7, minSupported = 7), forced = true),
            coordinator.phase.value,
        )
    }

    @Test
    fun `accepting without install permission routes through settings and resumes`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()
        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)

        coEvery { installer.canRequestInstalls() } returns true
        coordinator.returnedFromSettings()
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
    }

    @Test
    fun `returning from settings without the grant stays put`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()

        coordinator.returnedFromSettings()
        advanceUntilIdle()

        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)
    }

    @Test
    fun `download progress reaches the phase as it streams`() = runTest {
        serverSays(AppResult.Success(info()))
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val seen = mutableListOf<UpdatePhase>()
        lateinit var coordinator: UpdateCoordinator
        coEvery { repository.downloadAndVerify(any(), any()) } coAnswers {
            val onProgress = secondArg<suspend (Long, Long) -> Unit>()
            onProgress(50, 100)
            seen.add(coordinator.phase.value)
            onProgress(100, 100)
            seen.add(coordinator.phase.value)
            AppResult.Success(DownloadedApk(apkFile, 7))
        }
        coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        assertEquals(
            listOf<UpdatePhase>(
                UpdatePhase.Downloading(50, 100, forced = false),
                UpdatePhase.Downloading(100, 100, forced = false),
            ),
            seen,
        )
    }

    @Test
    fun `a committed install shows restarting then settles back to idle`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        runCurrent()
        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)

        advanceUntilIdle()
        assertEquals(UpdatePhase.Idle, coordinator.phase.value)
    }

    @Test
    fun `a corrupted download fails with a fresh-download retry and is completed work`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_CORRUPTED))
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(UpdateAttempt.COMPLETED, attempt)
        assertEquals(
            UpdatePhase.Failed(
                message = businessMessage(BusinessCode.UPDATE_CORRUPTED),
                info = info(),
                forced = false,
                retry = RetryTarget.DOWNLOAD,
            ),
            coordinator.phase.value,
        )

        coordinator.retry()
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `an interrupted download is retryable work`() = runTest {
        serverSays(AppResult.Success(info()))
        coEvery { repository.downloadAndVerify(any(), any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.DOWNLOAD_INTERRUPTED))
        val coordinator = coordinator()

        assertEquals(UpdateAttempt.RETRYABLE, coordinator.runUpdate(Presence.Headless))
    }

    @Test
    fun `a backup failure blocks the install`() = runTest {
        // Task 4 removes this gate; it is asserted here so the extraction is provably behaviour-free.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_BACKUP_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
        coVerify(exactly = 0) { installer.install(any()) }
    }

    @Test
    fun `an aborted install keeps the download and retries without re-downloading`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.Aborted, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_ABORTED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)

        coordinator.retry()
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerify(exactly = 1) { repository.downloadAndVerify(any(), any()) }
        coVerify(exactly = 2) { installer.install(apkFile) }
    }

    @Test
    fun `retrying an install after cache eviction re-downloads`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.Aborted, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()
        coordinator.accept()
        advanceUntilIdle()

        assertTrue(apkFile.delete())
        coordinator.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.downloadAndVerify(any(), any()) }
    }

    @Test
    fun `any other install failure is retryable at the install stage`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_FAILED), phase.message)
        assertEquals(RetryTarget.INSTALL, phase.retry)
    }

    private companion object {
        const val BASE_URL = "https://backoffice.example.com/api/v1/"
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: FAIL — compilation error, `Unresolved reference: UpdateCoordinator`.

- [ ] **Step 3: Create `core/update/UpdatePipeline.kt`**

```kotlin
package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.result.appErrorOrNull
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Where the pipeline publishes progress. Keeps the phase's single owner in [UpdateCoordinator]. */
internal fun interface PhaseSink {
    fun emit(phase: UpdatePhase)
}

/**
 * What one download-and-install run produced. [downloaded] is handed back so the coordinator can
 * keep a verified APK for a retry that skips the transfer.
 */
internal data class PipelineResult(val attempt: UpdateAttempt, val downloaded: DownloadedApk?)

/**
 * The linear half of the update journey: download -> backup -> install. Split out of
 * [UpdateCoordinator] so neither class carries enough functions to trip detekt's `TooManyFunctions`
 * — the very issue this refactor is meant to retire rather than relocate.
 *
 * Holds no state: everything it needs arrives as arguments, and everything it produces leaves
 * through [PhaseSink] and [PipelineResult]. That is what lets the coordinator own the mutex alone.
 */
@Singleton
class UpdatePipeline @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val installer: ApkInstaller,
    private val backupStore: ApkBackupStore,
    private val errorMapper: ErrorMapper,
    private val currentVersion: CurrentAppVersion,
) {

    /**
     * Runs from [from]: `DOWNLOAD` always transfers, `INSTALL` reuses [kept] when it still exists
     * (the OS may evict the cache between a failure and the retry).
     */
    internal suspend fun run(
        info: UpdateInfo,
        forced: Boolean,
        from: RetryTarget,
        kept: DownloadedApk?,
        sink: PhaseSink,
    ): PipelineResult =
        if (from == RetryTarget.INSTALL && kept != null && kept.file.exists()) {
            backupAndInstall(info, forced, kept, sink)
        } else {
            download(info, forced, sink)
        }

    private suspend fun download(info: UpdateInfo, forced: Boolean, sink: PhaseSink): PipelineResult {
        sink.emit(UpdatePhase.Downloading(0, info.sizeBytes, forced))
        val result = updateRepository.downloadAndVerify(info) { bytes, total ->
            sink.emit(UpdatePhase.Downloading(bytes, total, forced))
        }
        return when (result) {
            is AppResult.Success -> backupAndInstall(info, forced, result.data, sink)
            else -> {
                sink.emit(
                    UpdatePhase.Failed(
                        messageFor(errorMapper, result),
                        info,
                        forced,
                        RetryTarget.DOWNLOAD,
                    ),
                )
                PipelineResult(attemptFor(result), downloaded = null)
            }
        }
    }

    private suspend fun backupAndInstall(
        info: UpdateInfo,
        forced: Boolean,
        apk: DownloadedApk,
        sink: PhaseSink,
    ): PipelineResult {
        sink.emit(UpdatePhase.BackingUp(forced))
        val backup = backupStore.backupCurrentApk(currentVersion)
        if (backup !is AppResult.Success) {
            sink.emit(
                UpdatePhase.Failed(messageFor(errorMapper, backup), info, forced, RetryTarget.INSTALL),
            )
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        sink.emit(UpdatePhase.Installing(forced))
        val outcome = installer.install(apk.file)
        if (outcome == InstallOutcome.Committed) {
            settleAfterCommit(sink)
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        val code = if (outcome == InstallOutcome.Aborted) {
            BusinessCode.UPDATE_INSTALL_ABORTED
        } else {
            BusinessCode.UPDATE_INSTALL_FAILED
        }
        sink.emit(
            UpdatePhase.Failed(
                message = errorMapper.toUserMessage(AppError.Business(code)),
                info = info,
                forced = forced,
                retry = RetryTarget.INSTALL,
            ),
        )
        return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
    }

    /**
     * A real install kills this process mid-commit, so control usually never reaches here. When it
     * does — the fake dev installer (which cannot die) or the rare real case where the system
     * reports success while we survive — show [UpdatePhase.Restarting] briefly, then recover to
     * [UpdatePhase.Idle] the way a relaunched, now up-to-date build would.
     */
    private suspend fun settleAfterCommit(sink: PhaseSink) {
        sink.emit(UpdatePhase.Restarting)
        delay(RESTARTING_SETTLE_MILLIS)
        sink.emit(UpdatePhase.Idle)
    }
}

/**
 * Only a transport-level failure is worth an early retry; a definite answer from the server —
 * including a corrupt APK — waits for the next scheduled run.
 */
internal fun attemptFor(result: AppResult<*>): UpdateAttempt = when (result) {
    is AppResult.TransientFailure, AppResult.Timeout -> UpdateAttempt.RETRYABLE
    else -> UpdateAttempt.COMPLETED
}

/** Top-level on purpose: a member here would push both classes over detekt's function threshold. */
internal fun messageFor(errorMapper: ErrorMapper, result: AppResult<*>): UiMessage =
    errorMapper.toUserMessage(
        result.appErrorOrNull() ?: AppError.Transient(TransientKind.UNKNOWN),
    )
```

- [ ] **Step 4: Create `core/update/UpdateCoordinator.kt`**

```kotlin
package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the self-update journey
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §1).
 *
 * A `@Singleton` rather than a ViewModel because the flow has two callers now — [UpdateViewModel]
 * when the operator has the app open, and the background worker when nobody does — and both must
 * run *the same* code and publish to *the same* [phase]. Routing worker state through WorkManager's
 * untyped `Data` bundle instead would have broken the sealed-`…Phase` convention, pushed a platform
 * type into the UI layer, and cost the JVM-testability of the whole flow.
 *
 * The mutex is a `tryLock`, not a `withLock`: an overlapping trigger is *skipped*, not queued. Two
 * queued attempts would mean a second full download-and-install immediately after the first, which
 * is never what either caller wants.
 */
@Singleton
class UpdateCoordinator @Inject constructor(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val updateRepository: UpdateRepository,
    private val installer: ApkInstaller,
    private val backupStore: ApkBackupStore,
    private val errorMapper: ErrorMapper,
    private val currentVersion: CurrentAppVersion,
    private val pipeline: UpdatePipeline,
) {

    private val _phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = _phase.asStateFlow()

    private val attemptLock = Mutex()
    private val sink = PhaseSink { next -> _phase.value = next }

    private var housekeepingDone = false
    private var downloaded: DownloadedApk? = null

    /**
     * Launch housekeeping (once per process), then the version check. A [Presence.Headless] attempt
     * accepts on the operator's behalf and runs straight through to the install; a
     * [Presence.Foreground] one stops at the offer and waits for [accept].
     */
    suspend fun runUpdate(presence: Presence): UpdateAttempt {
        if (!attemptLock.tryLock()) return UpdateAttempt.COMPLETED
        try {
            housekeepingOnce()
            val checked = runCheck()
            val offer = _phase.value as? UpdatePhase.UpdateAvailable
            return when {
                checked != UpdateAttempt.COMPLETED -> checked
                offer == null -> UpdateAttempt.COMPLETED
                presence == Presence.Foreground -> UpdateAttempt.COMPLETED
                else -> advance(offer.info, offer.forced, RetryTarget.DOWNLOAD)
            }
        } finally {
            attemptLock.unlock()
        }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = backupStore.needsLegacyWritePermission()

    suspend fun accept() {
        val offer = _phase.value as? UpdatePhase.UpdateAvailable ?: return
        advance(offer.info, offer.forced, RetryTarget.DOWNLOAD)
    }

    fun dismiss() {
        _phase.value = when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> UpdatePhase.Idle
            is UpdatePhase.UpdateAvailable -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.PermissionNeeded -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.Failed -> if (current.forced) current else UpdatePhase.Idle
            else -> current
        }
    }

    suspend fun retry() {
        when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> runCheck()
            is UpdatePhase.Failed -> advance(current.info, current.forced, current.retry)
            else -> Unit
        }
    }

    suspend fun returnedFromSettings() {
        val current = _phase.value as? UpdatePhase.PermissionNeeded ?: return
        if (installer.canRequestInstalls()) advance(current.info, current.forced, RetryTarget.DOWNLOAD)
    }

    /**
     * Pruning runs here — and never on a failure path — so a failed update keeps its whole rollback
     * chain. The download prune is narrow by design: a partial download for a build still on offer
     * survives it, which is what lets an interrupted transfer resume after a restart.
     */
    private suspend fun housekeepingOnce() {
        if (housekeepingDone) return
        housekeepingDone = true
        backupStore.pruneStaleBackups(currentVersion.code)
        installer.abandonStaleSessions()
        updateRepository.pruneObsoleteDownloads()
    }

    private suspend fun runCheck(): UpdateAttempt {
        val result = checkForUpdate()
        if (result !is AppResult.Success) {
            _phase.value = UpdatePhase.CheckFailed(messageFor(errorMapper, result))
            return attemptFor(result)
        }
        _phase.value = when (val status = result.data) {
            UpdateStatus.UpToDate -> UpdatePhase.Idle
            is UpdateStatus.Optional -> UpdatePhase.UpdateAvailable(status.info, forced = false)
            is UpdateStatus.Forced -> UpdatePhase.UpdateAvailable(status.info, forced = true)
        }
        return UpdateAttempt.COMPLETED
    }

    private suspend fun advance(info: UpdateInfo, forced: Boolean, from: RetryTarget): UpdateAttempt {
        if (!installer.canRequestInstalls()) {
            _phase.value = UpdatePhase.PermissionNeeded(info, forced)
            return UpdateAttempt.COMPLETED
        }
        val result = pipeline.run(info, forced, from, downloaded, sink)
        result.downloaded?.let { downloaded = it }
        return result.attempt
    }
}
```

- [ ] **Step 5: Run the coordinator test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: PASS (20 tests).

- [ ] **Step 6: Reduce `UpdateViewModel` to an adapter**

Replace the whole body of `app/src/main/java/com/mediplus/spapp/ui/update/UpdateViewModel.kt` with:

```kotlin
package com.mediplus.spapp.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.core.update.Presence
import com.mediplus.spapp.core.update.UpdateCoordinator
import com.mediplus.spapp.core.update.UpdatePhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The UI's adapter over [UpdateCoordinator]. Deliberately thin: the orchestration is shared with the
 * background worker, so it lives in the coordinator and this class only supplies a `viewModelScope`
 * and translates operator gestures. Every phase the operator sees comes straight off the
 * coordinator's flow, whichever caller produced it.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val coordinator: UpdateCoordinator,
) : ViewModel() {

    val phase: StateFlow<UpdatePhase> = coordinator.phase

    init {
        viewModelScope.launch { coordinator.runUpdate(Presence.Foreground) }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = coordinator.needsLegacyWritePermission()

    fun onUpdateAccepted() {
        viewModelScope.launch { coordinator.accept() }
    }

    fun onDismissed() = coordinator.dismiss()

    fun onRetry() {
        viewModelScope.launch { coordinator.retry() }
    }

    fun onReturnedFromSettings() {
        viewModelScope.launch { coordinator.returnedFromSettings() }
    }
}
```

Note `onLegacyWriteDenied()` is **gone**. Task 4 removes its caller; until then `UpdateHost.kt`
will not compile. Fix it now with the Task 4 behaviour, which is the whole point of the change:

In `UpdateHost.kt` replace the launcher callback (lines 51–55) with:

```kotlin
    // The backup is best effort, never a gate: a denial skips it and installs anyway
    // (design 2026-08-03 §6). Rollback is a manual procedure, while a device stranded on a stale
    // build with nobody present to notice is unrecoverable.
    val legacyWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> viewModel.onUpdateAccepted() }
```

- [ ] **Step 7: Rewrite `UpdateViewModelTest` as adapter tests**

Replace `app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt` entirely:

```kotlin
package com.mediplus.spapp.ui.update

import com.mediplus.spapp.core.update.Presence
import com.mediplus.spapp.core.update.UpdateAttempt
import com.mediplus.spapp.core.update.UpdateCoordinator
import com.mediplus.spapp.core.update.UpdatePhase
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ViewModel is now an adapter over [UpdateCoordinator]: it supplies a scope, forwards gestures,
 * and re-exposes the coordinator's flow. The orchestration itself is covered by
 * `UpdateCoordinatorTest`, because the worker runs the same code and must not need a ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val coordinator = mockk<UpdateCoordinator>()
    private val phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)

    @Before
    fun setUp() {
        every { coordinator.phase } returns phase
        coEvery { coordinator.runUpdate(any()) } returns UpdateAttempt.COMPLETED
        coEvery { coordinator.accept() } returns Unit
        coEvery { coordinator.retry() } returns Unit
        coEvery { coordinator.returnedFromSettings() } returns Unit
        justRun { coordinator.dismiss() }
        every { coordinator.needsLegacyWritePermission() } returns false
    }

    @Test
    fun `opening the app runs a foreground update attempt`() = runTest {
        UpdateViewModel(coordinator)
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.runUpdate(Presence.Foreground) }
    }

    @Test
    fun `construction never blocks the first frame`() = runTest {
        val viewModel = UpdateViewModel(coordinator)

        assertEquals(UpdatePhase.Idle, viewModel.phase.value)
    }

    @Test
    fun `the rendered phase is the coordinator's, whichever caller produced it`() = runTest {
        val viewModel = UpdateViewModel(coordinator)

        phase.value = UpdatePhase.Installing(forced = true)

        assertEquals(UpdatePhase.Installing(forced = true), viewModel.phase.value)
    }

    @Test
    fun `accepting forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onUpdateAccepted()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.accept() }
    }

    @Test
    fun `dismissing forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)

        viewModel.onDismissed()

        verify(exactly = 1) { coordinator.dismiss() }
    }

    @Test
    fun `retrying forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onRetry()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.retry() }
    }

    @Test
    fun `returning from settings forwards to the coordinator`() = runTest {
        val viewModel = UpdateViewModel(coordinator)
        advanceUntilIdle()

        viewModel.onReturnedFromSettings()
        advanceUntilIdle()

        coVerify(exactly = 1) { coordinator.returnedFromSettings() }
    }

    @Test
    fun `the legacy write question is answered by the coordinator`() = runTest {
        every { coordinator.needsLegacyWritePermission() } returns true
        val viewModel = UpdateViewModel(coordinator)

        assertEquals(true, viewModel.needsLegacyWritePermission())
    }
}
```

- [ ] **Step 8: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Confirm detekt lost the `TooManyFunctions` issue**

Run the detekt CLI (see Verification commands).
Expected: **14** weighted issues, with no `UpdateViewModel` `TooManyFunctions` line. If a *new*
issue appears on `UpdateCoordinator` or `UpdatePipeline`, split further rather than suppressing.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/UpdateCoordinator.kt \
        app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt \
        app/src/main/java/com/mediplus/spapp/ui/update/UpdateViewModel.kt \
        app/src/main/java/com/mediplus/spapp/ui/update/UpdateHost.kt \
        app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt \
        app/src/test/java/com/mediplus/spapp/ui/update/UpdateViewModelTest.kt
git commit -m "refactor: extract UpdateCoordinator so the UI and a worker share one update path"
```

---

## Task 4: The backup is best-effort, never a gate

Spec §6. Headless, on a device with a full storage volume, "no backup, no install, ever" stops every
future update permanently with nobody present to notice. Rollback is already a manual procedure
(uninstall, then install the backup by hand), so its practical value on a device nobody will touch
is limited — while a silently stranded device is real and unrecoverable.

Task 3 already removed the *second* blocking route (`onLegacyWriteDenied`). This task removes the
first, in `UpdatePipeline.backupAndInstall`.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/result/ErrorMapper.kt` (KDoc only — see step 4)
- Test: `app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt`

**Interfaces:**
- Consumes: `UpdatePipeline.run` (Task 3).
- Produces: no signature changes.

- [ ] **Step 1: Replace the gate test with the two denial-path tests**

In `UpdateCoordinatorTest.kt`, delete the test `a backup failure blocks the install` and add:

```kotlin
    @Test
    fun `a failed backup does not block the install`() = runTest {
        // Headless, "no backup, no install, ever" strands a device on a stale build permanently,
        // with nobody present to notice. Rollback is a manual procedure; a stranded device is not
        // recoverable at all (design 2026-08-03 §6).
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerify(exactly = 1) { installer.install(apkFile) }
    }

    @Test
    fun `a failed backup still walks through the backing up phase`() = runTest {
        // The attempt is still made and still visible; only its power of veto is gone.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Committed
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        runCurrent()

        coVerifyOrder {
            backupStore.backupCurrentApk(currentVersion)
            installer.install(apkFile)
        }
    }

    @Test
    fun `an install failure after a failed backup still reports the install failure`() = runTest {
        // The backup's own error must not shadow the real one — UPDATE_BACKUP_FAILED no longer
        // reaches the operator at all.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        coEvery { backupStore.backupCurrentApk(any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED))
        coEvery { installer.install(any()) } returns InstallOutcome.Failed("INSTALL_FAILED_INVALID_APK")
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        val phase = coordinator.phase.value as UpdatePhase.Failed
        assertEquals(businessMessage(BusinessCode.UPDATE_INSTALL_FAILED), phase.message)
    }
```

- [ ] **Step 2: Run them to verify the first two fail**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: FAIL — `a failed backup does not block the install` fails with
`expected:<Restarting> but was:<Failed(...)>`, and `a failed backup still walks through the backing
up phase` fails on the unverified `installer.install` call. The third test passes already; it is
there to prove the fix does not swallow a *real* install failure.

- [ ] **Step 3: Remove the gate in `UpdatePipeline.backupAndInstall`**

Replace the early-return block:

```kotlin
        sink.emit(UpdatePhase.BackingUp(forced))
        val backup = backupStore.backupCurrentApk(currentVersion)
        if (backup !is AppResult.Success) {
            sink.emit(
                UpdatePhase.Failed(messageFor(errorMapper, backup), info, forced, RetryTarget.INSTALL),
            )
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        sink.emit(UpdatePhase.Installing(forced))
```

with:

```kotlin
        sink.emit(UpdatePhase.BackingUp(forced))
        // Best effort, never a gate (design 2026-08-03 §6). The result is deliberately discarded:
        // rollback is already a manual procedure (uninstall, then install the backup by hand), so a
        // missing backup costs convenience — while refusing to install leaves a field device that
        // nobody will ever open stranded on a stale build, which nothing can recover.
        backupStore.backupCurrentApk(currentVersion)
        sink.emit(UpdatePhase.Installing(forced))
```

`AppResult` and `errorMapper` may now be unused in that function; leave the imports alone — both are
still used by `download` and by `messageFor`.

- [ ] **Step 4: Record that `UPDATE_BACKUP_FAILED` is now diagnostic-only**

In `app/src/main/java/com/mediplus/spapp/core/result/ErrorMapper.kt`, above the
`BusinessCode.UPDATE_BACKUP_FAILED` branch inside `updateMessage` (line 140), add:

```kotlin
        // Retained as a diagnostic code only: since the 2026-08-03 unattended-update design the
        // backup is best effort and no longer blocks an install, so nothing routes this to the
        // operator. The mapping stays so the exhaustive `when` over BusinessCode still compiles.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: PASS (22 tests).

- [ ] **Step 6: Run the full suite and lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: PASS. Lint may now flag `err_update_backup_failed_title`/`_body` as unused — they are
still referenced by `ErrorMapper`, so no change is expected. If lint reports `UnusedResources`,
leave the strings and add `tools:ignore` **only** if the mapping was actually deleted (it was not).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt \
        app/src/main/java/com/mediplus/spapp/core/result/ErrorMapper.kt \
        app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt
git commit -m "fix: never let a failed rollback backup block a self-update"
```

---

## Task 5: `ForegroundTracker`

Spec §3. The install branch needs one fact: is a human looking at this app right now. Follows
`DiagnosticsPoller` exactly — a `@Singleton` `ProcessLifecycleOwner` observer bound from
`SpApp.onCreate()`.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/update/ForegroundTracker.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/SpApp.kt`
- Test: `app/src/test/java/com/mediplus/spapp/core/update/ForegroundTrackerTest.kt` (create)

**Interfaces:**
- Consumes: `Presence` (Task 1).
- Produces: `class ForegroundTracker` with `fun bind()`, `fun presence(): Presence`, and the
  `DefaultLifecycleObserver` overrides `onStart(owner: LifecycleOwner)` / `onStop(owner: LifecycleOwner)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/spapp/core/update/ForegroundTrackerTest.kt`:

```kotlin
package com.mediplus.spapp.core.update

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one fact the install branch needs. `bind()` itself touches ProcessLifecycleOwner and so is
 * device-verified rather than unit-tested (same precedent as DiagnosticsPoller.bind()); the
 * callbacks it registers are plain functions and are tested here.
 */
class ForegroundTrackerTest {

    private val owner = mockk<LifecycleOwner>()

    @Test
    fun `an app that has never been foregrounded is headless`() {
        assertEquals(Presence.Headless, ForegroundTracker().presence())
    }

    @Test
    fun `foregrounding makes the app present`() {
        val tracker = ForegroundTracker()

        tracker.onStart(owner)

        assertEquals(Presence.Foreground, tracker.presence())
    }

    @Test
    fun `backgrounding makes the app headless again`() {
        val tracker = ForegroundTracker()
        tracker.onStart(owner)

        tracker.onStop(owner)

        assertEquals(Presence.Headless, tracker.presence())
    }

    @Test
    fun `repeated foregroundings do not flip the answer`() {
        val tracker = ForegroundTracker()

        tracker.onStart(owner)
        tracker.onStop(owner)
        tracker.onStart(owner)

        assertEquals(Presence.Foreground, tracker.presence())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.ForegroundTrackerTest"`
Expected: FAIL — `Unresolved reference: ForegroundTracker`.

- [ ] **Step 3: Implement `ForegroundTracker`**

```kotlin
package com.mediplus.spapp.core.update

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a human is currently looking at the app, for the one decision that turns on it: when the
 * platform refuses a confirmation-free install, a foregrounded app can raise the system dialog
 * directly, while a headless one must post a notification instead (design 2026-08-03 §3).
 *
 * A `ProcessLifecycleOwner` observer, exactly like
 * [com.mediplus.spapp.core.diagnostics.DiagnosticsPoller] — `onStart`/`onStop` fire once per
 * foregrounding of the *process*, which is the event actually meant.
 *
 * `@Volatile` because the write comes from the main thread and the read comes from a broadcast
 * receiver, which the platform may dispatch on another thread.
 */
@Singleton
class ForegroundTracker @Inject constructor() : DefaultLifecycleObserver {

    @Volatile
    private var foreground = false

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
    }

    fun presence(): Presence = if (foreground) Presence.Foreground else Presence.Headless
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.ForegroundTrackerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Bind it in `SpApp`**

```kotlin
package com.mediplus.spapp

import android.app.Application
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import com.mediplus.spapp.core.update.ForegroundTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. Three process-lifecycle observers are bound here: the
 * [DiagnosticsPoller], which runs only while the app is foregrounded and the session is active, the
 * [SessionRevalidator], which confirms on each foregrounding that an apparently-active session is
 * still live, and the [ForegroundTracker], which answers the one question the unattended install
 * path asks — is anybody there to tap a confirmation.
 */
@HiltAndroidApp
class SpApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
        foregroundTracker.bind()
    }
}
```

- [ ] **Step 6: Run the suite and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/ForegroundTracker.kt \
        app/src/main/java/com/mediplus/spapp/SpApp.kt \
        app/src/test/java/com/mediplus/spapp/core/update/ForegroundTrackerTest.kt
git commit -m "feat: track process foreground presence for the install branch"
```

---

## Task 6: `AwaitingConfirmation` — plumbing and UI

Spec §3. When the platform demands a confirmation and nobody is present, `install()` must **return**
rather than suspend forever. This task adds the outcome and the phase and renders them; Task 7 makes
the receiver actually produce them.

This is also where the latent hang is closed. `UpdateStatusReceiver.kt:51` catches `SecurityException`,
but a background activity launch on API 29+ is normally *blocked and logged*, not thrown — so
`publishLaunchFailure` would never run and `install()` would suspend indefinitely. Unreachable today
because installs only happen with the app open; a guaranteed hang the moment a worker drives the flow.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/InstallEventBus.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/ApkInstaller.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/ui/update/UpdateHost.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/spapp/core/update/InstallStatusEventTest.kt` (create)
- Test: `app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt`

**Interfaces:**
- Consumes: `UpdatePhase.ConfirmationPending` (Task 1), `UpdatePipeline.run` (Task 3).
- Produces:
  - `InstallStatusEvent(sessionId: Int, status: Int, message: String?, awaitingConfirmation: Boolean = false)`
  - `InstallOutcome.AwaitingConfirmation` — a `data object`. It deliberately carries no session id:
    nothing above the installer needs one (see Task 8, which handles session survival inside the
    platform class where it actually works).

- [ ] **Step 1: Write the failing bus test**

Create `app/src/test/java/com/mediplus/spapp/core/update/InstallStatusEventTest.kt` (named for the
type under test, not for the file the type lives in):

```kotlin
package com.mediplus.spapp.core.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminality is what releases the suspended [ApkInstaller.install] call. A pending-user-action
 * status is normally NOT terminal — the confirmation dialog is still to come — but when nobody is
 * present to see a dialog, the notification becomes the outstanding step and the install call must
 * be allowed to return. Without this the headless flow suspends forever.
 */
class InstallStatusEventTest {

    @Test
    fun `a success is terminal`() {
        val event = InstallStatusEvent(1, PackageInstaller.STATUS_SUCCESS, null)

        assertTrue(event.isTerminal)
    }

    @Test
    fun `a pending user action is not terminal on its own`() {
        val event = InstallStatusEvent(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null)

        assertFalse(event.isTerminal)
    }

    @Test
    fun `a pending user action handed to a notification is terminal`() {
        val event = InstallStatusEvent(
            sessionId = 1,
            status = PackageInstaller.STATUS_PENDING_USER_ACTION,
            message = null,
            awaitingConfirmation = true,
        )

        assertTrue(event.isTerminal)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.InstallStatusEventTest"`
Expected: FAIL — `No value passed for parameter 'awaitingConfirmation'` / unresolved named argument.

- [ ] **Step 3: Add the flag to `InstallStatusEvent`**

In `InstallEventBus.kt`, replace the data class at the bottom:

```kotlin
/**
 * One status broadcast for one install session, as delivered by the platform installer.
 *
 * [awaitingConfirmation] is set by [UpdateStatusReceiver] when a pending-user-action status arrives
 * with nobody foregrounded, so the confirmation has been handed to a notification instead of a
 * dialog. That makes the event terminal: the suspended install call returns
 * [InstallOutcome.AwaitingConfirmation] rather than waiting for a tap that may be hours away — or
 * never, which is what would otherwise hang the background worker indefinitely.
 */
data class InstallStatusEvent(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val awaitingConfirmation: Boolean = false,
) {
    val isTerminal: Boolean
        get() = awaitingConfirmation || status != PackageInstaller.STATUS_PENDING_USER_ACTION
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.InstallStatusEventTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the outcome and map it in the installer**

In `ApkInstaller.kt`, add to `InstallOutcome`:

```kotlin
    /**
     * The platform demanded a confirmation while nobody was foregrounded, so a notification now
     * carries it and this call returned instead of suspending. The install session stays open and
     * committed until the operator taps.
     */
    data object AwaitingConfirmation : InstallOutcome
```

In `PackageInstallerApkInstaller.kt`, replace the `when` inside `awaitOutcome`:

```kotlin
            val event = terminal.await()
            when {
                event.awaitingConfirmation -> InstallOutcome.AwaitingConfirmation
                event.status == PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Committed
                event.status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Aborted
                else -> InstallOutcome.Failed(event.message)
            }
```

- [ ] **Step 6: Write the failing coordinator test**

Add to `UpdateCoordinatorTest.kt`:

```kotlin
    @Test
    fun `an install awaiting confirmation parks in ConfirmationPending as completed work`() = runTest {
        // The V2s half of the fleet reaches this on every single update: API 30 can never install
        // silently, so the notification path is the primary path there, not a fallback.
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returns InstallOutcome.AwaitingConfirmation
        val coordinator = coordinator()

        val attempt = coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        // Not RETRYABLE: nothing is wrong, and re-running would re-download and re-notify.
        assertEquals(UpdateAttempt.COMPLETED, attempt)
        assertEquals(
            UpdatePhase.ConfirmationPending(info(), forced = false),
            coordinator.phase.value,
        )
    }

    @Test
    fun `retrying from ConfirmationPending re-installs without re-downloading`() = runTest {
        serverSays(AppResult.Success(info()))
        downloadSucceeds()
        backupSucceeds()
        coEvery { installer.install(any()) } returnsMany
            listOf(InstallOutcome.AwaitingConfirmation, InstallOutcome.Committed)
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        coordinator.retry()
        runCurrent()

        assertEquals(UpdatePhase.Restarting, coordinator.phase.value)
        coVerify(exactly = 1) { repository.downloadAndVerify(any(), any()) }
        coVerify(exactly = 2) { installer.install(apkFile) }
    }
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: FAIL — the phase is `Failed(UPDATE_INSTALL_FAILED)` because `AwaitingConfirmation` falls
into the installer's `else` branch.

- [ ] **Step 8: Handle the outcome in `UpdatePipeline` and make `retry` reach it**

In `UpdatePipeline.backupAndInstall`, replace the block from `val outcome = installer.install(...)`
through the `sink.emit(UpdatePhase.Failed(...))` with:

```kotlin
        val outcome = installer.install(apk.file)
        if (outcome == InstallOutcome.Committed) {
            settleAfterCommit(sink)
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        if (outcome == InstallOutcome.AwaitingConfirmation) {
            // Nothing is wrong and nothing is retryable: the APK is verified and the session is
            // live, and only an operator tap is outstanding. Re-running would re-notify, not help.
            sink.emit(UpdatePhase.ConfirmationPending(info, forced))
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        val code = if (outcome == InstallOutcome.Aborted) {
            BusinessCode.UPDATE_INSTALL_ABORTED
        } else {
            BusinessCode.UPDATE_INSTALL_FAILED
        }
        sink.emit(
            UpdatePhase.Failed(
                message = errorMapper.toUserMessage(AppError.Business(code)),
                info = info,
                forced = forced,
                retry = RetryTarget.INSTALL,
            ),
        )
        return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
```

In `UpdateCoordinator.retry()`, add the branch:

```kotlin
    suspend fun retry() {
        when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> runCheck()
            is UpdatePhase.Failed -> advance(current.info, current.forced, current.retry)
            // The operator opened the app instead of tapping the notification: raise the system
            // dialog now, which the foreground branch of UpdateStatusReceiver does directly.
            is UpdatePhase.ConfirmationPending -> advance(current.info, current.forced, RetryTarget.INSTALL)
            else -> Unit
        }
    }
```

- [ ] **Step 9: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: PASS (24 tests).

- [ ] **Step 10: Add the strings**

In `app/src/main/res/values/strings.xml`, after the `update_permission_body` line (~line 133):

```xml
    <string name="update_confirm_title">Finish the update</string>
    <string name="update_confirm_body">The update is downloaded and ready. Confirm the install to finish.</string>
    <string name="action_install_now">Install now</string>
```

- [ ] **Step 11: Render `ConfirmationPending` in `UpdateHost`**

Replace the temporary branch added in Task 1:

```kotlin
        is UpdatePhase.ConfirmationPending -> ConfirmationSurface(
            phase,
            viewModel::onRetry,
            viewModel::onDismissed,
        )
```

and add the composable beside `PermissionSurface`:

```kotlin
/**
 * The operator opened the app while an install was waiting on a notification tap. The action raises
 * the system confirmation directly, which is strictly better than sending them back to the shade.
 */
@Composable
private fun ConfirmationSurface(
    phase: UpdatePhase.ConfirmationPending,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (phase.forced) {
        UpdateOverlay(
            title = stringResource(R.string.update_confirm_title),
            body = stringResource(R.string.update_confirm_body),
            actionLabel = stringResource(R.string.action_install_now),
            onAction = onConfirm,
        )
    } else {
        UpdateDrawer(
            title = stringResource(R.string.update_confirm_title),
            body = stringResource(R.string.update_confirm_body),
            confirmLabelRes = R.string.action_install_now,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}
```

`UpdateCoordinator.dismiss()` leaves `ConfirmationPending` untouched (it falls into the `else`
branch), which is correct: dismissing the drawer should not discard a live install session. The
drawer closes because Compose re-renders; the phase persists until the install resolves.

- [ ] **Step 12: Update the debug fake so the dev stack can reach the new state**

In `app/src/debug/java/com/mediplus/spapp/dev/update/FakeApkInstaller.kt`, no change is required —
`UpdateScenario` has no awaiting-confirmation case and adding one is optional. Skip it; the state is
verified on-device in Task 13.

- [ ] **Step 13: Run the full suite, lint and detekt**

Run: `./gradlew testDebugUnitTest lintDebug`, then the detekt CLI.
Expected: PASS; detekt still **14**.

- [ ] **Step 14: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/InstallEventBus.kt \
        app/src/main/java/com/mediplus/spapp/core/update/ApkInstaller.kt \
        app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt \
        app/src/main/java/com/mediplus/spapp/core/update/UpdatePipeline.kt \
        app/src/main/java/com/mediplus/spapp/ui/update/UpdateHost.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/mediplus/spapp/core/update/InstallStatusEventTest.kt \
        app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt
git commit -m "feat: let a headless install return awaiting-confirmation instead of hanging"
```

---

## Task 7: Notifications and the receiver's presence branch

Spec §3 and §7. **The notification is the primary path for the V2s half of the fleet**, not a
fallback: API 30 can never install silently, so every V2s update ends here. Build it to that
standard — high priority, re-posted by the next worker run if dismissed, and tapping it lands
directly on the system confirmation.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdateNotifications.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/UpdateStatusReceiver.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/di/UpdateBindingsModule.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ForegroundTracker.presence()` (Task 5), `InstallStatusEvent(..., awaitingConfirmation)` (Task 6).
- Produces:
  - `interface UpdateNotifier { fun clear() }` — the seam; carries no platform type, so the
    coordinator may hold it (Task 12 adds a second method).
  - `class UpdateNotifications : UpdateNotifier` with, additionally,
    `fun confirmationRequired(sessionId: Int, confirm: android.content.Intent)`.

- [ ] **Step 1: Add the strings**

In `strings.xml`, beside the ones added in Task 6:

```xml
    <string name="update_notification_channel_name">App updates</string>
    <string name="update_notification_channel_description">Alerts when an update needs to be confirmed.</string>
    <string name="update_notification_title">Update ready to install</string>
    <string name="update_notification_body">Tap to finish installing the latest version.</string>
```

- [ ] **Step 2: Create `core/update/UpdateNotifications.kt`**

```kotlin
package com.mediplus.spapp.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mediplus.spapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The containment seam for update notifications. Only the platform-side [UpdateStatusReceiver] holds
 * the concrete class, because only it has the confirmation [Intent]; everything above the seam sees
 * this interface and no `android.*` type.
 */
interface UpdateNotifier {
    /** Removes any outstanding update notification. */
    fun clear()
}

/**
 * Posts the notification that carries a pending install confirmation
 * (design 2026-08-03 §3, §7).
 *
 * On the Sunmi V2s (API 30) this is the ONLY way an update ever completes — the platform there can
 * never commit without user action — so it is built as a primary path: high importance, auto-cancel,
 * and a content intent that lands directly on the system confirmation rather than on our own UI.
 *
 * Below API 33 no runtime grant is needed, which is exactly the half of the fleet that depends on
 * it. On API 33+ a denial degrades to "installs the next time somebody opens the app" — no worse
 * than the behaviour before this design — so the permission check returns quietly rather than
 * throwing.
 */
@Singleton
class UpdateNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateNotifier {

    fun confirmationRequired(sessionId: Int, confirm: Intent) {
        if (!canNotify()) return
        ensureChannel()
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            sessionId,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.update_notification_channel_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "sp_app_updates"
        const val NOTIFICATION_ID = 1001
    }
}
```

The small icon is a platform drawable on purpose: a notification icon must be a flat silhouette, and
`@mipmap/ic_launcher` renders as a grey blob. Replacing it with a proper `ic_stat_update` vector is a
cosmetic follow-up, not a blocker.

- [ ] **Step 3: Bind the seam**

In `app/src/main/java/com/mediplus/spapp/core/di/UpdateBindingsModule.kt`:

```kotlin
package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.update.ApkBackupStore
import com.mediplus.spapp.core.update.MediaStoreApkBackupStore
import com.mediplus.spapp.core.update.UpdateNotifications
import com.mediplus.spapp.core.update.UpdateNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Backup storage and update notifications are bound in main (not per build type): the MediaStore
 * path works on a bare emulator and a notification is not a network seam, so the debug fake stack
 * has nothing to switch here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindingsModule {

    @Binds
    @Singleton
    abstract fun bindApkBackupStore(impl: MediaStoreApkBackupStore): ApkBackupStore

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: UpdateNotifications): UpdateNotifier
}
```

- [ ] **Step 4: Branch the receiver on presence**

Replace `app/src/main/java/com/mediplus/spapp/core/update/UpdateStatusReceiver.kt` entirely:

```kotlin
package com.mediplus.spapp.core.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the install session's status broadcasts. Every terminal status is forwarded to
 * [InstallEventBus] so the suspended [ApkInstaller.install] call can return. Non-exported: only the
 * platform installer (via the mutable PendingIntent it was handed) ever targets this receiver.
 *
 * Pending-user-action is where the two halves of the fleet diverge (design 2026-08-03 §3). With the
 * app open, the system confirmation is launched directly, as before. With nobody present, a
 * background activity launch would be silently dropped — on API 29+ it is blocked and *logged*, not
 * thrown, so the old `SecurityException` catch never fires — and the install call would suspend
 * forever. Instead the confirmation goes into a notification and the event is published as terminal.
 */
@AndroidEntryPoint
class UpdateStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var bus: InstallEventBus

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    @Inject
    lateinit var notifications: UpdateNotifications

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            requestConfirmation(context, sessionId, intent)
            return
        }
        // Any terminal status settles the session, so a notification pointing at it is now stale.
        notifications.clear()
        bus.publish(
            InstallStatusEvent(
                sessionId = sessionId,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            ),
        )
    }

    private fun requestConfirmation(context: Context, sessionId: Int, intent: Intent) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            publishLaunchFailure(sessionId)
            return
        }
        when (foregroundTracker.presence()) {
            Presence.Foreground -> launchConfirmation(context, sessionId, confirm)
            Presence.Headless -> notifyConfirmation(sessionId, confirm)
        }
    }

    private fun launchConfirmation(context: Context, sessionId: Int, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            publishLaunchFailure(sessionId)
        } catch (_: SecurityException) {
            // A background-activity-launch denial that the platform chose to throw rather than drop.
            publishLaunchFailure(sessionId)
        }
    }

    /**
     * Publishes AFTER posting, so the install call cannot return and be re-driven before the
     * notification the operator needs actually exists.
     */
    private fun notifyConfirmation(sessionId: Int, confirm: Intent) {
        notifications.confirmationRequired(sessionId, confirm)
        bus.publish(
            InstallStatusEvent(
                sessionId = sessionId,
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                message = null,
                awaitingConfirmation = true,
            ),
        )
    }

    private fun publishLaunchFailure(sessionId: Int) {
        bus.publish(
            InstallStatusEvent(sessionId = sessionId, status = PackageInstaller.STATUS_FAILURE, message = null),
        )
    }
}
```

- [ ] **Step 5: Declare `POST_NOTIFICATIONS`**

In `app/src/main/AndroidManifest.xml`, after the `WRITE_EXTERNAL_STORAGE` block (line 17):

```xml
    <!-- The confirmation notification is the ONLY way an update completes on an API 30 device,
         which is half the fleet. No runtime grant is needed there; API 33+ needs this one, and a
         denial degrades to "installs next time somebody opens the app". -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 6: Build and run the suite**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`
Expected: PASS. `UpdateStatusReceiver` has no JVM test — it is a `BroadcastReceiver` and this project
has no Robolectric (the same precedent as `DiagnosticsPoller.bind()` and `SessionRevalidator.bind()`).
Its branch is verified on-device in Task 13. The *decision* it delegates to — `ForegroundTracker`
and `InstallStatusEvent.isTerminal` — is unit-tested in Tasks 5 and 6.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/UpdateNotifications.kt \
        app/src/main/java/com/mediplus/spapp/core/update/UpdateStatusReceiver.kt \
        app/src/main/java/com/mediplus/spapp/core/di/UpdateBindingsModule.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat: carry a pending install confirmation in a notification when nobody is present"
```

---

## Task 8: Keep the pending session alive

Spec §4. `abandonStaleSessions()` abandons *every* session belonging to this app at launch, which
would orphan the intent inside an outstanding notification.

**This deviates from the spec's proposed fix, deliberately.** The spec says "the coordinator records
the pending session id and the installer skips it". Trace it: `housekeepingOnce()` runs once per
process, *before* any install. So within a process the sweep always precedes the pending session and
`keepSessionId` is always `null`; across a process restart the in-memory id is gone and the sweep
abandons the session anyway. The parameter would be dead code in every reachable scenario — and it
is the cross-process case that actually matters, because every reboot is one, and on the V2s that
notification is the only way an update ever completes.

The platform already records what we need. A session waiting on a confirmation dialog is
**committed**, and `PackageInstaller.SessionInfo.isCommitted` (API 29+, and the fleet floor is 30)
reports it. Skipping committed sessions needs no state, no signature change, and works across
process death — strictly better than what the spec proposed.

Cost of the change: the logic moves into a platform class, so it loses its JVM test. That is the
right trade — a testable parameter that never fires is worse than three lines that work and are
verified on the bench. Task 13 gains a check for it.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt`
- Modify: `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md` (record the deviation)

**Interfaces:**
- Consumes: `InstallOutcome.AwaitingConfirmation` (Task 6).
- Produces: no signature changes anywhere. `ApkInstaller.abandonStaleSessions()` keeps its
  no-argument form, so `FakeApkInstaller`, `SwitchingApkInstaller`, `UpdateCoordinator` and
  `UpdatePipeline` are all untouched.

- [ ] **Step 1: Skip committed sessions in the sweep**

In `PackageInstallerApkInstaller.kt`, replace `abandonStaleSessions`:

```kotlin
    override suspend fun abandonStaleSessions(): Unit = withContext(dispatcher) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { info ->
            if (!isAwaitingConfirmation(info)) abandonQuietly(installer, info.sessionId)
        }
    }

    /**
     * A committed session is one the platform has accepted and is holding open — on this fleet,
     * because it is waiting for the operator to tap the confirmation an [UpdateNotifications]
     * notification is carrying. Abandoning it would leave a notification that does nothing, and on
     * the V2s (API 30) that notification is the ONLY way an update ever completes.
     *
     * The alternative — remembering the session id ourselves — cannot work: launch housekeeping
     * runs before any install in a process, and the id does not survive the process death that a
     * reboot causes. The platform's own record does.
     *
     * `isCommitted` is API 29+; the whole fleet is API 30+, and below 29 the old sweep-everything
     * behaviour is unchanged.
     */
    private fun isAwaitingConfirmation(info: PackageInstaller.SessionInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isCommitted
```

- [ ] **Step 2: Update the seam's KDoc to match**

In `ApkInstaller.kt`:

```kotlin
    /**
     * Abandons leftover sessions from crashed attempts; they count against a system quota. A
     * session the platform reports as committed is spared: it is waiting on a confirmation the
     * operator has been notified about, and abandoning it would make that notification inert.
     */
    suspend fun abandonStaleSessions()
```

- [ ] **Step 3: Record the deviation in the design document**

In `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md`, replace the body of
**§4 Keeping the pending session alive** with:

```markdown
A session waiting on a notification tap must survive. `abandonStaleSessions()` currently abandons
every session belonging to this app at launch, which would orphan the notification's intent.

**Revised during implementation (2026-08-03).** This section originally proposed that the
coordinator record the pending session id and the installer skip it. That does not work: launch
housekeeping runs once per process and always *before* any install, so within a process the id is
always null, and across a process restart — which every reboot is — the in-memory id is gone. The
parameter would never fire in any reachable scenario, and the cross-process case is the one that
matters, because on the V2s the notification is the only way an update completes.

The platform already holds the fact. A session awaiting confirmation is **committed**, and
`PackageInstaller.SessionInfo.isCommitted` (API 29+; the fleet floor is 30) reports it. The sweep
skips committed sessions. No new state, no signature change, and it survives process death. The
trade is that the logic now lives in a platform class and is device-verified rather than
unit-tested.

If the operator opens the app rather than tapping the notification, the foreground flow re-checks
and re-offers from `ConfirmationPending`, raising the system dialog directly.
```

- [ ] **Step 4: Run the full suite, lint and detekt**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`, then the detekt CLI.
Expected: PASS; detekt still **14**. No test changes: nothing above the installer changed, so the
existing `coJustRun { installer.abandonStaleSessions() }` stubs and the housekeeping assertion in
`UpdateCoordinatorTest` still compile and still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt \
        app/src/main/java/com/mediplus/spapp/core/update/ApkInstaller.kt \
        docs/superpowers/specs/2026-08-03-unattended-self-update-design.md
git commit -m "fix: never abandon an install session awaiting operator confirmation"
```

---

## Task 9: Request the confirmation-free install

Spec §3. **Always request silent, and let the system decide.** A `SDK_INT` branch would assume
silence on a device whose modified Android does not deliver it, never receive a terminal status, and
suspend forever. The `SDK_INT` guard below is for *API availability only* — `setRequireUserAction`
does not exist before API 31.

`UPDATE_PACKAGES_WITHOUT_USER_ACTION` keys off being the *installer of record*. A sideloaded app is
not — the shell is. The first self-install promotes the app, so the realistic worst case is "the
first update needs one tap, every update after it is silent". Task 13 turns that into a bench step.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing new.
- Produces: no signature changes.

- [ ] **Step 1: Request it in `sessionParams`**

```kotlin
    /**
     * Always asks for a confirmation-free commit and lets the platform answer, rather than deciding
     * from [Build.VERSION.SDK_INT] whether it is available (design 2026-08-03 §3). Sunmi ships
     * modified Android: a V3 reporting API 33 may still refuse, and a version check that assumed
     * silence would wait for a terminal status that never arrives.
     *
     * The guard here is API availability only — `setRequireUserAction` does not exist before
     * API 31. Below that the request is simply not made and the platform answers
     * `STATUS_PENDING_USER_ACTION`, which is the whole of the V2s path.
     */
    private fun sessionParams(apk: File) =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
```

- [ ] **Step 2: Replace the stale class KDoc**

The KDoc at lines 21–29 still says the confirmation-free path is "deliberately NOT enabled yet".
Replace the second paragraph with:

```kotlin
 * Every commit requests `USER_ACTION_NOT_REQUIRED` (API 31+) and reacts to whatever the platform
 * answers — a terminal status means it installed unattended, `STATUS_PENDING_USER_ACTION` means a
 * human is needed. Nothing here branches on the OS version to predict which. The permission that
 * makes silence possible keys off being the installer of record, which this app becomes only after
 * it has installed itself once, so the first self-update on a sideloaded device may still ask.
```

- [ ] **Step 3: Declare the permission**

In `AndroidManifest.xml`, replace the self-update permission comment block (lines 10–12):

```xml
    <!-- Self-update: stream the verified APK into a PackageInstaller session (sideloaded fleet,
         no Play). -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <!-- Normal permission, auto-granted, inert below API 31. Lets an app that is its own installer
         of record commit without the confirmation screen — the Sunmi V3 path. The V2s (API 30)
         always falls through to the confirmation notification instead. -->
    <uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />
```

- [ ] **Step 4: Verify the merged manifest carries both**

Run: `./gradlew assembleDebug`
Then:

```bash
grep -n "UPDATE_PACKAGES_WITHOUT_USER_ACTION\|POST_NOTIFICATIONS" \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: both `uses-permission` lines present.

- [ ] **Step 5: Run the suite and lint**

Run: `./gradlew testDebugUnitTest lintDebug`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/PackageInstallerApkInstaller.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: always request a confirmation-free install and react to the platform's answer"
```

---

## Task 10: `UpdateWorker` and the WorkManager schedule

Spec §2. A `@HiltWorker CoroutineWorker` whose entire body is
`coordinator.runUpdate(Presence.Headless)` mapped to a `Result`.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdateWorker.kt`
- Create: `app/src/main/java/com/mediplus/spapp/core/update/UpdateScheduler.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/SpApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UpdateCoordinator.runUpdate(Presence): UpdateAttempt` (Task 3).
- Produces: `class UpdateScheduler` with `fun schedule()` and
  `companion object { const val UNIQUE_WORK_NAME = "sp-app-self-update" }`; `class UpdateWorker`.

- [ ] **Step 1: Add the dependencies**

In `gradle/libs.versions.toml`, under `[versions]` after `datastore`:

```toml
# Background work (unattended self-update)
work = "2.10.0"
androidxHilt = "1.3.0"
```

Under `[libraries]` after the storage block:

```toml
# Background work
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidxHilt" }
```

`hilt-navigation-compose` already resolves at `1.3.0` in this project's Gradle cache, so the
`androidx.hilt` train is confirmed at that version. WorkManager is not cached — **verify it resolves
in step 3 and bump the patch if it does not.**

In `app/build.gradle.kts`, after the Hilt block (line ~151):

```kotlin
    // Background work (unattended self-update)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
```

- [ ] **Step 2: Create `core/update/UpdateWorker.kt`**

```kotlin
package com.mediplus.spapp.core.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the update journey with nobody watching
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §2).
 *
 * Deliberately has no logic of its own: it calls the same [UpdateCoordinator] the UI does, so there
 * is exactly one orchestration to reason about and to test. The mapping below is the whole of the
 * worker's contribution, and the decision behind it lives in [UpdateAttempt] — only a transport
 * failure earns WorkManager's backoff; every definite answer waits for the next periodic run.
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UpdateCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (coordinator.runUpdate(Presence.Headless)) {
        UpdateAttempt.RETRYABLE -> Result.retry()
        UpdateAttempt.COMPLETED -> Result.success()
    }
}
```

There is no `UpdateWorkerTest`: `TestListenableWorkerBuilder` needs a real `Context`, and this
project has no Robolectric. The behaviour being asserted — that a transient failure or timeout maps
to a retry and every definite answer does not — is covered by `UpdateCoordinatorTest`'s
`UpdateAttempt` assertions, which is where the decision actually lives. The `when` above is total
over a two-value enum and cannot drift.

- [ ] **Step 3: Create `core/update/UpdateScheduler.kt`**

```kotlin
package com.mediplus.spapp.core.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the update work schedule, and the `androidx.work` types with it — nothing above this class
 * sees a WorkManager type.
 *
 * `KEEP` rather than `UPDATE`, and re-enqueued on every process start: the call is idempotent, and
 * repeating it self-heals a schedule that an OEM's aggressive task killer dropped. On these
 * custom-OEM Sunmi builds that is a real failure class, and it is invisible until the fleet has
 * already gone stale.
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sp-app-self-update"
        private const val INTERVAL_HOURS = 6L
    }
}
```

> **Correction (2026-08-05, verified against work-runtime 2.10.0).** The KDoc above is wrong and was
> not shipped as written; see the class for the corrected text. `KEEP` makes the repeated enqueue
> *harmless*, not curative — an existing enqueued `WorkSpec` survives a killed job, so the enqueue
> finds a row and no-ops in the very scenario "self-heals a schedule that an OEM's aggressive task
> killer dropped" credits it with. What actually recovers the schedule is `ForceStopRunnable`, which
> `WorkManagerImpl`'s constructor dispatches. Because Step 5 removes the `androidx.startup`
> initializer, that constructor only runs on the first `WorkManager.getInstance(...)` — the call
> inside `schedule()`, the only one in the app — so the recovery happens *because* we ask, not
> regardless.

- [ ] **Step 4: Make `SpApp` provide the WorkManager configuration and schedule the work**

```kotlin
package com.mediplus.spapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import com.mediplus.spapp.core.update.ForegroundTracker
import com.mediplus.spapp.core.update.UpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. Three process-lifecycle observers are bound here: the
 * [DiagnosticsPoller], the [SessionRevalidator], and the [ForegroundTracker].
 *
 * [Configuration.Provider] switches WorkManager to on-demand initialisation so workers are built by
 * Hilt's [HiltWorkerFactory]. That REQUIRES removing WorkManager's own `androidx.startup`
 * initializer from the merged manifest — see AndroidManifest.xml, and note the removal is targeted
 * at WorkManager's meta-data node only, because `lifecycle-process` registers
 * `ProcessLifecycleInitializer` through the same provider and the three observers above depend on it.
 */
@HiltAndroidApp
class SpApp : Application(), Configuration.Provider {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    @Inject
    lateinit var updateScheduler: UpdateScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
        foregroundTracker.bind()
        updateScheduler.schedule()
    }
}
```

- [ ] **Step 5: Remove WorkManager's auto-initializer — and only WorkManager's**

In `app/src/main/AndroidManifest.xml`, inside `<application>` before the `<activity>`:

```xml
        <!-- On-demand WorkManager init (SpApp is the Configuration.Provider), so workers are built
             by HiltWorkerFactory. tools:node="merge" on the provider with a targeted removal of the
             WorkManagerInitializer meta-data ONLY: removing the whole InitializationProvider would
             also take out androidx.lifecycle's ProcessLifecycleInitializer, and with it the
             DiagnosticsPoller, SessionRevalidator and ForegroundTracker. -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
```

- [ ] **Step 6: Verify the dependency resolves and the manifest merged correctly**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If Gradle reports `Could not find androidx.work:work-runtime-ktx:2.10.0`,
bump `work` in `libs.versions.toml` to the newest published `2.10.x` and re-run. Same for
`androidx.hilt` at `1.3.0` → fall back to `1.2.0` if unresolved.

Then confirm the surgical removal worked:

```bash
grep -n -A6 "InitializationProvider" \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: the provider is **present**, `ProcessLifecycleInitializer` meta-data is **present**,
`WorkManagerInitializer` meta-data is **absent**. If the provider is gone entirely, the removal was
too broad — fix it before continuing, or the three lifecycle observers stop running.

- [ ] **Step 7: Run the full suite, lint and detekt**

Run: `./gradlew testDebugUnitTest lintDebug`, then the detekt CLI.
Expected: PASS; detekt still **14**.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/mediplus/spapp/core/update/UpdateWorker.kt \
        app/src/main/java/com/mediplus/spapp/core/update/UpdateScheduler.kt \
        app/src/main/java/com/mediplus/spapp/SpApp.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: run the update journey from a periodic WorkManager job"
```

---

## Task 11: Re-enqueue the schedule after a reboot

Spec §2, Reboot. WorkManager persists its schedule through JobScheduler and reschedules on boot, so
this receiver is not strictly required. It is a few lines of insurance against a well-known
custom-OEM failure class that is invisible until the fleet has already gone stale.

This does **not** remove the first manual launch: a newly installed app is in the stopped state and
receives no broadcasts at all, `BOOT_COMPLETED` included, until a human taps the icon once.

> **Correction (2026-08-05, verified against work-runtime 2.10.0).** The paragraph above is wrong in
> its first clause and in its justification. WorkManager does *not* persist its schedule through
> JobScheduler: `SystemJobInfoConverter:128` sets `setPersisted(false)` precisely so it can rebuild
> the jobs on `BOOT_COMPLETED` itself. And the receiver is not insurance against an OEM task killer
> — its real justification is that work-runtime's own `RescheduleReceiver` is declared
> `enabled="false"` and only switched on by a runtime `setComponentEnabledSetting` write, whereas
> ours is statically enabled in the manifest and so does not depend on that write. The shipped
> receiver and design §2 carry the corrected reasoning; the stopped-state paragraph above is
> accurate and unchanged.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/update/BootCompletedReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UpdateScheduler.schedule()` (Task 10).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Create the receiver**

```kotlin
package com.mediplus.spapp.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms the update schedule after a reboot
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §2).
 *
 * Belt and braces: WorkManager already persists its schedule through JobScheduler and reschedules
 * itself on boot. These are custom-OEM Sunmi builds, though, and silently dropped background jobs
 * are a well-known failure class on such devices — one that is invisible until the whole fleet has
 * gone stale. [UpdateScheduler.schedule] is idempotent, so re-running it costs nothing.
 *
 * This does NOT reach a freshly installed app: Android holds it in the stopped state, where it
 * receives no broadcasts at all, until a human launches it once. That first launch is unavoidable
 * and is part of the office pass.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: UpdateScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        scheduler.schedule()
    }
}
```

> **Correction (2026-08-05).** The KDoc above was not shipped as written — see the class for the
> corrected text, and the note under the task heading for why. The body is correct as given: no
> `super.onReceive`, which the Hilt Gradle plugin already emits as instruction 0 of the rewritten
> `onReceive`. The `<receiver>` below shipped with `android:exported="false"`, not `"true"`: the
> system delivers a protected broadcast to a manifest receiver either way, and `RescheduleReceiver`
> takes this same action non-exported.

- [ ] **Step 2: Declare the permission and the receiver**

In `AndroidManifest.xml`, with the other permissions:

```xml
    <!-- Re-arm the periodic update check after a reboot; see BootCompletedReceiver. -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

and inside `<application>`, beside the other receivers:

```xml
        <!-- exported: BOOT_COMPLETED is a system broadcast, so the system must be able to reach it. -->
        <receiver
            android:name=".core.update.BootCompletedReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: Build, run the suite and lint**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug`
Expected: PASS. The receiver has no JVM test for the same reason as `UpdateStatusReceiver`; it is
verified on-device in Task 13.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/BootCompletedReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: re-arm the update schedule after a reboot"
```

---

## Task 12: Tell the operator when the install permission has gone

Spec §8. Android 11+ revokes permissions for unused apps, and **the whole fleet is API 30 or above**,
so this applies to every device. If `REQUEST_INSTALL_PACKAGES` is stripped from an idle device, the
headless flow reaches `PermissionNeeded` and stops there — silently, with nobody present, forever.

This is the design's §8 turned into something testable. Rather than guessing at the auto-revoke APIs
(`setAutoRevokeWhitelisted` is a system API, and `ACTION_AUTO_REVOKE_PERMISSIONS` was deprecated one
release after it appeared), the app reacts to the *consequence* it can actually observe: it cannot
request installs, and nobody is there to be told. The office-pass procedure in Task 13 covers the
prevention side.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/UpdateNotifications.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/core/update/UpdateCoordinator.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt`

**Interfaces:**
- Consumes: `UpdateNotifier` (Task 7), `UpdateCoordinator.advance` (Task 3).
- Produces: `UpdateNotifier` gains `fun installPermissionRequired()`. `UpdateCoordinator`'s
  constructor gains a trailing `notifier: UpdateNotifier` parameter.

- [ ] **Step 1: Write the failing test**

Add to `UpdateCoordinatorTest.kt`. Add the mock and pass it to the factory:

```kotlin
    private val notifier = mockk<UpdateNotifier>(relaxed = true)
```

```kotlin
    private fun coordinator() = UpdateCoordinator(
        checkForUpdate = CheckForUpdateUseCase(repository, currentVersion, BASE_URL),
        updateRepository = repository,
        installer = installer,
        backupStore = backupStore,
        errorMapper = errorMapper,
        currentVersion = currentVersion,
        pipeline = UpdatePipeline(repository, installer, backupStore, errorMapper, currentVersion),
        notifier = notifier,
    )
```

```kotlin
    @Test
    fun `a headless attempt that cannot request installs tells the operator`() = runTest {
        // Permission auto-reset applies to every device in this fleet (all API 30+). Stripped of
        // REQUEST_INSTALL_PACKAGES, an idle device would stop here silently and never update again.
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()

        coordinator.runUpdate(Presence.Headless)
        advanceUntilIdle()

        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)
        verify(exactly = 1) { notifier.installPermissionRequired() }
    }

    @Test
    fun `a foreground attempt that cannot request installs does not notify`() = runTest {
        // The operator is already looking at the PermissionNeeded surface; a notification on top
        // of it is noise.
        serverSays(AppResult.Success(info()))
        coEvery { installer.canRequestInstalls() } returns false
        val coordinator = coordinator()
        coordinator.runUpdate(Presence.Foreground)
        advanceUntilIdle()

        coordinator.accept()
        advanceUntilIdle()

        assertEquals(UpdatePhase.PermissionNeeded(info(), forced = false), coordinator.phase.value)
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }
```

Add `import io.mockk.verify` if it is not already present.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: FAIL — compilation error, `Too many arguments` / `Unresolved reference: installPermissionRequired`.

- [ ] **Step 3: Add the strings**

```xml
    <string name="update_permission_notification_title">Update needs permission</string>
    <string name="update_permission_notification_body">Open Service Provider App and allow it to install updates.</string>
```

- [ ] **Step 4: Extend the notifier**

In `UpdateNotifications.kt`, add to the interface:

```kotlin
interface UpdateNotifier {
    /**
     * The device cannot request installs — typically because Android's unused-app permission reset
     * stripped the grant from an idle device, which applies to every unit in this fleet (API 30+).
     * Nobody is present to see the in-app prompt, so this is the only signal that would otherwise
     * exist.
     */
    fun installPermissionRequired()

    /** Removes any outstanding update notification. */
    fun clear()
}
```

and to the implementation, plus a shared builder so the two notifications do not duplicate setup:

```kotlin
    fun confirmationRequired(sessionId: Int, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            sessionId,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(R.string.update_notification_title, R.string.update_notification_body, pending)
    }

    override fun installPermissionRequired() {
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            PERMISSION_REQUEST_CODE,
            settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            R.string.update_permission_notification_title,
            R.string.update_permission_notification_body,
            pending,
        )
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun post(titleRes: Int, bodyRes: Int, contentIntent: PendingIntent) {
        if (!canNotify()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
```

Add the imports `android.provider.Settings` and `androidx.core.net.toUri`, and to the companion:

```kotlin
        const val PERMISSION_REQUEST_CODE = 2001
```

Both notifications share `NOTIFICATION_ID`, so one replaces the other rather than stacking — the
device only ever has one outstanding update problem.

- [ ] **Step 5: Notify from the coordinator, headless only**

Add the constructor parameter:

```kotlin
    private val notifier: UpdateNotifier,
```

Thread presence into `advance` and act on it:

```kotlin
    private suspend fun advance(
        info: UpdateInfo,
        forced: Boolean,
        from: RetryTarget,
        presence: Presence = Presence.Foreground,
    ): UpdateAttempt {
        if (!installer.canRequestInstalls()) {
            _phase.value = UpdatePhase.PermissionNeeded(info, forced)
            // With the app open the operator is already looking at the PermissionNeeded surface.
            // Headless, this notification is the only signal that would ever exist.
            if (presence == Presence.Headless) notifier.installPermissionRequired()
            return UpdateAttempt.COMPLETED
        }
        val result = pipeline.run(info, forced, from, downloaded, sink)
        result.downloaded?.let { downloaded = it }
        return result.attempt
    }
```

and in `runUpdate`, pass it through:

```kotlin
                else -> advance(offer.info, offer.forced, RetryTarget.DOWNLOAD, presence)
```

The `accept()` / `retry()` / `returnedFromSettings()` call sites keep the default, which is correct:
all three exist only because an operator gestured.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.update.UpdateCoordinatorTest"`
Expected: PASS (26 tests).

- [ ] **Step 7: Run the full suite, lint and detekt**

Run: `./gradlew testDebugUnitTest lintDebug`, then the detekt CLI.
Expected: PASS; detekt still **14**. If `UpdateNotifications` trips `TooManyFunctions` (it has
`confirmationRequired`, `installPermissionRequired`, `clear`, `post`, `canNotify`, `ensureChannel` =
6), it is well under the threshold.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/update/UpdateNotifications.kt \
        app/src/main/java/com/mediplus/spapp/core/update/UpdateCoordinator.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/mediplus/spapp/core/update/UpdateCoordinatorTest.kt
git commit -m "feat: notify when an idle device has lost its install permission"
```

---

## Task 13: Bench verification and documentation

Spec, Testing and Rollout consequence. **This is the only evidence that counts.** Everything above is
JVM tests and reasoning; whether `USER_ACTION_NOT_REQUIRED` is honoured on Sunmi's modified Android
13 can only be settled on a real V3, and it decides whether half the fleet or none of it updates
unattended.

**Files:**
- Create: `docs/superpowers/plans/2026-08-03-unattended-self-update-bench-checklist.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Confirm the two rollout blockers are clear before benching**

Both are resolved as of 2026-08-03, but re-verify rather than assume:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs `
  app/build/outputs/apk/release/app-release.apk
```

Expected: SHA-256 `69:DA:BA:2F:40:6F:DD:0D:A0:97:65:6B:8E:26:C0:95:D7:FD:0D:B7:57:B1:D6:78:31:55:EC:1C:70:FC:ED:B4`.
A different fingerprint means `keystore.properties` is missing and the build is debug-signed — such
an APK must never leave the building.

Then confirm the release `BASE_URL`:

```bash
grep -n "BASE_URL" app/build/generated/source/buildConfig/release/com/mediplus/spapp/BuildConfig.java
```

Expected: `https://bio.infoeaze.com/api/v1/`.

Finally, confirm the back office serves `apkUrl` same-origin with that host. `CheckForUpdateUseCase`
refuses any other origin, and the refusal surfaces as an opaque `TransientKind.UNKNOWN` the operator
can never resolve.

- [ ] **Step 2: Write the bench checklist**

Create `docs/superpowers/plans/2026-08-03-unattended-self-update-bench-checklist.md`:

```markdown
# Unattended self-update — bench verification

Run this on **one real Sunmi V3 (API 33)** and **one real Sunmi V2s (API 30)** before any device
leaves the office. Nothing below can be substituted with an emulator: the whole question is what
Sunmi's modified Android actually does.

## Prerequisites

- Release APK signed with the permanent key (fingerprint `69:DA:…:ED:B4`), not debug-signed.
- `versionCode 4` installed; `versionCode 5` published to the back office at
  `https://bio.infoeaze.com/api/v1/`, with `apkUrl` on that same origin.
- The whole fake stack OFF in Dev Settings if a debug build is used for a dry run.

## Office-pass procedure (per device)

1. `adb install -r -t app-release.apk`
2. **Tap the launcher icon once.** Unavoidable: a newly installed app is in the stopped state and
   receives no broadcasts at all, `BOOT_COMPLETED` included, until a human launches it.
3. Grant "install unknown apps" when prompted.
4. Grant notifications (API 33+ only; the V2s does not ask).
5. Settings → Apps → SP App → **turn OFF "Remove permissions if app isn't used"** (the auto-reset
   that would otherwise strip the install permission from an idle device — the entire fleet is
   API 30+, so this applies to every unit).
6. **Perform one real self-update on the bench** (steps below). This both proves the chain and
   promotes the app to its own installer of record, which is what makes later updates silent.

## Checks

- [ ] **V3, foregrounded:** open the app with `5` published. Offer appears; accepting downloads,
      backs up, installs.
- [ ] **V3, headless silent install.** Publish `5`, lock the screen, leave the device untouched.
      Within 6 hours (or force it: `adb shell cmd jobscheduler run -f com.mediplus.spapp <id>`) the
      app is on `5` with nobody having touched it. **This is the single most valuable result here:**
      it decides whether the V3 half of the fleet is truly unattended.
- [ ] **V2s, headless notification.** Same setup. Expect `STATUS_PENDING_USER_ACTION` every time:
      a high-priority notification appears; tapping it lands directly on the system confirmation;
      confirming installs `5`. This is the V2s **primary** path, not a fallback.
- [ ] **Notification dismissed.** Swipe the notification away without acting. The next worker run
      re-posts it.
- [ ] **Notification survives a restart (V2s).** With a confirmation notification outstanding,
      force-stop and relaunch the app — or reboot — then tap the notification. It must still open
      the system confirmation and install. This is the check that replaces Task 8's unit test: the
      launch sweep must not have abandoned the committed session. Verify with
      `adb shell pm list staged-sessions` or `dumpsys package installer` before and after.
- [ ] **Reboot.** Reboot the device, do not open the app, publish `6`. It still updates.
- [ ] **Force-stop.** Force-stop from Settings, then reboot. The app does NOT update (stopped state,
      expected). Tapping the icon once restores it. Record this so nobody reports it as a bug.
- [ ] **Airplane mode.** Enable it, force a worker run. The attempt retries rather than failing
      permanently, and no notification appears.
- [ ] **Interrupted download.** Kill connectivity mid-transfer, restore it. The next attempt resumes
      (`Range: bytes=N-`) rather than restarting, and an already-complete verified file is not
      re-fetched at all.
- [ ] **Backup failure.** Fill the Downloads volume, then update. The install proceeds anyway.
- [ ] **`adb logcat`** during each run, filtered to `PackageInstaller|WorkManager|SpApp`, kept with
      the result.

## Recording

Write the outcome — especially whether the V3 installed silently — into
`docs/superpowers/specs/2026-08-03-unattended-self-update-design.md` under **Open items**, and update
the "Current state to be aware of" section in `CLAUDE.md`.
```

- [ ] **Step 3: Update `CLAUDE.md`**

In the "Current state to be aware of" section, after the existing self-update bullet, add:

```markdown
- **Self-update runs unattended** (design: `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md`;
  bench checklist: `docs/superpowers/plans/2026-08-03-unattended-self-update-bench-checklist.md`).
  Orchestration moved out of `UpdateViewModel` into a `@Singleton UpdateCoordinator` (+ `UpdatePipeline`)
  in `core/update`, so the UI and a periodic `UpdateWorker` drive the *same* code and publish to the
  same `StateFlow<UpdatePhase>`; the ViewModel is now a thin adapter. A `tryLock` mutex means an
  overlapping trigger is skipped, not queued. WorkManager runs every 6 h on `NetworkType.CONNECTED`,
  is re-enqueued with `KEEP` from `SpApp.onCreate()` and from `BootCompletedReceiver`, and needs
  WorkManager's `androidx.startup` initializer removed — **removed by a targeted `tools:node="remove"`
  on the `WorkManagerInitializer` meta-data only**, because dropping the whole `InitializationProvider`
  would also take out `ProcessLifecycleInitializer` and with it `DiagnosticsPoller`,
  `SessionRevalidator` and `ForegroundTracker`.
  The install branch is **capability-driven, never `SDK_INT`-driven**: every commit requests
  `USER_ACTION_NOT_REQUIRED` and reacts to the platform's answer, because Sunmi ships modified
  Android and a version check that assumed silence would wait forever for a status that never comes.
  When the platform demands confirmation and `ForegroundTracker` says nobody is there, the intent
  goes into a high-priority notification and `install()` returns `InstallOutcome.AwaitingConfirmation`
  (phase `ConfirmationPending`) instead of suspending — closing a hang that was unreachable while
  installs only happened with the app open. **On the V2s (API 30) that notification is the primary
  path, not a fallback**; the V3 (API 33) is the only half that can go silent, and only after the
  app has installed itself once and become its own installer of record.
  Two behaviour changes worth knowing: the rollback backup is **best effort and no longer gates an
  install** (a stranded field device is unrecoverable; a missing backup is an inconvenience), so
  `BusinessCode.UPDATE_BACKUP_FAILED` is now diagnostic-only and reaches no operator; and a
  completed-but-uninstalled APK is reused rather than re-downloaded, which is the normal state
  whenever a confirmation is outstanding.
  **The first manual launch cannot be removed** — Android's stopped state blocks every broadcast to
  a freshly installed app until a human taps the icon once. The office pass must include it, plus
  one real self-update on the bench.
```

Also update the detekt bullet: the baseline drops from **15** to **14** once Task 3 lands
(`TooManyFunctions` on `UpdateViewModel` is gone). Re-measure against a clean `HEAD` worktree and
correct the table rather than trusting this number — it has been wrong twice.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-08-03-unattended-self-update-bench-checklist.md CLAUDE.md
git commit -m "docs: bench checklist and CLAUDE.md notes for unattended self-update"
```

- [ ] **Step 5: Run the bench checklist on real hardware**

This is not a code step and cannot be completed by an agent. Until both devices pass, the feature is
**designed, implemented and unverified** — record it that way, and do not ship the fleet on it.

---

## Deferred, deliberately

- **Device-owner provisioning** (spec §9) would give silent installs on the V2s too, auto-grant the
  install and notification permissions, and make the app immune to permission auto-reset and to
  uninstalling. It is strictly stronger on the stated goal. It is not in this plan because device
  owner cannot be removed without a factory reset, it needs a `DeviceAdminReceiver` and a
  provisioning step, and the V2s failure mode without it is "an operator taps once" — a delay, not a
  stranded device. Revisit if the V2s tap proves unreliable in the field.
- **Raising `minSdk` from 24 to 30** (spec, Open items). It would drop core-library desugaring and
  delete the legacy storage path outright, but it is a build-wide change with its own regression
  surface. Worth doing immediately after this lands, not during it.
- **A proper `ic_stat_update` notification icon.** Task 7 uses a platform drawable; a flat silhouette
  vector would look better and changes nothing functionally.
