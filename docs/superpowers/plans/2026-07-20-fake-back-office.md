# Fake Back Office (dev tooling) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a debug-only in-app fake for the 8 back-office endpoints, driven by a dev screen, so the Compose UI can be exercised through every outcome with no backend.

**Architecture:** Fake at the four repository interfaces (the stable seam). Real `*Impl`s stay untouched; a source-set-specific `RepositoryModule` binds real impls in `release` and *switching* repos in `debug`. Each switching repo delegates to a `Fake*Repository` when a persisted master toggle is on, else to the real impl. A second launcher activity ("FaceVerify Dev") edits the persisted scenarios.

**Tech Stack:** Kotlin, Hilt, Coroutines, Compose, DataStore-Preferences, JUnit4 + MockK + coroutines-test.

## Global Constraints

- **AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.3.10.** Do NOT add the `org.jetbrains.kotlin.android` plugin. Hilt ≥ 2.60. compileSdk 37. (See `build-toolchain-constraints` memory.)
- **Android Lint is the in-build gate** (`abortOnError = true`). All new debug code must pass `:app:lintDebug`.
- **Package root:** `com.mediplus.faceverify`. Namespace/`applicationId` = `com.mediplus.faceverify`.
- **Privacy invariants (must hold even in fakes):** the face frame is transient — `FakeFaceRepository.verify` MUST `frame.clear()` in a `finally` block (FR-017). No token/documentNumber/biometric is persisted; DataStore holds dev prefs only.
- **All fake/dev code lives under `app/src/debug/.../dev/` (+ the two variant `RepositoryModule`s). Release builds must contain none of it.**
- **Gradle commands assume the Android Studio JBR.** Bash: prefix each command with `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. PowerShell: run `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"` once in the session first. All commands below are shown in Bash form.
- **Source sets:** production debug code → `app/src/debug/java/...`; debug-only unit tests → `app/src/testDebug/java/...` (NOT `app/src/test`, which also compiles for `testReleaseUnitTest` where debug classes are absent).

---

## File Structure

**Deleted**
- `app/src/main/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`

**Created — variant DI**
- `app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt` — binds real `*Impl`s (verbatim from the deleted file).
- `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt` — binds switching repos (starts as real-binding pass-through in Task 1, rebound in Task 5).

**Created — dev (all under `app/src/debug/java/com/mediplus/faceverify/dev/`)**
- `DevScenarios.kt` — scenario enums.
- `DevSettings.kt` — `DevSettings` snapshot + `Preferences.toDevSettings()` mapping + preference keys.
- `DevSettingsStore.kt` — interface + `DataStoreDevSettingsStore` impl.
- `FakeData.kt` — canned session / patient validation / services / face decision.
- `di/DevModule.kt` — binds `DevSettingsStore`.
- `repository/FakeAuthRepository.kt`, `FakeDocumentRepository.kt`, `FakeFaceRepository.kt`, `FakeEnrollmentRepository.kt`.
- `repository/SwitchingRepositories.kt` — the four switching classes.
- `ui/DevSettingsViewModel.kt`, `ui/DevSettingsScreen.kt`, `ui/DevSettingsActivity.kt`.
- `app/src/debug/AndroidManifest.xml` — declares `DevSettingsActivity` as a second launcher.

**Created — tests (`app/src/testDebug/java/com/mediplus/faceverify/dev/`)**
- `TestDevSettingsStore.kt` — in-memory test double.
- `DevSettingsMappingTest.kt`, `FakeAuthRepositoryTest.kt`, `FakeDocumentRepositoryTest.kt`, `FakeFaceRepositoryTest.kt`, `FakeEnrollmentRepositoryTest.kt`, `SwitchingRepositoryTest.kt`, `DevSettingsViewModelTest.kt`.

---

## Task 1: Move `RepositoryModule` into source sets (build gate)

De-risks the DI variant swap in isolation, before any fake logic. Debug still binds the real impls, so behavior is unchanged and the build stays green.

**Files:**
- Delete: `app/src/main/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Create: `app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`

**Interfaces:**
- Consumes: `AuthRepository(Impl)`, `DocumentRepository(Impl)`, `FaceRepository(Impl)`, `EnrollmentRepository(Impl)` (existing).
- Produces: a `RepositoryModule` class present in both the `debug` and `release` variants binding the four repository interfaces.

- [ ] **Step 1: Delete the main-source module**

```bash
git rm app/src/main/java/com/mediplus/faceverify/core/di/RepositoryModule.kt
```

- [ ] **Step 2: Create the release binding (verbatim real impls)**

Create `app/src/release/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: binds repository interfaces to their real implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: FaceRepositoryImpl): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: EnrollmentRepositoryImpl): EnrollmentRepository
}
```

- [ ] **Step 3: Create the debug binding (identical real impls for now)**

Create `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt` with the **same content as Step 2**, except change the KDoc first line to:

```kotlin
/** Debug: binds repository interfaces. Rebound to switching repos in the fake-backend task. */
```

(The class name, package, and all four `@Binds` methods are identical to Step 2. Repeating the full file:)

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: binds repository interfaces. Rebound to switching repos in the fake-backend task. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: FaceRepositoryImpl): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: EnrollmentRepositoryImpl): EnrollmentRepository
}
```

- [ ] **Step 4: Verify debug build + unit suite (the build gate)**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`. The DI move compiles and existing unit tests still pass.

- [ ] **Step 5: Verify release build (real bindings resolve)**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/release app/src/debug
git commit -m "refactor(di): move RepositoryModule into debug/release source sets"
```

---

