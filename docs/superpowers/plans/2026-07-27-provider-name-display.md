# Provider Name Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the clinic/provider name (from the login response) as a header subtitle throughout the signed-in journey and again on the final confirmation drawer before a service is submitted.

**Architecture:** A new nullable `provider` object rides on the existing login response → domain `Session` (held in the in-memory `SessionManager`). The header reads it via a new `AppViewModel.providerName` flow; the confirmation drawer reads a snapshot captured into `AddServicePhase.ReviewingSummary` at `confirmAmount()`, mirroring how `patient` is already captured. Missing/blank name is fail-open: header shows just the app title, drawer omits the provider section.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + kotlinx.serialization, Coroutines/StateFlow. Tests: JUnit4 + MockK.

## Global Constraints

- Package namespace is `com.mediplus.spapp` (post-rename). Every new file uses it.
- `JAVA_HOME` must be set before any Gradle call: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell).
- Test-first (TDD), ≥ 80% coverage on changed code, with explicit success **and** absence-path tests.
- detekt (run in CI, not a Gradle task): functions ≤ 50 lines, line length ≤ 120, `ReturnCount` ≤ 4, no bare `TODO`/`FIXME`.
- No hardcoded dp or colors — use `LocalSpacing`, theme typography, and `MaterialTheme.colorScheme` roles only.
- No user-facing free text beyond the server-supplied clinic name itself; all static strings live in `res/values/strings.xml` as `@StringRes`.
- Screens must not apply their own window insets — `NavGraph`'s `Scaffold` owns them. Do not add insets in the header change.
- Fail-open: a missing/blank provider name never blocks the operator.

---

### Task 1: Provider on the wire and in the session

Adds the `provider` field to the login DTO and domain `Session`, maps it in the repository (blank → null), and documents the placeholder in the OpenAPI contract.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/domain/model/Session.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/data/remote/AuthApi.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt`
- Modify: `docs/openapi.yaml`
- Test: `app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt`

**Interfaces:**
- Produces: `com.mediplus.spapp.domain.model.Provider(val name: String)`; `Session` gains `val provider: Provider? = null` (last constructor param, default keeps existing call sites valid). `LoginResponse` gains `val provider: ProviderDto? = null`; new `ProviderDto(val name: String? = null)`.

- [ ] **Step 1: Write the failing tests**

Add these three tests to `AuthRepositoryTest.kt` (inside the class). Also add imports at the top:
`import com.mediplus.spapp.data.remote.ProviderDto` and `import org.junit.Assert.assertNull`.

```kotlin
@Test
fun `login maps a provider name onto the session`() = runTest(dispatcher) {
    coEvery { api.login(any()) } returns Response.success(
        loginResponse().copy(provider = ProviderDto(name = "Riverside Clinic")),
    )

    repo.signIn("sam", "pw")

    assertEquals("Riverside Clinic", sessionManager.session.value?.provider?.name)
}

@Test
fun `login with a blank provider name yields no provider`() = runTest(dispatcher) {
    coEvery { api.login(any()) } returns Response.success(
        loginResponse().copy(provider = ProviderDto(name = "   ")),
    )

    repo.signIn("sam", "pw")

    assertNull(sessionManager.session.value?.provider)
}

@Test
fun `login without a provider yields no provider`() = runTest(dispatcher) {
    coEvery { api.login(any()) } returns Response.success(loginResponse())

    repo.signIn("sam", "pw")

    assertNull(sessionManager.session.value?.provider)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.AuthRepositoryTest"
```
Expected: FAIL — `ProviderDto` unresolved (compile error), and `provider` not a member of `Session`.

- [ ] **Step 3: Add `Provider` and the `provider` field to `Session.kt`**

In `Session.kt`, add the `Provider` model above `Session` and the field to the data class:

```kotlin
/**
 * The service provider (clinic/organization) the authenticated session belongs to. Display-only;
 * surfaced in the app chrome and on the final confirmation. Absent until the back office supplies
 * it, in which case it is simply not shown (fail-open).
 */
data class Provider(val name: String)

data class Session(
    val token: String,
    val operator: Operator,
    val expiresAt: Long?,
    val state: SessionState = SessionState.Active,
    val provider: Provider? = null,
) {
    /** Never leak the token via toString() (FR-029). */
    override fun toString(): String =
        "Session(operator=${operator.operatorId}, expiresAt=$expiresAt, state=$state, token=<redacted>)"
}
```

- [ ] **Step 4: Add `ProviderDto` and the `provider` field to `AuthApi.kt`**

In `AuthApi.kt`, add `provider` to `LoginResponse` and a new DTO:

```kotlin
@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String? = null,
    val operator: OperatorDto,
    val provider: ProviderDto? = null,
    val config: SessionConfigDto? = null,
)

