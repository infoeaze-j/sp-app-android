# Session Revalidation on Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the app returns to the foreground believing it has an active session, confirm that belief with the back office before the operator involves a patient — and never end a session on anything less than the server saying so.

**Architecture:** Three pieces, no new user-visible surface. (1) `AuthRepository` gains `revalidateSession(): SessionCheck`, where `SessionCheck` is a three-valued enum (`Valid`/`Ended`/`Unknown`) that makes fail-open a case the compiler forces you to name. (2) A new `core/session/SessionRevalidator` — a second `ProcessLifecycleOwner` observer mirroring `DiagnosticsPoller`'s shape — calls it on `onStart` when and only when `sessionState == Active`. (3) `SpApp.onCreate()` binds it beside the poller. The repository never calls a `SessionManager` mutator: the existing `AuthInterceptor` already owns "a 401 on a tokened request ends the session", and it stays the only place that rule lives.

**Tech Stack:** Kotlin 2.3.10, Hilt ≥ 2.60, Retrofit + kotlinx.serialization, Coroutines/StateFlow, AndroidX `lifecycle-process` (already a dependency — added for `DiagnosticsPoller`, no new libraries needed). Tests: JUnit4 + MockK + MockWebServer + `kotlinx-coroutines-test`.

**Design doc:** `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md`

## Global Constraints

Every task's requirements implicitly include these (verbatim from the design, CLAUDE.md and the constitution):

- **`JAVA_HOME` for Gradle:** `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` before any `./gradlew` call (PowerShell). Git Bash mangles this project's adb paths; run Gradle from PowerShell.
- **This is NOT session persistence.** The bearer token lives only in `InMemorySessionManager`'s `StateFlow`; `PrefsDataStore` deliberately persists nothing. Do not add token persistence, do not touch `PrefsDataStore`, do not attempt to resume across process death.
- **The repository never mutates session state.** `revalidateSession()` classifies and returns. `AuthInterceptor` is the single place a 401 ends a session; adding a second `markSessionInvalidated()`/`markSessionExpired()` call anywhere is the failure mode this design exists to avoid.
- **Fail open.** Only an explicit 401 counts as expiry. 5xx, an unexpected status, `IOException` and `SocketTimeoutException` all leave the session **completely alone**. A flaky clinic connection must never force a re-login.
- **No new user-facing anything.** No new screen, no new string in `res/values/strings.xml`, no new `UiMessage`, no new `AppError`/`BusinessCode`/`TransientKind` variant, no new `…Phase` variant. The whole visible change is that the existing `Invalidated` → `NavGraph` pops to sign-in → `SignInViewModel` "session ended" notice chain fires on resume instead of three patient-facing steps later.
- **Injected dispatchers only** — `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher` from `DispatchersModule`. Never reference `Dispatchers.*` directly.
- **DTOs never leave `data/remote`.** `SessionResource` is read inside `AuthRepositoryImpl` and nothing about its contents (`policy`, `provider`, `serverTime`) is acted on — see "Out of scope" in the design.
- **Never log or persist identity data.** No logging of tokens, operator identifiers, or session contents anywhere in this change.
- **detekt (CLI 1.23.7, `config/detekt/detekt.yml`, `maxIssues: 0`, `warningsAsErrors`):** functions ≤ 50 lines, line length ≤ 120, `ReturnCount` ≤ 4, no bare `TODO`/`FIXME`. detekt is **not** a Gradle task here — `./gradlew detekt` fails with "task not found". `main` is already red with ~48 pre-existing issues; compare against that baseline, do not add new ones. detekt runs over `app/src/main/java` only, so test sources are not gated by it.
- **Test-first, ≥ 80% coverage on changed code, success *and* denial paths.** `bind()` (the `ProcessLifecycleOwner` registration) is the one untested shim, following the `DiagnosticsPoller.bind()` precedent.

---

## File Structure

**New (main):**
- `app/src/main/java/com/mediplus/spapp/core/session/SessionRevalidator.kt` — the second `ProcessLifecycleOwner` observer. Sole responsibility: on foreground, if the session is `Active`, fire one revalidation and discard the result.

**New (test):**
- `app/src/test/java/com/mediplus/spapp/core/session/SessionRevalidatorTest.kt` — the trigger rules. (The `core/session/` test directory does not exist yet; create it.)

**Modified (main):**
- `app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt` — add the `SessionCheck` enum, the interface method, and the impl.
- `app/src/main/java/com/mediplus/spapp/SpApp.kt` — inject and `bind()` the revalidator.