## Task 2: Dev scenarios, settings snapshot, and store

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/di/DevModule.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsMappingTest.kt`

**Interfaces:**
- Consumes: `DataStore<Preferences>` (provided by main `StorageModule`).
- Produces:
  - enums `AuthScenario`, `DocumentScenario`, `FaceScenario`, `ServicesScenario`, `EnrollScenario`.
  - `data class DevSettings(fakeEnabled, auth, document, face, services, enroll, latencyMillis, verificationWindowSeconds)` with defaults.
  - `fun Preferences.toDevSettings(): DevSettings`.
  - `interface DevSettingsStore { val settings: Flow<DevSettings>; suspend fun current(): DevSettings; suspend fun setFakeEnabled(Boolean); suspend fun setAuth(AuthScenario); suspend fun setDocument(DocumentScenario); suspend fun setFace(FaceScenario); suspend fun setServices(ServicesScenario); suspend fun setEnroll(EnrollScenario); suspend fun setLatencyMillis(Long); suspend fun setVerificationWindowSeconds(Long) }`.

- [ ] **Step 1: Write the failing mapping test**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsMappingTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class DevSettingsMappingTest {

    @Test
    fun `empty preferences map to defaults`() {
        val settings = mutablePreferencesOf().toDevSettings()

        assertEquals(DevSettings(), settings)
        assertEquals(true, settings.fakeEnabled)
        assertEquals(AuthScenario.SUCCESS, settings.auth)
        assertEquals(500L, settings.latencyMillis)
        assertEquals(300L, settings.verificationWindowSeconds)
    }

    @Test
    fun `stored values map back onto the snapshot`() {
        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.FAKE_ENABLED, false)
            set(DevPrefKeys.AUTH, AuthScenario.ACCOUNT_LOCKED.name)
            set(DevPrefKeys.ENROLL, EnrollScenario.TIMEOUT.name)
            set(DevPrefKeys.LATENCY_MS, 0L)
        }

        val settings = prefs.toDevSettings()

        assertEquals(false, settings.fakeEnabled)
        assertEquals(AuthScenario.ACCOUNT_LOCKED, settings.auth)
        assertEquals(EnrollScenario.TIMEOUT, settings.enroll)
        assertEquals(0L, settings.latencyMillis)
    }

    @Test
    fun `an unknown enum name falls back to the default`() {
        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.FACE, "NOT_A_REAL_SCENARIO")
        }

        assertEquals(FaceScenario.PASS, prefs.toDevSettings().face)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsMappingTest"
```
Expected: FAIL — unresolved references (`toDevSettings`, `DevSettings`, `DevPrefKeys`, scenario enums).

- [ ] **Step 3: Create the scenario enums**

Create `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`:

```kotlin
package com.mediplus.faceverify.dev

/** Which canned outcome each faked endpoint group returns. Names are persisted verbatim. */
enum class AuthScenario { SUCCESS, INVALID_CREDENTIALS, ACCOUNT_LOCKED, THROTTLED, SERVER_ERROR }

enum class DocumentScenario { SUCCESS, INVALID, PATIENT_NOT_FOUND, SERVER_ERROR }

enum class FaceScenario { PASS, FAIL_NO_MATCH, FAIL_LIVENESS, SUBJECT_MISMATCH, LOCKED_OUT, SERVER_ERROR }

enum class ServicesScenario { SUCCESS, EMPTY, PATIENT_NOT_FOUND, SERVER_ERROR }

enum class EnrollScenario { CONFIRMED, DUPLICATE, INELIGIBLE, TIMEOUT, SERVER_ERROR }
```

- [ ] **Step 4: Create the settings snapshot, keys, and mapping**

Create `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`:

```kotlin
package com.mediplus.faceverify.dev

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Persisted dev configuration snapshot. Defaults = happy path, fake ON, 500ms latency. */
data class DevSettings(
    val fakeEnabled: Boolean = true,
    val auth: AuthScenario = AuthScenario.SUCCESS,
    val document: DocumentScenario = DocumentScenario.SUCCESS,
    val face: FaceScenario = FaceScenario.PASS,
    val services: ServicesScenario = ServicesScenario.SUCCESS,
    val enroll: EnrollScenario = EnrollScenario.CONFIRMED,
    val latencyMillis: Long = 500L,
    val verificationWindowSeconds: Long = 300L,
)

/** DataStore keys for dev settings. Namespaced to avoid clashing with app prefs. */
object DevPrefKeys {
    val FAKE_ENABLED = booleanPreferencesKey("dev_fake_enabled")
    val AUTH = stringPreferencesKey("dev_scenario_auth")
    val DOCUMENT = stringPreferencesKey("dev_scenario_document")
    val FACE = stringPreferencesKey("dev_scenario_face")
    val SERVICES = stringPreferencesKey("dev_scenario_services")
    val ENROLL = stringPreferencesKey("dev_scenario_enroll")
    val LATENCY_MS = longPreferencesKey("dev_latency_ms")
    val WINDOW_SECONDS = longPreferencesKey("dev_verification_window_seconds")
}

private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    this?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: default

/** Pure Preferences -> DevSettings mapping (defaults fill any absent/invalid key). */
fun Preferences.toDevSettings(): DevSettings {
    val defaults = DevSettings()
    return DevSettings(
        fakeEnabled = this[DevPrefKeys.FAKE_ENABLED] ?: defaults.fakeEnabled,
        auth = this[DevPrefKeys.AUTH].toEnumOr(defaults.auth),
        document = this[DevPrefKeys.DOCUMENT].toEnumOr(defaults.document),
        face = this[DevPrefKeys.FACE].toEnumOr(defaults.face),
        services = this[DevPrefKeys.SERVICES].toEnumOr(defaults.services),
        enroll = this[DevPrefKeys.ENROLL].toEnumOr(defaults.enroll),
        latencyMillis = this[DevPrefKeys.LATENCY_MS] ?: defaults.latencyMillis,
        verificationWindowSeconds = this[DevPrefKeys.WINDOW_SECONDS] ?: defaults.verificationWindowSeconds,
    )
}
```