@Serializable
data class ProviderDto(
    val name: String? = null,
)
```

- [ ] **Step 5: Map the provider in `AuthRepository.kt`**

In `AuthRepository.kt`, add the import `import com.mediplus.spapp.domain.model.Provider`, then update the `toSession()` extension to map the provider (blank → null):

```kotlin
private fun LoginResponse.toSession(): Session = Session(
    token = token,
    operator = Operator(
        operatorId = operator.operatorId,
        displayName = operator.displayName,
        permissions = operator.permissions.toSet(),
    ),
    expiresAt = expiresAt?.let { parseEpochMillisOrNull(it) },
    state = SessionState.Active,
    provider = provider?.name?.takeIf { it.isNotBlank() }?.let { Provider(it) },
)
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.data.repository.AuthRepositoryTest"
```
Expected: PASS (all tests, including the three new ones).

- [ ] **Step 7: Document the placeholder in `docs/openapi.yaml`**

In the `LoginResponse` schema (around line 531), add `provider` between `operator` and `config`, and add the `Provider` schema after the `Operator` schema:

```yaml
        operator:
          $ref: '#/components/schemas/Operator'
        provider:
          $ref: '#/components/schemas/Provider'
        config:
          $ref: '#/components/schemas/SessionConfig'

    Provider:
      type: [object, "null"]
      description: |
        App-invented placeholder (reconcile when the server publishes its real
        shape). The service provider (clinic/organization) the session belongs
        to. Display-only; the app shows the name in the app chrome and on the
        final confirmation. If absent or blank, nothing is shown (fail-open).
      properties:
        name:
          type: [string, "null"]
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/domain/model/Session.kt \
  app/src/main/java/com/mediplus/spapp/data/remote/AuthApi.kt \
  app/src/main/java/com/mediplus/spapp/data/repository/AuthRepository.kt \
  app/src/test/java/com/mediplus/spapp/data/repository/AuthRepositoryTest.kt \
  docs/openapi.json
git commit -m "feat: carry clinic provider name on the login session"
```

---

### Task 2: Provider name in the header

Exposes the provider name as a flow on `AppViewModel` and renders it as a subtitle line under the app title in the `TopAppBar`.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/ui/navigation/AppViewModel.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/ui/navigation/NavGraph.kt`
- Test: `app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt`

**Interfaces:**
- Consumes: `SessionManager.session: StateFlow<Session?>`; `Session.provider` (Task 1).
- Produces: `AppViewModel.providerName: StateFlow<String?>` — the clinic name, or null when absent.

- [ ] **Step 1: Write the failing tests**

Add to `AppViewModelTest.kt`. Add the import `import com.mediplus.spapp.domain.model.Provider`. The existing `setUp()` seeds a session with no provider, so the null case can assert against `vm` directly; the present case builds a fresh session + VM.

```kotlin
@Test
fun `provider name is null when the session has none`() {
    assertEquals(null, vm.providerName.value)
}

@Test
fun `provider name is exposed from the session`() {
    sessionManager.set(
        Session(
            "tok",
            Operator("op-1", "Sam"),
            expiresAt = null,
            state = SessionState.Active,
            provider = Provider("Riverside Clinic"),
        ),
    )
    val freshVm = AppViewModel(sessionManager, repo)

    assertEquals("Riverside Clinic", freshVm.providerName.value)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.ui.navigation.AppViewModelTest"
```
Expected: FAIL — `providerName` is not a member of `AppViewModel`.