**Modified (debug — required, or the debug build stops compiling):**
- `app/src/debug/java/com/mediplus/spapp/dev/repository/FakeAuthRepository.kt`
- `app/src/debug/java/com/mediplus/spapp/dev/repository/SwitchingRepositories.kt` (`SwitchingAuthRepository` only)

**Modified (test):**
- `app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt` — the fail-open matrix.
- `app/src/test/java/com/mediplus/spapp/data/remote/AuthApiContractTest.kt` — the 200-leaves-`Active` half of the interceptor proof (the 401 half already exists).
- `app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt` — its file-private `FakeAuthRepository` hand-implements the interface and will not compile without the new method.

**Modified (docs):**
- `CLAUDE.md` — close open decision #2, record the shipped behaviour.
- `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md` — flip the status header.

**Deliberately NOT modified:** `core/network/AuthInterceptor.kt` (already does exactly the right thing), `core/session/SessionManager.kt` (no new state, no new mutator), `app/src/{debug,release}/.../core/di/RepositoryModule.kt` (bindings are by interface and unchanged), `dev/FakeSeam.kt` / `dev/DevSettings.kt` / the Dev Settings UI (the `AUTH` seam already routes this; no new scenario).

---

## Task 1: `SessionCheck` and `AuthRepository.revalidateSession()` — the fail-open seam

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt`
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/repository/FakeAuthRepository.kt`
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/repository/SwitchingRepositories.kt:35-50`
- Modify: `app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt:90-110`
- Test: `app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt`
- Test: `app/src/test/java/com/mediplus/spapp/data/remote/AuthApiContractTest.kt`

**Interfaces:**
- Consumes: `AuthApi.session(): Response<SessionResource>` (already exists, currently uncalled); `apiCall(dispatcher, call, map)` from `core/network/ApiCall.kt`; `AppResult.Success(data)`.
- Produces: `enum class SessionCheck { Valid, Ended, Unknown }` in package `com.mediplus.spapp.data.repository`; `suspend fun AuthRepository.revalidateSession(): SessionCheck`.

- [ ] **Step 1: Write the failing repository tests**

In `app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt`, add these two imports to the existing import block (keep them alphabetical among the `com.mediplus.spapp.domain.model.*` imports, which today only has `SessionState`):

```kotlin
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Session
```

`SessionCheck` needs no import — it lives in the same package as the test.

Add this private helper next to the existing `loginResponse()` helper (after line 52):

```kotlin
    /**
     * A live session, set directly rather than via `signIn`, so the revalidation tests do not depend
     * on the login path.
     */
    private fun activeSession() = Session(
        token = "tok",
        operator = Operator("op-1", "Sam"),
        expiresAt = null,
        state = SessionState.Active,
    )