- [ ] **Step 5: Create the store interface + DataStore impl**

Create `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`:

```kotlin
package com.mediplus.faceverify.dev

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for dev scenario selection; persisted, read by the fakes and the dev UI. */
interface DevSettingsStore {
    val settings: Flow<DevSettings>
    suspend fun current(): DevSettings
    suspend fun setFakeEnabled(enabled: Boolean)
    suspend fun setAuth(scenario: AuthScenario)
    suspend fun setDocument(scenario: DocumentScenario)
    suspend fun setFace(scenario: FaceScenario)
    suspend fun setServices(scenario: ServicesScenario)
    suspend fun setEnroll(scenario: EnrollScenario)
    suspend fun setLatencyMillis(millis: Long)
    suspend fun setVerificationWindowSeconds(seconds: Long)
}

@Singleton
class DataStoreDevSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DevSettingsStore {

    override val settings: Flow<DevSettings> = dataStore.data.map { it.toDevSettings() }

    override suspend fun current(): DevSettings = settings.first()

    override suspend fun setFakeEnabled(enabled: Boolean) =
        edit { it[DevPrefKeys.FAKE_ENABLED] = enabled }

    override suspend fun setAuth(scenario: AuthScenario) =
        edit { it[DevPrefKeys.AUTH] = scenario.name }

    override suspend fun setDocument(scenario: DocumentScenario) =
        edit { it[DevPrefKeys.DOCUMENT] = scenario.name }

    override suspend fun setFace(scenario: FaceScenario) =
        edit { it[DevPrefKeys.FACE] = scenario.name }

    override suspend fun setServices(scenario: ServicesScenario) =
        edit { it[DevPrefKeys.SERVICES] = scenario.name }

    override suspend fun setEnroll(scenario: EnrollScenario) =
        edit { it[DevPrefKeys.ENROLL] = scenario.name }

    override suspend fun setLatencyMillis(millis: Long) =
        edit { it[DevPrefKeys.LATENCY_MS] = millis }

    override suspend fun setVerificationWindowSeconds(seconds: Long) =
        edit { it[DevPrefKeys.WINDOW_SECONDS] = seconds }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }
}
```

- [ ] **Step 6: Bind the store**

Create `app/src/debug/java/com/mediplus/faceverify/dev/di/DevModule.kt`:

```kotlin
package com.mediplus.faceverify.dev.di

import com.mediplus.faceverify.dev.DataStoreDevSettingsStore
import com.mediplus.faceverify.dev.DevSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug-only bindings for the fake back office. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevModule {

    @Binds
    @Singleton
    abstract fun bindDevSettingsStore(impl: DataStoreDevSettingsStore): DevSettingsStore
}
```

- [ ] **Step 7: Run the mapping test to confirm it passes**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsMappingTest"
```
Expected: PASS (3 tests).

- [ ] **Step 8: Commit**

```bash
git add app/src/debug/java/com/mediplus/faceverify/dev app/src/testDebug
git commit -m "feat(dev): dev settings store + scenario model"
```

---

## Task 3: FakeData + FakeAuth + FakeDocument repositories

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeAuthRepository.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeDocumentRepository.kt`
- Create: `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeAuthRepositoryTest.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeDocumentRepositoryTest.kt`

**Interfaces:**
- Consumes: `DevSettingsStore`, `SessionManager`, `AuthRepository`, `DocumentRepository`, domain types (`Session`, `Operator`, `SessionState`, `DocumentValidation`), `AppResult`/`AppError`/`BusinessCode`/`TransientKind`.
- Produces:
  - `object FakeData { val session: Session; val validationValid: DocumentValidation; val validationInvalid: DocumentValidation; val services: List<Service>; val faceDecisionPass: FaceDecision }` (services/faceDecisionPass consumed in Task 4).
  - `class FakeAuthRepository @Inject constructor(store, sessionManager) : AuthRepository`.
  - `class FakeDocumentRepository @Inject constructor(store) : DocumentRepository`.
  - `class TestDevSettingsStore(var value: DevSettings) : DevSettingsStore` (test double, consumed by Tasks 3–7).

- [ ] **Step 1: Create the test double for the store**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`:

```kotlin
package com.mediplus.faceverify.dev

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory DevSettingsStore for unit tests. Latency defaults to 0 so runTest is instant. */
class TestDevSettingsStore(initial: DevSettings = DevSettings(latencyMillis = 0L)) : DevSettingsStore {
    private val state = MutableStateFlow(initial)
    var value: DevSettings
        get() = state.value
        set(v) { state.value = v }