- [ ] **Step 3: Add `providerName` to `AppViewModel.kt`**

Add imports:
```kotlin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
```

Add the property alongside `sessionState` (inside the class body):
```kotlin
    /** The clinic/provider name for the active session, or null when the back office omitted it. */
    val providerName: StateFlow<String?> = sessionManager.session
        .map { it?.provider?.name }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = sessionManager.session.value?.provider?.name,
        )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.ui.navigation.AppViewModelTest"
```
Expected: PASS.

- [ ] **Step 5: Render the subtitle in `NavGraph.kt`**

Add the import `import androidx.compose.foundation.layout.Column`.

In `NavGraph(...)`, collect the name (next to the existing `sessionState` collection):
```kotlin
    val providerName by appViewModel.providerName.collectAsStateWithLifecycle()
```

Pass it into the app bar — change the `topBar` lambda:
```kotlin
            topBar = { if (showAppBar) AppBar(providerName = providerName, onLogOutClick = { confirmingLogOut = true }) },
```

Replace the `AppBar` composable's signature and title slot:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(providerName: String?, onLogOutClick: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.appbar_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (providerName != null) {
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            // Never disabled: log out has to work mid-capture and mid-request alike.
            IconButton(onClick = onLogOutClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = stringResource(R.string.action_log_out),
                )
            }
        },
    )
}
```

- [ ] **Step 6: Verify the debug build compiles**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/ui/navigation/AppViewModel.kt \
  app/src/main/java/com/mediplus/spapp/ui/navigation/NavGraph.kt \
  app/src/test/java/com/mediplus/spapp/ui/navigation/AppViewModelTest.kt
git commit -m "feat: show provider name as a header subtitle"
```

---

### Task 3: Provider name on the final confirmation

Captures the provider name into the summary phase at `confirmAmount()` and renders a Provider section at the top of the confirmation drawer.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/ui/addservice/AddServiceViewModel.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/ui/addservice/AddServiceSummaryDrawer.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/spapp/ui/addservice/AddServiceViewModelTest.kt`

**Interfaces:**
- Consumes: `SessionManager.session` / `Session.provider` (Task 1).
- Produces: `AddServicePhase.ReviewingSummary` gains `val providerName: String?` (added as the first constructor param). `AddServiceViewModel` constructor gains a `sessionManager: SessionManager` parameter (appended after `errorMapper`).

- [ ] **Step 1: Write the failing tests**

In `AddServiceViewModelTest.kt`, add imports:
```kotlin
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Provider
import com.mediplus.spapp.domain.model.Session
```

Add a session-manager field and update `buildVm()` to pass it:
```kotlin
    private val sessionManager = InMemorySessionManager()

    private fun buildVm() =
        AddServiceViewModel(listServices, addService, evaluate, endVisit, DefaultErrorMapper(), sessionManager)
```

Add the two tests:
```kotlin
@Test
fun `summary captures the provider name from the session`() {
    every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
    coEvery { listServices() } returns AppResult.Success(catalog)
    sessionManager.set(
        Session("tok", Operator("op-1", "Sam"), expiresAt = null, provider = Provider("Riverside Clinic")),
    )
    val vm = buildVm()

    vm.selectService("s1")
    vm.amountEntryChanged(text = "150.00")
    vm.confirmAmount()

    val phase = vm.uiState.value.phase
    assertTrue(phase is AddServicePhase.ReviewingSummary)
    assertEquals("Riverside Clinic", (phase as AddServicePhase.ReviewingSummary).providerName)
}