```

Add these six tests at the end of the class, before the closing brace:

```kotlin
    @Test
    fun `revalidate - a 200 says the session is valid`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.success(loginResponse())

        assertEquals(SessionCheck.Valid, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - a 401 says the session has ended`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(401, "".toResponseBody(null))

        // The actual invalidation is AuthInterceptor's job, proven end to end in AuthApiContractTest.
        // AuthApi is mocked here, so there is no interceptor in the chain and only the classification
        // is asserted — the repository must not invalidate anything itself.
        assertEquals(SessionCheck.Ended, repo.revalidateSession())
    }

    @Test
    fun `revalidate - a 500 is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(500, "".toResponseBody(null))

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - an unexpected status is unknown, not an ending`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } returns Response.error(418, "".toResponseBody(null))

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - an IO failure is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } throws IOException("offline")

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    @Test
    fun `revalidate - a socket timeout is unknown and leaves the session alone`() = runTest(dispatcher) {
        sessionManager.set(activeSession())
        coEvery { api.session() } throws SocketTimeoutException("slow")

        assertEquals(SessionCheck.Unknown, repo.revalidateSession())
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }
```

> The `assertEquals(SessionState.Active, ...)` line in every `Unknown` case **is the feature**. It is the assertion that fails if someone later "simplifies" the enum into a `Boolean` or a nullable.

- [ ] **Step 2: Write the failing contract test**

In `app/src/test/java/com/mediplus/spapp/data/remote/AuthApiContractTest.kt`, add this test immediately after the existing `` `the session endpoint re-reads the same resource` `` test (which ends at line 134). All imports it needs are already in the file.

```kotlin
    @Test
    fun `a 200 on the session endpoint leaves the session Active`() = runTest {
        sessionManager.set(activeSession("tok-xyz"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SESSION_BODY))

        api.session()

        // Paired with `401 on a protected call invalidates the session` below, this is the end-to-end
        // proof that revalidation routes correctly: a healthy session survives, a dead one does not.
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run (PowerShell):
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.AuthRepositoryTest" --tests "com.mediplus.spapp.data.remote.AuthApiContractTest"
```
Expected: FAIL — compilation error, `Unresolved reference: revalidateSession` and `Unresolved reference: SessionCheck` in `AuthRepositoryTest`. (`AuthApiContractTest` compiles but cannot run while its sibling is broken; it passes once compilation succeeds — that one is a regression guard for behaviour the interceptor already has.)

- [ ] **Step 4: Add the enum and the interface method**

In `app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt`, insert the enum immediately **above** the `AuthRepository` interface KDoc (i.e. after the import block, before line 24):

```kotlin
/**
 * What one revalidation learned
 * (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md).
 *
 * Only [Ended] means the session is over, and by the time it is returned
 * [com.mediplus.spapp.core.network.AuthInterceptor] has already acted on it. Modelling the
 * third case as its own value rather than folding it into a nullable or a `Boolean` is the point:
 * fail-open stops being a rule someone has to remember and becomes a case the compiler makes you name.
 */
enum class SessionCheck {
    /** A 2xx. The session stands. */
    Valid,

    /** An explicit 401. The session has already been invalidated by the interceptor (FR-004, FR-004a). */
    Ended,

    /** 5xx, an unexpected status, or a transport failure. The session is left completely alone. */
    Unknown,
}
```

Then add the method to the `AuthRepository` interface, between `signOut()` and `sessionState()`:

```kotlin
    /**
     * Ask the back office whether this session is still live (FR-004). Called on every foregrounding
     * by [com.mediplus.spapp.core.session.SessionRevalidator] so an expiry is discovered before the
     * operator involves a patient, rather than passively at the next protected call.
     *
     * Never mutates session state: a 401 is acted on by
     * [com.mediplus.spapp.core.network.AuthInterceptor] — one rule, one place — and anything that is
     * not a definite answer returns [SessionCheck.Unknown] so a flaky connection can never force a
     * re-login.
     */
    suspend fun revalidateSession(): SessionCheck
```

- [ ] **Step 5: Implement it in `AuthRepositoryImpl`**

In the same file, add this to `AuthRepositoryImpl` between `signOut()` and `sessionState()` (after line 73):

```kotlin
    override suspend fun revalidateSession(): SessionCheck {
        val result = apiCall(dispatcher, { api.session() }) { response ->
            when {
                response.isSuccessful -> AppResult.Success(SessionCheck.Valid)
                // AuthInterceptor has already invalidated the session by the time we get here; this
                // value is returned so callers and tests can assert on it, not so anything must act.
                response.code() == HttpURLConnection.HTTP_UNAUTHORIZED ->
                    AppResult.Success(SessionCheck.Ended)
                else -> AppResult.Success(SessionCheck.Unknown)
            }
        }
        // apiCall maps a socket timeout to Timeout and any other IO failure to TransientFailure. Both
        // mean "we learned nothing", which is exactly Unknown — the session is left untouched.
        return (result as? AppResult.Success)?.data ?: SessionCheck.Unknown
    }
```

No new imports are needed: `apiCall`, `AppResult` and `HttpURLConnection` are all already imported in this file.

- [ ] **Step 6: Update the three other implementers so everything compiles**

In `app/src/debug/java/com/mediplus/spapp/dev/repository/FakeAuthRepository.kt`, add the import:

```kotlin
import com.mediplus.spapp.data.repository.SessionCheck
```

and this override after `signOut()` (after line 46):

```kotlin
    /**
     * The fake back office never expires a session, so revalidation always says it stands. Session
     * loss is exercised in a debug build through Dev Settings' "force expire" action, which drives
     * [SessionManager] directly.
     */
    override suspend fun revalidateSession(): SessionCheck = SessionCheck.Valid
```

In `app/src/debug/java/com/mediplus/spapp/dev/repository/SwitchingRepositories.kt`, add the import:

```kotlin
import com.mediplus.spapp.data.repository.SessionCheck
```

and this override inside `SwitchingAuthRepository`, after `signOut()` (after line 43):

```kotlin
    override suspend fun revalidateSession(): SessionCheck = pick().revalidateSession()
```

In `app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt`, add the import:

```kotlin
import com.mediplus.spapp.data.repository.SessionCheck
```

and this override to the file-private `FakeAuthRepository` (after its `signOut()`, around line 105):

```kotlin
    override suspend fun revalidateSession(): SessionCheck =
        throw UnsupportedOperationException("not exercised by these tests")
```

- [ ] **Step 7: Run the tests to verify they pass**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.AuthRepositoryTest" --tests "com.mediplus.spapp.data.remote.AuthApiContractTest" --tests "com.mediplus.spapp.ui.navigation.AppViewModelTest" --tests "com.mediplus.spapp.dev.*"
```
Expected: PASS. `AuthRepositoryTest` gains 6 tests, `AuthApiContractTest` gains 1. The `dev.*` filter covers `FakeAuthRepositoryTest`, `SwitchingRepositoryTest` and `SwitchingSeamRoutingTest`, which must still pass unchanged — the `AUTH` seam routing is untouched.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt app/src/debug/java/com/mediplus/spapp/dev/repository/FakeAuthRepository.kt app/src/debug/java/com/mediplus/spapp/dev/repository/SwitchingRepositories.kt app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt app/src/test/java/com/mediplus/spapp/data/remote/AuthApiContractTest.kt app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt
git commit -m "feat: add fail-open session revalidation to AuthRepository"
```

---

## Task 2: `SessionRevalidator` — the process-lifecycle observer

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/session/SessionRevalidator.kt`
- Test: `app/src/test/java/com/mediplus/spapp/core/session/SessionRevalidatorTest.kt` (create the directory)

**Interfaces:**
- Consumes: `AuthRepository.revalidateSession(): SessionCheck` and `SessionCheck` (Task 1); `SessionManager.sessionState: StateFlow<SessionState>`; `SessionState.Active`; `@MainDispatcher`.
- Produces: `@Singleton class SessionRevalidator @Inject constructor(authRepository: AuthRepository, sessionManager: SessionManager, @param:MainDispatcher dispatcher: CoroutineDispatcher) : DefaultLifecycleObserver` with `fun bind()` and `override fun onStart(owner: LifecycleOwner)`.

> **Note on layering:** `SessionRevalidator` lives in `core/session` but depends on `data/repository/AuthRepository`. This mirrors the existing `DiagnosticsPoller`, which lives in `core/diagnostics` and depends on `domain/usecase`. Process-lifecycle observers sit outside the one-way stack by nature — they are driven by the Android lifecycle, not by a screen. Do not "fix" this by inventing a use case wrapper; the design specifies the repository call directly.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/spapp/core/session/SessionRevalidatorTest.kt`.

```kotlin
package com.mediplus.spapp.core.session

import androidx.lifecycle.LifecycleOwner
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.SessionCheck
import com.mediplus.spapp.domain.model.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The trigger rules for revalidation on resume
 * (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md): exactly one call per
 * foregrounding when the app believes it has a live session, and no call at all otherwise.
 *
 * `bind()` — the ProcessLifecycleOwner registration — is a thin untested shim, following the
 * DiagnosticsPoller.bind() precedent. `onStart` is called directly here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRevalidatorTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private val authRepository = mockk<AuthRepository> {
        coEvery { revalidateSession() } returns SessionCheck.Valid
    }

    private fun sessionManager(state: SessionState): SessionManager =
        mockk(relaxed = true) { every { sessionState } returns MutableStateFlow(state) }

    @Test
    fun `foregrounding with an active session revalidates exactly once`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Active),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 1) { authRepository.revalidateSession() }
    }

    @Test
    fun `no session means no call - nothing goes out from the sign-in screen`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.None),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `an expired session is not revalidated`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Expired),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `an invalidated session is not revalidated`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Invalidated),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 0) { authRepository.revalidateSession() }
    }

    @Test
    fun `two foregroundings produce two calls - no once-per-process latch`() = runTest {
        val revalidator = SessionRevalidator(
            authRepository,
            sessionManager(SessionState.Active),
            StandardTestDispatcher(testScheduler),
        )

        revalidator.onStart(owner)
        runCurrent()
        revalidator.onStart(owner)
        runCurrent()

        coVerify(exactly = 2) { authRepository.revalidateSession() }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.session.SessionRevalidatorTest"
```
Expected: FAIL — `Unresolved reference: SessionRevalidator`.

- [ ] **Step 3: Write the revalidator**

Create `app/src/main/java/com/mediplus/spapp/core/session/SessionRevalidator.kt`.

```kotlin
package com.mediplus.spapp.core.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mediplus.spapp.core.di.MainDispatcher
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirms on every foregrounding that a session the app believes is [SessionState.Active] really
 * still is (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md).
 *
 * A session can expire server-side while the app sits backgrounded, and nothing on the device
 * notices because nothing is being called. Without this, the expiry surfaces passively at the next
 * protected request — usually the member-card verification, i.e. after the operator has already
 * asked the patient for their card. Nothing is unsafe; the patient has simply been walked through
 * steps that were never going to count.
 *
 * Deliberately a separate class from [com.mediplus.spapp.core.diagnostics.DiagnosticsPoller] rather
 * than a few lines inside it: the poller's contract is that every failure is swallowed and nothing
 * it does can affect the journey, which is the opposite of what an auth decision must be able to do.
 * Ordering between the two is a non-issue — both start on the same event and converge on the same
 * outcome whichever lands first.
 *
 * `onStart` rather than a per-Activity `ON_RESUME`: [ProcessLifecycleOwner] fires once per
 * foregrounding of the *process*, which is the event actually meant, and it is what the poller
 * already uses.
 *
 * The result is discarded on purpose. A 401 is acted on by
 * [com.mediplus.spapp.core.network.AuthInterceptor], which already owns that rule for every other
 * endpoint; everything else is [com.mediplus.spapp.data.repository.SessionCheck.Unknown] and leaves
 * the session alone (fail-open). Nothing here calls a [SessionManager] mutator.
 */
@Singleton
class SessionRevalidator @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    @param:MainDispatcher private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // No token, no call: on the sign-in screen the state is None and nothing goes out.
        if (sessionManager.sessionState.value != SessionState.Active) return
        // Not cancelled or throttled between foregroundings by design: one GET per foreground is
        // bounded by how fast a human can switch apps, and a minimum-interval guard would introduce
        // a window in which a known-dead session is treated as live.
        scope.launch { authRepository.revalidateSession() }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.session.SessionRevalidatorTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/session/SessionRevalidator.kt app/src/test/java/com/mediplus/spapp/core/session/SessionRevalidatorTest.kt
git commit -m "feat: revalidate the session on every foregrounding"
```

---

## Task 3: Bind it in `SpApp`, close open decision #2, and verify the whole gate

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/SpApp.kt`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md:4`

**Interfaces:**
- Consumes: `SessionRevalidator.bind()` (Task 2).
- Produces: nothing new. This task makes the feature live and closes the tracking note.

> **Note on testing:** the binding itself has no unit test, following the `DiagnosticsPoller.bind()` precedent — `ProcessLifecycleOwner.get()` needs a real Android process. Its verification is `assembleDebug` (proves the Hilt graph resolves `SessionRevalidator`, which is where a DI mistake would surface) plus the full gate below.

- [ ] **Step 1: Bind the revalidator in the Application**

Replace the whole of `app/src/main/java/com/mediplus/spapp/SpApp.kt` with:

```kotlin
package com.mediplus.spapp

import android.app.Application
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. Two process-lifecycle observers are bound here: the
 * [DiagnosticsPoller], which runs only while the app is foregrounded and the session is active, and
 * the [SessionRevalidator], which confirms on each foregrounding that an apparently-active session
 * is still live.
 */
@HiltAndroidApp
class SpApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
    }
}
```

- [ ] **Step 2: Verify the debug build and the Hilt graph**

Run:
```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. A Hilt binding error here would mean `SessionRevalidator`'s dependencies are not all in the graph — `AuthRepository` (bound to `SwitchingAuthRepository` in debug, `AuthRepositoryImpl` in release), `SessionManager` and `@MainDispatcher` all already are.

- [ ] **Step 3: Close open decision #2 in `CLAUDE.md`**

Under `### Open decisions from the 2026-07-28 spec realignment`, change the intro sentence:

- from: `Three questions the realignment surfaced and deliberately did **not** answer.`
- to: `Two questions the realignment surfaced and deliberately did **not** answer.`

Then **delete** the entire numbered item 2 (the paragraph beginning `2. **`GET /auth/session` is aligned but unwired.**` and ending `...why that is not something to rely on.`), and renumber the following item from `3.` to `2.` (the `MemberVerification.capabilities` one). Item 1 (the authenticated-APK-download question) is unchanged.

Then, in the `## Current state to be aware of` section, add this bullet immediately after the `**Device diagnostics telemetry**` bullet:

```markdown
- **Session revalidation on resume** (design: `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md`):
  `SessionRevalidator` (a second `ProcessLifecycleOwner` observer, bound beside `DiagnosticsPoller` in
  `SpApp.onCreate()`) calls `GET /auth/session` on every foregrounding, but only when
  `sessionState == Active`. `AuthRepository.revalidateSession()` returns `SessionCheck.Valid`/`Ended`/`Unknown`
  and **never mutates session state** — a 401 is acted on by `AuthInterceptor`, which stays the single
  owner of that rule, and everything else (5xx, unexpected status, `IOException`, timeout) is `Unknown`
  and leaves the session untouched. Fail-open is deliberate: a flaky clinic connection must never force
  a re-login. This is **not** session persistence — the token still lives only in `InMemorySessionManager`,
  so process death still ends the session. No new screen, string or `UiMessage`; the operator just reaches
  the existing "session ended" notice on resume instead of three patient-facing steps later.
```

- [ ] **Step 4: Flip the design doc status**

In `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md`, change line 4:

- from: `**Status:** Proposed — not implemented`
- to: `**Status:** Implemented — 2026-07-28`

and line 6:

- from: ``**Tracked as:** open decision #2 in `CLAUDE.md` ``
- to: ``**Tracked as:** was open decision #2 in `CLAUDE.md`; closed on implementation ``

- [ ] **Step 5: Run the full gate**

Run each and confirm:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew testDebugUnitTest
```
Expected: PASS, with 11 more tests than before this plan (6 in `AuthRepositoryTest`, 1 in `AuthApiContractTest`, 5 in `SessionRevalidatorTest` — minus nothing removed; the suite was ~380 before).

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; ./gradlew lintDebug
```
Expected: BUILD SUCCESSFUL (`abortOnError=true`, so any new Lint issue fails the build).

detekt is **not** a Gradle task. Run the CLI exactly as CI does, from the repo root:
```
curl -sSLO https://github.com/detekt/detekt/releases/download/v1.23.7/detekt-cli-1.23.7.zip
unzip -q detekt-cli-1.23.7.zip
./detekt-cli-1.23.7/bin/detekt-cli --config config/detekt/detekt.yml --input app/src/main/java --build-upon-default-config
```
Expected: the same ~48 weighted issues that are already on `main` (`Color.kt` magic numbers, `NfcModels` naming, line length, `VerifyFaceUseCase` return count) and **no new ones** in `AuthRepository.kt`, `SessionRevalidator.kt` or `SpApp.kt`. Compare against the baseline before assuming this change caused a failure. Do not commit the downloaded zip/directory.

- [ ] **Step 6: Manual smoke check on the emulator (optional but recommended)**

The unit suite proves the trigger rules and the classification; this proves the wiring. With the `AUTH` seam faked (the default), sign in, press Home, then reopen the app — the journey must continue undisturbed (the fake always answers `Valid`). Then use Dev Settings' "force expire" action and confirm the app pops to sign-in with the existing "session ended" notice. Screenshots, if taken: `adb shell screencap -p /sdcard/x.png` then `adb pull` — `adb exec-out screencap -p > file.png` corrupts the PNG under PowerShell.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/SpApp.kt CLAUDE.md docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md
git commit -m "feat: bind the session revalidator and close open decision #2"
```

---

## Deferred — explicitly out of scope

Named here so a reviewer does not read their absence as an oversight. Every one of these is called out in the design's "Out of scope" section:

- **Persisting the token / resuming across process death.** A materially different decision with its own threat model.
- **Throttling repeated foregroundings.** One `GET /auth/session` per foreground is cheaper than the diagnostics poll that already fires on the same event, and a minimum-interval guard would create a window in which a known-dead session is treated as live.
- **Acting on `SessionResource`'s contents.** The response carries a fresh `policy`, `provider` and `serverTime`; re-seeding the freshness window or provider name from a resume is a separate change, and mixing it in would make a read-only check quietly mutate session state.
- **Reaching `SessionState.Expired`.** An expiry detected this way arrives as `Invalidated` because the interceptor gets there first, and the two are indistinguishable to the operator.
- **Revalidating on any other trigger** — no timer, no pre-flight check before individual journey steps.
- **A new `AUTH` dev scenario for a dead session.** Dev Settings' existing "force expire" action already exercises the downstream chain.