    override val settings: Flow<DevSettings> = state
    override suspend fun current(): DevSettings = state.value
    override suspend fun setFakeEnabled(enabled: Boolean) { state.value = state.value.copy(fakeEnabled = enabled) }
    override suspend fun setAuth(scenario: AuthScenario) { state.value = state.value.copy(auth = scenario) }
    override suspend fun setDocument(scenario: DocumentScenario) { state.value = state.value.copy(document = scenario) }
    override suspend fun setFace(scenario: FaceScenario) { state.value = state.value.copy(face = scenario) }
    override suspend fun setServices(scenario: ServicesScenario) { state.value = state.value.copy(services = scenario) }
    override suspend fun setEnroll(scenario: EnrollScenario) { state.value = state.value.copy(enroll = scenario) }
    override suspend fun setLatencyMillis(millis: Long) { state.value = state.value.copy(latencyMillis = millis) }
    override suspend fun setVerificationWindowSeconds(seconds: Long) { state.value = state.value.copy(verificationWindowSeconds = seconds) }
}
```

- [ ] **Step 2: Write the failing auth + document tests**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeAuthRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.dev.repository.FakeAuthRepository
import com.mediplus.faceverify.domain.model.SessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class FakeAuthRepositoryTest {

    private fun repo(store: TestDevSettingsStore, session: InMemorySessionManager) =
        FakeAuthRepository(store, session)

    @Test
    fun `success sets the session and verification window`() = runTest {
        val session = InMemorySessionManager()
        val store = TestDevSettingsStore(DevSettings(latencyMillis = 0L, verificationWindowSeconds = 120L))

        val result = repo(store, session).signIn("demo", "demo")

        assertTrue(result is AppResult.Success)
        assertEquals(SessionState.Active, session.sessionState.value)
        assertEquals(120.seconds, session.verificationWindow.value)
    }

    @Test
    fun `invalid credentials scenario rejects without a session`() = runTest {
        val session = InMemorySessionManager()
        val store = TestDevSettingsStore(DevSettings(auth = AuthScenario.INVALID_CREDENTIALS, latencyMillis = 0L))

        val result = repo(store, session).signIn("demo", "demo")

        val error = (result as AppResult.BusinessRejection).error
        assertEquals(BusinessCode.INVALID_CREDENTIALS, error.code)
        assertEquals(SessionState.None, session.sessionState.value)
    }

    @Test
    fun `server error scenario is a transient failure`() = runTest {
        val store = TestDevSettingsStore(DevSettings(auth = AuthScenario.SERVER_ERROR, latencyMillis = 0L))

        val result = repo(store, InMemorySessionManager()).signIn("demo", "demo")

        assertTrue((result as AppResult.TransientFailure).error is AppError.Transient)
    }
}
```

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeDocumentRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.dev.repository.FakeDocumentRepository
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FakeDocumentRepositoryTest {

    private val read = ReadDocument(
        documentNumber = "X123",
        identity = DocumentIdentity("X123", "Doe", "Jane", "1990-01-01", "UTO", "F", LocalDate.of(2030, 1, 1), "GOV"),
        referencePhoto = null,
        securityObjectBase64 = null,
        dataGroupHashes = emptyMap(),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    @Test
    fun `success returns a VALID verified document`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.SUCCESS, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.VALID, validation.authenticity)
        assertEquals(true, validation.documentVerified)
    }

    @Test
    fun `invalid scenario is a 200 with INVALID authenticity, not a rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.INVALID, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.INVALID, validation.authenticity)
        assertEquals(false, validation.documentVerified)
    }

    @Test
    fun `patient not found is a business rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.PATIENT_NOT_FOUND, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        assertEquals(BusinessCode.PATIENT_NOT_FOUND, (result as AppResult.BusinessRejection).error.code)
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeAuthRepositoryTest" --tests "com.mediplus.faceverify.dev.FakeDocumentRepositoryTest"
```
Expected: FAIL — unresolved `FakeData`, `FakeAuthRepository`, `FakeDocumentRepository`.

- [ ] **Step 4: Create FakeData**

Create `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import com.mediplus.faceverify.domain.model.Operator
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState

/** Canned domain payloads for the happy path. Deterministic (no timestamps) for stable tests. */
object FakeData {

    val session: Session = Session(
        token = "fake-token-op-001",
        operator = Operator(operatorId = "op-001", displayName = "Demo Operator"),
        expiresAt = null,
        state = SessionState.Active,
    )

    val validationValid: DocumentValidation = DocumentValidation(
        authenticity = DocumentValidation.Authenticity.VALID,
        reason = null,
        documentVerified = true,
        referenceOnFile = true,
        patientResolved = true,
    )

    val validationInvalid: DocumentValidation = DocumentValidation(
        authenticity = DocumentValidation.Authenticity.INVALID,
        reason = "Document expired",
        documentVerified = false,
        referenceOnFile = true,
        patientResolved = true,
    )

    val services: List<Service> = listOf(
        Service("svc-blood", "Blood test", eligibleForPatient = true, alreadySelected = false),
        Service("svc-xray", "X-ray", eligibleForPatient = true, alreadySelected = false),
        Service("svc-vaccine", "Vaccination", eligibleForPatient = true, alreadySelected = false),
    )

    val faceDecisionPass: FaceDecision = FaceDecision(
        decisionPass = true,
        liveness = LivenessResult.PASSED,
        sameSubject = true,
        reason = null,
        lockout = FaceLockoutState(lockedOut = false, remainingAttempts = null, cooldownUntilMillis = null),
    )
}
```

- [ ] **Step 5: Create FakeAuthRepository**

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeAuthRepository.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.dev.AuthScenario
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Fake auth: returns the persisted [AuthScenario], driving the real [SessionManager] on success. */
class FakeAuthRepository @Inject constructor(
    private val store: DevSettingsStore,
    private val sessionManager: SessionManager,
) : AuthRepository {

    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.auth) {
            AuthScenario.SUCCESS -> {
                sessionManager.set(FakeData.session)
                sessionManager.setVerificationWindow(settings.verificationWindowSeconds.seconds)
                AppResult.Success(FakeData.session)
            }
            AuthScenario.INVALID_CREDENTIALS ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
            AuthScenario.ACCOUNT_LOCKED, AuthScenario.THROTTLED ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.ACCOUNT_LOCKED))
            AuthScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun signOut(): AppResult<Unit> {
        sessionManager.clearAll()
        return AppResult.Success(Unit)
    }

    override fun sessionState(): StateFlow<SessionState> = sessionManager.sessionState
}
```