@Test
fun `summary has no provider name when the session has none`() {
    every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
    coEvery { listServices() } returns AppResult.Success(catalog)
    val vm = buildVm()

    vm.selectService("s1")
    vm.amountEntryChanged(text = "150.00")
    vm.confirmAmount()

    val phase = vm.uiState.value.phase
    assertEquals(null, (phase as AddServicePhase.ReviewingSummary).providerName)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.ui.addservice.AddServiceViewModelTest"
```
Expected: FAIL — `AddServiceViewModel` constructor has no 6th parameter, and `ReviewingSummary` has no `providerName`.

- [ ] **Step 3: Add `providerName` to `ReviewingSummary` and inject `SessionManager`**

In `AddServiceViewModel.kt`, add the import `import com.mediplus.spapp.core.session.SessionManager`.

Add `providerName` as the first field of `ReviewingSummary`:
```kotlin
    data class ReviewingSummary(
        val providerName: String?,
        val services: List<Service>,
        val patient: MemberDetails?,
        val selected: Service,
        val currency: Currency,
        val amount: Money,
    ) : AddServicePhase
```

Append `sessionManager` to the constructor:
```kotlin
@HiltViewModel
class AddServiceViewModel @Inject constructor(
    private val listServices: ListEligibleServicesUseCase,
    private val addService: AddServiceUseCase,
    private val evaluate: EvaluateVerifiedIdentityUseCase,
    private val endPatientVisit: EndPatientVisitUseCase,
    private val errorMapper: ErrorMapper,
    private val sessionManager: SessionManager,
) : ViewModel() {
```

- [ ] **Step 4: Populate `providerName` in `confirmAmount()`**

In `confirmAmount()`, set the new field when constructing `ReviewingSummary` (read the snapshot from the session, consistent with how `patient` is snapshotted):
```kotlin
        _uiState.value = AddServiceUiState(
            AddServicePhase.ReviewingSummary(
                providerName = sessionManager.session.value?.provider?.name,
                services = phase.services,
                patient = patient,
                selected = phase.selected,
                currency = phase.selectedCurrency,
                amount = amount,
            ),
        )
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.ui.addservice.AddServiceViewModelTest"
```
Expected: PASS.

- [ ] **Step 6: Add the drawer string**

In `app/src/main/res/values/strings.xml`, add next to the other `addservice_summary_*` entries (after `addservice_summary_patient_heading`):
```xml
    <string name="addservice_summary_provider_heading">Provider</string>
```

- [ ] **Step 7: Render the Provider section at the top of the drawer**

In `AddServiceSummaryDrawer.kt`, add `import androidx.compose.material3.MaterialTheme` is already present; ensure `androidx.compose.material3.Text`, `FontWeight`, and `MaterialTheme` imports exist (they do). Insert the provider section as the first body element (immediately after the `Text(... addservice_summary_desc ...)` block, before `PatientSection`):
```kotlin
        phase.providerName?.let { ProviderSection(it) }
        PatientSection(phase.patient)
```

Add the composable (place it just above `PatientSection`):
```kotlin
@Composable
private fun ProviderSection(name: String) {
    SectionHeading(R.string.addservice_summary_provider_heading)
    Text(
        text = name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
```

- [ ] **Step 8: Verify the debug build compiles**

```bash
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run the full unit suite**

```bash
./gradlew testDebugUnitTest
```
Expected: PASS (no regressions in the ~200-test suite).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/ui/addservice/AddServiceViewModel.kt \
  app/src/main/java/com/mediplus/spapp/ui/addservice/AddServiceSummaryDrawer.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/mediplus/spapp/ui/addservice/AddServiceViewModelTest.kt
git commit -m "feat: show provider name on the final confirmation drawer"
```

---

## Notes for the implementer

- **Device-gated visual check (not a task blocker):** the two-line `TopAppBar` and the drawer's Provider section are rendered UI. The unit tests cover the data (flow value, captured field). A manual emulator pass — log in with a fake provider name via the debug "SP App Dev" settings if wired, otherwise confirm the fail-open path renders the single-line header and no provider row — should be done before field rollout, alongside the other device-gated items in CLAUDE.md.
- **detekt:** the new `ProviderSection` and the reshaped `AppBar` stay well under 50 lines; keep the two-line title `Column` inline rather than extracting, to avoid adding a trivial composable.