- [ ] **Step 6: Create FakeDocumentRepository**

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeDocumentRepository.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DocumentScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake document validation: returns the persisted [DocumentScenario]. */
class FakeDocumentRepository @Inject constructor(
    private val store: DevSettingsStore,
) : DocumentRepository {

    override suspend fun validate(read: ReadDocument): AppResult<DocumentValidation> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.document) {
            DocumentScenario.SUCCESS -> AppResult.Success(FakeData.validationValid)
            DocumentScenario.INVALID -> AppResult.Success(FakeData.validationInvalid)
            DocumentScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            DocumentScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }
}
```

- [ ] **Step 7: Run tests to confirm they pass**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeAuthRepositoryTest" --tests "com.mediplus.faceverify.dev.FakeDocumentRepositoryTest"
```
Expected: PASS (6 tests).

- [ ] **Step 8: Commit**

```bash
git add app/src/debug/java/com/mediplus/faceverify/dev app/src/testDebug
git commit -m "feat(dev): fake auth + document repositories with canned data"
```

---

## Task 4: FakeFace + FakeEnrollment repositories

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeFaceRepository.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeFaceRepositoryTest.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`

**Interfaces:**
- Consumes: `DevSettingsStore`, `FakeData`, `FaceRepository`, `EnrollmentRepository`, `TransientFrame`, domain types (`FaceDecision`, `FaceLockoutState`, `LivenessResult`, `Service`, `Enrollment`, `EnrollmentStatus`).
- Produces: `class FakeFaceRepository @Inject constructor(store) : FaceRepository`; `class FakeEnrollmentRepository @Inject constructor(store) : EnrollmentRepository` (holds an in-memory `idempotencyKey -> Enrollment` map so `recheck` resolves a prior `TIMEOUT`).

- [ ] **Step 1: Write the failing face + enrollment tests**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeFaceRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.repository.FakeFaceRepository
import com.mediplus.faceverify.domain.model.LivenessResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFaceRepositoryTest {

    private fun frame() = TransientFrame(byteArrayOf(1, 2, 3))

    @Test
    fun `pass returns a passing decision and clears the frame`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.PASS, latencyMillis = 0L))
        val f = frame()

        val result = FakeFaceRepository(store).verify("X123", f)

        assertTrue((result as AppResult.Success).data.decisionPass)
        assertTrue("frame must be cleared after verify (FR-017)", f.isCleared)
    }

    @Test
    fun `liveness failure returns a failed decision`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.FAIL_LIVENESS, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertFalse(decision.decisionPass)
        assertEquals(LivenessResult.FAILED, decision.liveness)
    }

    @Test
    fun `locked out populates the lockout state`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.LOCKED_OUT, latencyMillis = 0L))

        val decision = (FakeFaceRepository(store).verify("X123", frame()) as AppResult.Success).data

        assertTrue(decision.lockout.lockedOut)
    }

    @Test
    fun `server error clears the frame too`() = runTest {
        val store = TestDevSettingsStore(DevSettings(face = FaceScenario.SERVER_ERROR, latencyMillis = 0L))
        val f = frame()

        val result = FakeFaceRepository(store).verify("X123", f)

        assertTrue(result is AppResult.TransientFailure)
        assertTrue(f.isCleared)
    }
}
```

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.dev.repository.FakeEnrollmentRepository
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEnrollmentRepositoryTest {

    @Test
    fun `listServices success returns the canned list`() = runTest {
        val store = TestDevSettingsStore(DevSettings(services = ServicesScenario.SUCCESS, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).listServices("X123")

        assertEquals(FakeData.services, (result as AppResult.Success).data)
    }

    @Test
    fun `enroll confirmed yields a Confirmed status`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.CONFIRMED, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).enroll("X123", "svc-blood", "key-1")

        val enrollment = (result as AppResult.Success).data
        assertTrue(enrollment.status is EnrollmentStatus.Confirmed)
    }

    @Test
    fun `enroll duplicate is a business rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.DUPLICATE, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).enroll("X123", "svc-blood", "key-1")

        assertEquals(BusinessCode.DUPLICATE_SERVICE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `timeout on enroll is resolvable by recheck with the same key`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.TIMEOUT, latencyMillis = 0L))
        val repo = FakeEnrollmentRepository(store)

        val enrollResult = repo.enroll("X123", "svc-blood", "key-42")
        assertTrue(enrollResult is AppResult.Timeout)

        val recheck = repo.recheck("X123", "key-42")
        assertTrue((recheck as AppResult.Success).data?.status is EnrollmentStatus.Confirmed)
    }

    @Test
    fun `recheck with an unknown key returns success-null`() = runTest {
        val store = TestDevSettingsStore(DevSettings(latencyMillis = 0L))

        val recheck = FakeEnrollmentRepository(store).recheck("X123", "never-seen")

        assertNull((recheck as AppResult.Success).data)
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeFaceRepositoryTest" --tests "com.mediplus.faceverify.dev.FakeEnrollmentRepositoryTest"
```
Expected: FAIL — unresolved `FakeFaceRepository`, `FakeEnrollmentRepository`.

- [ ] **Step 3: Create FakeFaceRepository**

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeFaceRepository.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FaceScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake face verify: returns the persisted [FaceScenario]. Always clears the frame (FR-017). */
class FakeFaceRepository @Inject constructor(
    private val store: DevSettingsStore,
) : FaceRepository {

    override suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision> {
        try {
            val settings = store.current()
            delay(settings.latencyMillis)
            return when (settings.face) {
                FaceScenario.PASS -> AppResult.Success(FakeData.faceDecisionPass)
                FaceScenario.FAIL_NO_MATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false, reason = "No match"),
                )
                FaceScenario.FAIL_LIVENESS -> AppResult.Success(
                    fail(liveness = LivenessResult.FAILED, sameSubject = true, reason = "Liveness failed"),
                )
                FaceScenario.SUBJECT_MISMATCH -> AppResult.Success(
                    fail(liveness = LivenessResult.PASSED, sameSubject = false, reason = "Different subject"),
                )
                FaceScenario.LOCKED_OUT -> AppResult.Success(
                    fail(
                        liveness = LivenessResult.PASSED,
                        sameSubject = false,
                        reason = "Locked out",
                        lockout = FaceLockoutState(lockedOut = true, remainingAttempts = 0, cooldownUntilMillis = null),
                    ),
                )
                FaceScenario.SERVER_ERROR ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            }
        } finally {
            frame.clear()
        }
    }

    private fun fail(
        liveness: LivenessResult,
        sameSubject: Boolean,
        reason: String,
        lockout: FaceLockoutState = FaceLockoutState(lockedOut = false, remainingAttempts = 2, cooldownUntilMillis = null),
    ) = FaceDecision(
        decisionPass = false,
        liveness = liveness,
        sameSubject = sameSubject,
        reason = reason,
        lockout = lockout,
    )
}
```

- [ ] **Step 4: Create FakeEnrollmentRepository**

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.EnrollScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.dev.ServicesScenario
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Service
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fake enrollment: returns the persisted scenarios. A [TIMEOUT][EnrollScenario.TIMEOUT] models a POST
 * that landed but whose ack was lost — the enrollment is recorded, so [recheck] with the same key
 * resolves it (mirrors FR-022). Singleton so the idempotency map survives across calls.
 */
@Singleton
class FakeEnrollmentRepository @Inject constructor(
    private val store: DevSettingsStore,
) : EnrollmentRepository {

    private val landed = ConcurrentHashMap<String, Enrollment>()

    override suspend fun listServices(documentNumber: String): AppResult<List<Service>> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.services) {
            ServicesScenario.SUCCESS -> AppResult.Success(FakeData.services)
            ServicesScenario.EMPTY -> AppResult.Success(emptyList())
            ServicesScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            ServicesScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun enroll(
        documentNumber: String,
        serviceId: String,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val settings = store.current()
        delay(settings.latencyMillis)
        val confirmed = confirmedEnrollment(documentNumber, serviceId, idempotencyKey)
        return when (settings.enroll) {
            EnrollScenario.CONFIRMED -> {
                landed[idempotencyKey] = confirmed
                AppResult.Success(confirmed)
            }
            EnrollScenario.DUPLICATE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, "Already added"))
            EnrollScenario.INELIGIBLE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, "Not eligible"))
            EnrollScenario.TIMEOUT -> {
                landed[idempotencyKey] = confirmed // POST landed; ack lost.
                AppResult.Timeout
            }
            EnrollScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun recheck(documentNumber: String, idempotencyKey: String): AppResult<Enrollment?> {
        delay(store.current().latencyMillis)
        return AppResult.Success(landed[idempotencyKey])
    }

    private fun confirmedEnrollment(documentNumber: String, serviceId: String, idempotencyKey: String): Enrollment {
        val id = "enr-$idempotencyKey"
        val service = FakeData.services.firstOrNull { it.serviceId == serviceId }
            ?: Service(serviceId, "", eligibleForPatient = true, alreadySelected = false)
        return Enrollment(
            enrollmentId = id,
            documentNumber = documentNumber,
            service = service,
            idempotencyKey = idempotencyKey,
            status = EnrollmentStatus.Confirmed(id),
            timestampMillis = null,
        )
    }
}
```

- [ ] **Step 5: Run tests to confirm they pass**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeFaceRepositoryTest" --tests "com.mediplus.faceverify.dev.FakeEnrollmentRepositoryTest"
```
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/debug/java/com/mediplus/faceverify/dev app/src/testDebug
git commit -m "feat(dev): fake face + enrollment repositories with idempotent recheck"
```

---

## Task 5: Switching repositories + rebind debug module (build gate)

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/SwitchingRepositoryTest.kt`

**Interfaces:**
- Consumes: real impls (`AuthRepositoryImpl` etc.), fakes (`FakeAuthRepository` etc.), `DevSettingsStore`, `TransientFrame`.
- Produces: `SwitchingAuthRepository`, `SwitchingDocumentRepository`, `SwitchingFaceRepository`, `SwitchingEnrollmentRepository`, each `@Inject`-constructed with `(real, fake, store)` and delegating on `store.current().fakeEnabled`.

- [ ] **Step 1: Write the failing switching test**

Uses MockK (already a `testImplementation` dependency) to stand in for the real
`DocumentRepositoryImpl`, so the test isolates the fake-vs-real branch without
constructing the real Retrofit stack.

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/SwitchingRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.dev.repository.FakeDocumentRepository
import com.mediplus.faceverify.dev.repository.SwitchingDocumentRepository
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SwitchingRepositoryTest {

    private val read = ReadDocument(
        documentNumber = "X123",
        identity = DocumentIdentity("X123", "Doe", "Jane", "1990-01-01", "UTO", "F", LocalDate.of(2030, 1, 1), "GOV"),
        referencePhoto = null,
        securityObjectBase64 = null,
        dataGroupHashes = emptyMap(),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    private val realImpl = mockk<DocumentRepositoryImpl>().also {
        coEvery { it.validate(any()) } returns AppResult.Success(
            DocumentValidation(DocumentValidation.Authenticity.VALID, "REAL", true, true, true),
        )
    }

    @Test
    fun `delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val switching = SwitchingDocumentRepository(realImpl, FakeDocumentRepository(store), store)

        val result = switching.validate(read) as AppResult.Success
        assertEquals(null, result.data.reason) // fake VALID has null reason
    }

    @Test
    fun `delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val switching = SwitchingDocumentRepository(realImpl, FakeDocumentRepository(store), store)

        val result = switching.validate(read) as AppResult.Success
        assertEquals("REAL", result.data.reason)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.SwitchingRepositoryTest"
```
Expected: FAIL — unresolved `SwitchingDocumentRepository`.

- [ ] **Step 3: Create the switching repositories**

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.ReadDocument
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Debug-only routers: use the fake when the master toggle is on, else the real impl. */

class SwitchingAuthRepository @Inject constructor(
    private val real: AuthRepositoryImpl,
    private val fake: FakeAuthRepository,
    private val store: DevSettingsStore,
) : AuthRepository {
    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> =
        pick().signIn(identifier, secret)

    override suspend fun signOut(): AppResult<Unit> = pick().signOut()

    // Both delegate to the same singleton SessionManager, so either is fine; use the real one.
    override fun sessionState(): StateFlow<SessionState> = real.sessionState()

    private suspend fun pick(): AuthRepository = if (store.current().fakeEnabled) fake else real
}

class SwitchingDocumentRepository @Inject constructor(
    private val real: DocumentRepositoryImpl,
    private val fake: FakeDocumentRepository,
    private val store: DevSettingsStore,
) : DocumentRepository {
    override suspend fun validate(read: ReadDocument): AppResult<DocumentValidation> =
        (if (store.current().fakeEnabled) fake else real).validate(read)
}

class SwitchingFaceRepository @Inject constructor(
    private val real: FaceRepositoryImpl,
    private val fake: FakeFaceRepository,
    private val store: DevSettingsStore,
) : FaceRepository {
    override suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision> =
        (if (store.current().fakeEnabled) fake else real).verify(documentNumber, frame)
}

class SwitchingEnrollmentRepository @Inject constructor(
    private val real: EnrollmentRepositoryImpl,
    private val fake: FakeEnrollmentRepository,
    private val store: DevSettingsStore,
) : EnrollmentRepository {
    override suspend fun listServices(documentNumber: String): AppResult<List<Service>> =
        (if (store.current().fakeEnabled) fake else real).listServices(documentNumber)

    override suspend fun enroll(documentNumber: String, serviceId: String, idempotencyKey: String): AppResult<Enrollment> =
        (if (store.current().fakeEnabled) fake else real).enroll(documentNumber, serviceId, idempotencyKey)

    override suspend fun recheck(documentNumber: String, idempotencyKey: String): AppResult<Enrollment?> =
        (if (store.current().fakeEnabled) fake else real).recheck(documentNumber, idempotencyKey)
}
```

- [ ] **Step 4: Rebind the debug RepositoryModule to the switching repos**

Replace the contents of `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt` with:

```kotlin
package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.dev.repository.SwitchingAuthRepository
import com.mediplus.faceverify.dev.repository.SwitchingDocumentRepository
import com.mediplus.faceverify.dev.repository.SwitchingEnrollmentRepository
import com.mediplus.faceverify.dev.repository.SwitchingFaceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes each repository through a switching repo (fake vs real, per the dev toggle). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SwitchingAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: SwitchingDocumentRepository): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: SwitchingFaceRepository): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: SwitchingEnrollmentRepository): EnrollmentRepository
}
```

- [ ] **Step 5: Run the switching test + full debug unit suite + debug build (build gate)**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`; `SwitchingRepositoryTest` (2) and all prior dev tests pass. This proves Hilt resolves the switching graph (real impls + fakes + store) in the debug variant.

- [ ] **Step 6: Commit**

```bash
git add app/src/debug app/src/testDebug
git commit -m "feat(dev): switching repositories route fake vs real via master toggle"
```

---

## Task 6: Dev screen (ViewModel + Compose UI + second launcher)

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `DevSettingsStore`, `SessionManager`, `FaceVerifyTheme`, the scenario enums.
- Produces: `DevSettingsViewModel(store, sessionManager)` with `val settings: StateFlow<DevSettings>`, setters that delegate to the store, and `fun forceSessionExpired()`; `DevSettingsScreen(...)`; `DevSettingsActivity`.

- [ ] **Step 1: Write the failing ViewModel test**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/DevSettingsViewModelTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.dev.ui.DevSettingsViewModel
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.model.Operator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DevSettingsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `setAuth persists to the store`() = runTest {
        val store = TestDevSettingsStore()
        val vm = DevSettingsViewModel(store, InMemorySessionManager())

        vm.setAuth(AuthScenario.ACCOUNT_LOCKED)

        assertEquals(AuthScenario.ACCOUNT_LOCKED, store.current().auth)
    }

    @Test
    fun `forceSessionExpired marks the session expired`() = runTest {
        val session = InMemorySessionManager().apply {
            set(Session("t", Operator("op-001", "Demo"), expiresAt = null, state = SessionState.Active))
        }
        val vm = DevSettingsViewModel(TestDevSettingsStore(), session)

        vm.forceSessionExpired()

        assertEquals(SessionState.Expired, session.sessionState.value)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsViewModelTest"
```
Expected: FAIL — unresolved `DevSettingsViewModel`.

- [ ] **Step 3: Create the ViewModel**

Create `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`:

```kotlin
package com.mediplus.faceverify.dev.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.dev.AuthScenario
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DocumentScenario
import com.mediplus.faceverify.dev.EnrollScenario
import com.mediplus.faceverify.dev.FaceScenario
import com.mediplus.faceverify.dev.ServicesScenario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevSettingsViewModel @Inject constructor(
    private val store: DevSettingsStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val settings: StateFlow<DevSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevSettings())

    fun setFakeEnabled(enabled: Boolean) = launchEdit { store.setFakeEnabled(enabled) }
    fun setAuth(scenario: AuthScenario) = launchEdit { store.setAuth(scenario) }
    fun setDocument(scenario: DocumentScenario) = launchEdit { store.setDocument(scenario) }
    fun setFace(scenario: FaceScenario) = launchEdit { store.setFace(scenario) }
    fun setServices(scenario: ServicesScenario) = launchEdit { store.setServices(scenario) }
    fun setEnroll(scenario: EnrollScenario) = launchEdit { store.setEnroll(scenario) }
    fun setLatencyMillis(millis: Long) = launchEdit { store.setLatencyMillis(millis) }

    /** Immediately drop the session so the NavGraph guard routes back to sign-in (FR-004/FR-004a). */
    fun forceSessionExpired() {
        sessionManager.markSessionExpired()
    }

    private inline fun launchEdit(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
```

- [ ] **Step 4: Run the ViewModel test to confirm it passes**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests "com.mediplus.faceverify.dev.DevSettingsViewModelTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Create the Compose screen**

Create `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`:

```kotlin
package com.mediplus.faceverify.dev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediplus.faceverify.dev.AuthScenario
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.DocumentScenario
import com.mediplus.faceverify.dev.EnrollScenario
import com.mediplus.faceverify.dev.FaceScenario
import com.mediplus.faceverify.dev.ServicesScenario

/** Debug scenario picker. Stateless: hoists all state from [settings] and reports edits via callbacks. */
@Composable
fun DevSettingsScreen(
    settings: DevSettings,
    onFakeEnabled: (Boolean) -> Unit,
    onAuth: (AuthScenario) -> Unit,
    onDocument: (DocumentScenario) -> Unit,
    onFace: (FaceScenario) -> Unit,
    onServices: (ServicesScenario) -> Unit,
    onEnroll: (EnrollScenario) -> Unit,
    onLatency: (Long) -> Unit,
    onForceExpire: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fake Back Office", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Fake backend enabled", modifier = Modifier.weight(1f))
            Switch(checked = settings.fakeEnabled, onCheckedChange = onFakeEnabled)
        }

        HorizontalDivider()

        ScenarioPicker("Auth (login)", AuthScenario.entries, settings.auth, onAuth)
        ScenarioPicker("Document validate", DocumentScenario.entries, settings.document, onDocument)
        ScenarioPicker("Face verify", FaceScenario.entries, settings.face, onFace)
        ScenarioPicker("Services list", ServicesScenario.entries, settings.services, onServices)
        ScenarioPicker("Enrollment", EnrollScenario.entries, settings.enroll, onEnroll)

        HorizontalDivider()

        Text("Latency: ${settings.latencyMillis} ms")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L, 500L, 1500L).forEach { preset ->
                OutlinedButton(onClick = { onLatency(preset) }) { Text("$preset") }
            }
        }

        HorizontalDivider()

        Button(onClick = onForceExpire, modifier = Modifier.fillMaxWidth()) {
            Text("Force session expired")
        }
    }
}

@Composable
private fun <T : Enum<T>> ScenarioPicker(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }) { Text(selected.name) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 6: Create the Activity**

Create `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`:

```kotlin
package com.mediplus.faceverify.dev.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediplus.faceverify.core.ui.theme.FaceVerifyTheme
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only second launcher: edit the fake back-office scenarios, then return to the app. */
@AndroidEntryPoint
class DevSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FaceVerifyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: DevSettingsViewModel = hiltViewModel()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    DevSettingsScreen(
                        settings = settings,
                        onFakeEnabled = vm::setFakeEnabled,
                        onAuth = vm::setAuth,
                        onDocument = vm::setDocument,
                        onFace = vm::setFace,
                        onServices = vm::setServices,
                        onEnroll = vm::setEnroll,
                        onLatency = vm::setLatencyMillis,
                        onForceExpire = vm::forceSessionExpired,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 7: Declare the second launcher in the debug manifest**

Create `app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <activity
            android:name="com.mediplus.faceverify.dev.ui.DevSettingsActivity"
            android:exported="true"
            android:label="FaceVerify Dev">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8: Build debug + run Lint (UI build gate)**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleDebug :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. (If Lint flags the reused launcher icon or label as hardcoded, those are warnings, not errors; `abortOnError` fails only on errors.)

- [ ] **Step 9: Commit**

```bash
git add app/src/debug app/src/testDebug
git commit -m "feat(dev): dev settings screen + second launcher activity"
```

---

## Task 7: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full debug suite + build**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`; all dev tests pass.

- [ ] **Step 2: Release build excludes all dev code**

Run:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease
```
Expected: `BUILD SUCCESSFUL`.

Then confirm no `dev/` classes leaked into the release APK:
```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease && \
  unzip -l app/build/outputs/apk/release/app-release-unsigned.apk | grep -i "dev/ui/DevSettings" || echo "OK: no dev classes in release APK"
```
Expected: prints `OK: no dev classes in release APK`.

- [ ] **Step 3: Manual smoke checklist (physical device, debug build)**

Document the outcome in the commit message. On a device:
1. Install debug build → **two launcher icons** appear ("faceverify" and "FaceVerify Dev").
2. Open **FaceVerify Dev**, leave defaults (fake ON, all Success) → open the app → sign in with any credentials → reaches NFC step. Complete NFC + face capture → each step succeeds → enrollment confirms.
3. In **FaceVerify Dev**, set Face = `FAIL_LIVENESS` → re-run face step → UI shows the failure state.
4. Tap **Force session expired** while signed in → app returns to sign-in.

- [ ] **Step 4: Commit the verification note (if any docs/notes changed)**

```bash
git commit --allow-empty -m "chore(dev): verify fake back office builds, tests pass, release excludes dev code"
```
