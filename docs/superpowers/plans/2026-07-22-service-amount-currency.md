# Amount and Currency on Service Enrollment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator enter an amount and pick a backend-supplied currency when adding a service, and transmit both on the enrollment request.

**Architecture:** The services endpoint starts returning a `currencies` list alongside `services`; the two travel together as one `ServiceCatalog`. An empty currency list halts the screen at load with a dedicated `Unavailable` phase. Choosing a service moves the ViewModel into a new `EnteringAmount` phase which the screen renders as a dialog over the list; confirming parses the text into a `Money` and submits `currency` + `amountCents` with the existing idempotency key.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + kotlinx.serialization, Coroutines/Flow, JUnit4 + MockK + MockWebServer.

**Source spec:** `docs/superpowers/specs/2026-07-22-service-amount-design.md`

## Global Constraints

- **Build toolchain is fixed.** AGP 9 with built-in Kotlin, `compileSdk 37`, `targetSdk 36`, `minSdk 24`, JVM target 11. Do not edit `build.gradle.kts`, `gradle/libs.versions.toml`, or `gradle.properties` — this feature needs **no new dependencies**.
- **No linter is wired.** There is no ktlint or detekt task. Match the surrounding file's style by eye: 4-space indent, trailing commas in multi-line argument lists, ~120 column limit.
- **All unit tests run with:** `./gradlew testDebugUnitTest` (covers both the `test` and `testDebug` source sets). Run from the repo root in Git Bash.
- **All user-facing copy lives in `app/src/main/res/values/strings.xml`.** Never hardcode a display string in a composable. Apostrophes must be escaped as `\'`.
- **Currency `value` is transmitted; `label` is only ever displayed.** Never send, parse, or branch on a label.
- **Money is always minor units (cents) on the wire**, as `amountCents: Long`. Every currency is assumed to have 2 decimal places.
- **Debug-only code lives in `app/src/debug/`** and its tests in `app/src/testDebug/`. Release builds must not reference any of it.
- **Work on branch `feat/service-amount-currency`**, which already exists and holds the design spec commit.

---

### Task 1: The `Money` value type

A self-contained, dependency-free parser. Nothing consumes it yet — Task 4 is the first caller.

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/domain/model/Money.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/domain/model/MoneyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class Money(val cents: Long)` with `companion object { fun parse(text: String): Money? }`.

Note on the type choice: this is a plain `data class`, **not** a `@JvmInline value class`. Value-class parameters get name-mangled by the Kotlin compiler, which makes MockK's `coEvery { repository.enroll(...) }` stubs in Task 5 unreliable. A regular data class costs an allocation nobody will ever measure and keeps the test seam simple.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/domain/model/MoneyTest.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money.parse is the only gate between operator keystrokes and what is transmitted, so the
 * invalid column matters as much as the valid one: anything it lets through is charged.
 */
class MoneyTest {

    @Test
    fun `whole numbers become cents`() {
        assertEquals(Money(15_000), Money.parse("150"))
    }

    @Test
    fun `one decimal place is padded to two`() {
        assertEquals(Money(15_050), Money.parse("150.5"))
    }

    @Test
    fun `two decimal places are exact`() {
        assertEquals(Money(15_000), Money.parse("150.00"))
    }

    @Test
    fun `the smallest positive amount is one cent`() {
        assertEquals(Money(1), Money.parse("0.01"))
    }

    @Test
    fun `zero is rejected`() {
        assertNull(Money.parse("0"))
        assertNull(Money.parse("0.00"))
    }

    @Test
    fun `negatives are rejected`() {
        assertNull(Money.parse("-1"))
        assertNull(Money.parse("-1.50"))
    }

    @Test
    fun `more than two decimal places is rejected`() {
        assertNull(Money.parse("1.234"))
    }

    @Test
    fun `empty and non-numeric input is rejected`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("."))
        assertNull(Money.parse("1."))
    }

    @Test
    fun `a comma is never a decimal separator`() {
        assertNull(Money.parse("1,50"))
    }

    @Test
    fun `surrounding whitespace is rejected rather than trimmed`() {
        assertNull(Money.parse(" 150 "))
    }

    @Test
    fun `an absurdly long number is rejected instead of overflowing`() {
        assertNull(Money.parse("1234567890123456"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.domain.model.MoneyTest"`
Expected: FAIL — compilation error, `Unresolved reference: Money`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/mediplus/faceverify/domain/model/Money.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

/**
 * A positive amount of money in minor units (cents), decoupled from any currency — the currency is
 * carried separately because the back office supplies the list of allowed ones.
 *
 * Minor units rather than a decimal type so no floating-point rounding can ever reach the wire.
 * Every currency in use is assumed to have 2 decimal places; see the "Known limitation" section of
 * the design spec.
 */
data class Money(val cents: Long) {

    companion object {
        /**
         * At most 15 whole digits keeps `whole * 100` inside [Long] with room to spare, so the
         * digit cap doubles as the overflow guard.
         */
        private val PATTERN = Regex("""^\d{1,15}(\.\d{1,2})?$""")

        /**
         * Parses operator input. Returns null for anything that is not a strictly positive amount
         * with at most two decimal places — empty, zero, negative, over-precise, or malformed.
         *
         * Deliberately locale-independent: `.` is the only accepted decimal separator and only
         * ASCII digits are accepted, so the device locale can never change what gets transmitted.
         * Whitespace is rejected rather than trimmed, so the caller never has to wonder whether the
         * string it holds is the string that was parsed.
         */
        fun parse(text: String): Money? {
            if (!PATTERN.matches(text)) return null
            val whole = text.substringBefore('.')
            val fraction = text.substringAfter('.', missingDelimiterValue = "").padEnd(2, '0')
            val cents = whole.toLong() * 100 + fraction.toLong()
            return if (cents > 0) Money(cents) else null
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.domain.model.MoneyTest"`
Expected: PASS — 11 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/domain/model/Money.kt app/src/test/java/com/mediplus/faceverify/domain/model/MoneyTest.kt
git commit -m "feat: add Money, a minor-units amount parser"
```

---

### Task 2: Carry the currency list through the services call

Changes `listServices` to return a `ServiceCatalog` instead of a bare service list, all the way from the DTO to the ViewModel. This is one task because a signature change on a repository interface breaks every implementer and caller at once — splitting it would leave the build red.

The ViewModel reads `catalog.services` and ignores the currencies for now. Task 3 acts on them.

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/domain/model/EnrollmentModels.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/data/remote/EnrollmentApi.kt:33-42`
- Modify: `app/src/main/java/com/mediplus/faceverify/data/repository/EnrollmentRepository.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCase.kt:13-22`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt:66-71`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt:36-47`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt:73-74`
- Modify: `docs/openapi.yaml:416-423`
- Test: `app/src/test/java/com/mediplus/faceverify/data/remote/EnrollmentApiContractTest.kt:52-65`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt:15-22`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `data class Currency(val value: String, val label: String)`
  - `data class ServiceCatalog(val services: List<Service>, val currencies: List<Currency>)`
  - `EnrollmentRepository.listServices(memberNumber: String): AppResult<ServiceCatalog>`
  - `ListEligibleServicesUseCase.invoke(): AppResult<ServiceCatalog>`
  - `CurrencyDto(val value: String, val label: String)`
  - `FakeData.currencies: List<Currency>`

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/mediplus/faceverify/data/remote/EnrollmentApiContractTest.kt`, **replace** the existing `lists eligible services` test (lines 52-65) with these two:

```kotlin
    @Test
    fun `lists eligible services with their currencies`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"serviceId":"s1","description":"Consultation","eligibleForPatient":true,"alreadySelected":false}],"currencies":[{"value":"ZAR","label":"Rand (R)"}]}""",
            ),
        )

        val result = repository.listServices("P1")

        val catalog = (result as AppResult.Success).data
        assertEquals(1, catalog.services.size)
        assertEquals("Consultation", catalog.services.first().description)
        assertEquals(1, catalog.currencies.size)
        assertEquals("ZAR", catalog.currencies.first().value)
        assertEquals("Rand (R)", catalog.currencies.first().label)
    }

    @Test
    fun `a services response with no currencies key parses to an empty list`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"serviceId":"s1","description":"Consultation","eligibleForPatient":true,"alreadySelected":false}]}""",
            ),
        )

        val result = repository.listServices("P1")

        assertTrue((result as AppResult.Success).data.currencies.isEmpty())
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.data.remote.EnrollmentApiContractTest"`
Expected: FAIL — compilation error, `Unresolved reference: currencies` (`AppResult.Success.data` is still a `List<Service>`).

- [ ] **Step 3: Add the domain types**

Append to `app/src/main/java/com/mediplus/faceverify/domain/model/EnrollmentModels.kt`, directly after the `Service` class:

```kotlin
/**
 * A currency the back office will accept for an enrollment. Like [Service], the app enumerates what
 * the server reports and invents none. [value] is what goes on the wire; [label] is display-only.
 */
data class Currency(val value: String, val label: String)

/**
 * What one services call returns: what can be added, and in which currencies. The two travel
 * together so they can never drift apart in the UI state.
 */
data class ServiceCatalog(
    val services: List<Service>,
    val currencies: List<Currency>,
)
```

- [ ] **Step 4: Add the DTO**

In `app/src/main/java/com/mediplus/faceverify/data/remote/EnrollmentApi.kt`, replace the `ServicesResponse` declaration (line 34) and add `CurrencyDto` beneath it:

```kotlin
@Serializable
data class ServicesResponse(
    val services: List<ServiceDto> = emptyList(),
    val currencies: List<CurrencyDto> = emptyList(),
)

@Serializable
data class CurrencyDto(val value: String, val label: String)
```

The `emptyList()` default on `currencies` is load-bearing: an absent key must parse to empty, which Task 3 turns into a hard stop, rather than throwing.

- [ ] **Step 5: Change the repository interface and implementation**

In `app/src/main/java/com/mediplus/faceverify/data/repository/EnrollmentRepository.kt`:

Change the import block to add the two new domain types and the DTO:

```kotlin
import com.mediplus.faceverify.data.remote.CurrencyDto
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.ServiceCatalog
```

Change the interface method (line 27):

```kotlin
    suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog>
```

Change the implementation (lines 37-48):

```kotlin
    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> =
        apiCall(dispatcher, { api.listServices(memberNumber) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(
                    ServiceCatalog(
                        services = body.services.map(ServiceDto::toDomain),
                        currencies = body.currencies.map(CurrencyDto::toDomain),
                    ),
                )
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC))
            }
        }
```

Add the mapper beside the existing `ServiceDto.toDomain` at the bottom of the file (after line 91):

```kotlin
private fun CurrencyDto.toDomain() = Currency(value, label)
```

- [ ] **Step 6: Change the use case**

In `app/src/main/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCase.kt`, change `ListEligibleServicesUseCase.invoke` (line 17):

```kotlin
    suspend operator fun invoke(): AppResult<ServiceCatalog> {
```

Add the import:

```kotlin
import com.mediplus.faceverify.domain.model.ServiceCatalog
```

and delete `import com.mediplus.faceverify.domain.model.Service` (line 9). That method signature was its only use in this file — `AddServiceUseCase` references only `Enrollment`.

- [ ] **Step 7: Change the ViewModel to read the catalog**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`, change the `start()` body (lines 66-71):

```kotlin
        viewModelScope.launch {
            _uiState.value = when (val result = listServices()) {
                is AppResult.Success -> AddServiceUiState(AddServicePhase.Ready(result.data.services))
                else -> AddServiceUiState(AddServicePhase.Failed(map(result), canRetry = true))
            }
        }
```

- [ ] **Step 8: Update the debug fakes**

In `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`, add the import and the canned list beneath `services`:

```kotlin
import com.mediplus.faceverify.domain.model.Currency
```

```kotlin
    val currencies: List<Currency> = listOf(
        Currency("ZAR", "Rand (R)"),
        Currency("USD", "US Dollar ($)"),
    )
```

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`, add the import:

```kotlin
import com.mediplus.faceverify.domain.model.ServiceCatalog
```

and replace `listServices` (lines 36-47):

```kotlin
    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.services) {
            ServicesScenario.SUCCESS -> AppResult.Success(ServiceCatalog(FakeData.services, FakeData.currencies))
            ServicesScenario.EMPTY -> AppResult.Success(ServiceCatalog(emptyList(), FakeData.currencies))
            ServicesScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            ServicesScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }
```

`ServicesScenario.EMPTY` means *no services*, not *no currencies* — the currency list stays populated so the two scenarios remain independently reachable.

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`, add the import:

```kotlin
import com.mediplus.faceverify.domain.model.ServiceCatalog
```

and change `SwitchingEnrollmentRepository.listServices` (lines 73-74):

```kotlin
    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> =
        pick().listServices(memberNumber)
```

The `Service` import at line 18 is now unused by this file — remove it.

- [ ] **Step 9: Update the remaining tests**

In `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`, add the imports:

```kotlin
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.ServiceCatalog
```

add a catalog fixture beneath the existing `services` field (after line 40):

```kotlin
    private val currencies = listOf(Currency("ZAR", "Rand (R)"), Currency("USD", "US Dollar ($)"))
    private val catalog = ServiceCatalog(services, currencies)
```

and replace **every** occurrence of `coEvery { listServices() } returns AppResult.Success(services)` (lines 56, 79, 91, 106) with:

```kotlin
        coEvery { listServices() } returns AppResult.Success(catalog)
```

In `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`, change the assertion in `listServices success returns the canned list` (line 21):

```kotlin
        assertEquals(FakeData.services, (result as AppResult.Success).data.services)
```

- [ ] **Step 10: Update the API contract doc**

In `docs/openapi.yaml`, replace the `ServicesResponse` schema (lines 416-423) with:

```yaml
    ServicesResponse:
      type: object
      properties:
        services:
          type: array
          items:
            $ref: '#/components/schemas/Service'
          default: []
        currencies:
          type: array
          items:
            $ref: '#/components/schemas/Currency'
          default: []
          description: >
            Currencies the back office accepts for an enrollment. An empty list
            halts the add-service step client-side with an explanatory message —
            no enrollment is ever submitted without a currency.

    Currency:
      type: object
      required: [value, label]
      properties:
        value:
          type: string
          description: Transmitted verbatim as `currency` on EnrollRequest.
          example: ZAR
        label:
          type: string
          description: Display only. Never parsed or transmitted by the app.
          example: Rand (R)
```

- [ ] **Step 11: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — the whole suite, including the two new contract tests.

- [ ] **Step 12: Commit**

```bash
git add app/src docs/openapi.json
git commit -m "feat: return currencies alongside services as a ServiceCatalog"
```

---

### Task 3: Halt at load when no currency is available

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`

**Interfaces:**
- Consumes: `ServiceCatalog`, `Currency` (Task 2).
- Produces:
  - `enum class UnavailableReason { NO_CURRENCY }`
  - `AddServicePhase.Unavailable(val reason: UnavailableReason)`

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`:

```kotlin
    @Test
    fun `no currencies halts the step instead of listing services`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(ServiceCatalog(services, emptyList()))

        val vm = buildVm()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Unavailable)
        assertEquals(UnavailableReason.NO_CURRENCY, (phase as AddServicePhase.Unavailable).reason)
    }

    @Test
    fun `services with currencies still reach the ready state`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)

        val vm = buildVm()

        assertTrue(vm.uiState.value.phase is AddServicePhase.Ready)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.ui.addservice.AddServiceViewModelTest"`
Expected: FAIL — compilation error, `Unresolved reference: Unavailable`.

- [ ] **Step 3: Add the phase and the load-time check**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`, add above the `AddServicePhase` interface:

```kotlin
/** Why the add-service step cannot proceed at all — not fixable by retrying. */
enum class UnavailableReason { NO_CURRENCY }
```

Add this member to the `AddServicePhase` interface, after `Uncertain`:

```kotlin
    /**
     * A terminal halt distinct from [Blocked]: the identity is fine, but the back office reported
     * no usable currency, so no enrollment could ever be submitted. Kept separate because [Blocked]
     * tells the operator to re-verify the patient, which is the wrong remedy here.
     */
    data class Unavailable(val reason: UnavailableReason) : AddServicePhase
```

Replace the `start()` coroutine body:

```kotlin
        viewModelScope.launch {
            _uiState.value = when (val result = listServices()) {
                is AppResult.Success -> {
                    val catalog = result.data
                    // Checked before Ready is ever emitted: the list must not render if nothing on
                    // it could be submitted (FR-023a).
                    if (catalog.currencies.isEmpty()) {
                        AddServiceUiState(AddServicePhase.Unavailable(UnavailableReason.NO_CURRENCY))
                    } else {
                        AddServiceUiState(AddServicePhase.Ready(catalog.services))
                    }
                }
                else -> AddServiceUiState(AddServicePhase.Failed(map(result), canRetry = true))
            }
        }
```

- [ ] **Step 4: Add the strings**

In `app/src/main/res/values/strings.xml`, add after the `addservice_blocked_stale` line:

```xml
    <string name="addservice_unavailable_title">Can\'t add services</string>
    <string name="addservice_unavailable_no_currency">No currency is set up for this site, so a service can\'t be added right now. Please contact the back office.</string>
```

- [ ] **Step 5: Render the new phase**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt`, add a branch to the `when (val phase = state.phase)` block, after the `Uncertain` branch:

```kotlin
        is AddServicePhase.Unavailable -> UnavailableContent(phase.reason, modifier)
```

and add the composable after `BlockedContent`:

```kotlin
@Composable
private fun UnavailableContent(reason: UnavailableReason, modifier: Modifier) {
    val bodyRes = when (reason) {
        UnavailableReason.NO_CURRENCY -> R.string.addservice_unavailable_no_currency
    }
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.addservice_unavailable_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}
```

There is deliberately **no action button** — retrying cannot change a back-office configuration.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — full suite green.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: halt the add-service step when no currency is available"
```

---

### Task 4: The `EnteringAmount` phase

The ViewModel captures the amount and currency. It does **not** transmit them yet — the repository signature still takes only `serviceId` and the key. Task 5 completes the path. This split keeps each task's build green and each reviewable on its own.

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt:48,67`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`

**Interfaces:**
- Consumes: `Money.parse` (Task 1), `Currency` (Task 2), `AddServicePhase.Unavailable` (Task 3).
- Produces:
  - `AddServicePhase.EnteringAmount(services, currencies, selected, selectedCurrency, amountText)`
  - `AddServiceViewModel.selectService(serviceId: String)` — replaces `submit(serviceId)`
  - `AddServiceViewModel.amountChanged(text: String)`
  - `AddServiceViewModel.currencySelected(currency: Currency)`
  - `AddServiceViewModel.cancelAmount()`
  - `AddServiceViewModel.confirmAmount()`

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`. Also add the import `import com.mediplus.faceverify.domain.model.Money`:

```kotlin
    @Test
    fun `selecting a service opens amount entry rather than submitting`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.EnteringAmount)
        assertEquals("s1", (phase as AddServicePhase.EnteringAmount).selected.serviceId)
        assertEquals("", phase.amountText)
    }

    @Test
    fun `the first currency is preselected`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals(Currency("ZAR", "Rand (R)"), phase.selectedCurrency)
        assertEquals(currencies, phase.currencies)
    }

    @Test
    fun `an unparseable amount never submits`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.amountChanged("abc")
        vm.confirmAmount()

        assertTrue(vm.uiState.value.phase is AddServicePhase.EnteringAmount)
        coVerify(exactly = 0) { addService(any(), any()) }
    }

    @Test
    fun `cancelling returns to the list with it intact`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.cancelAmount()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Ready)
        assertEquals(services, (phase as AddServicePhase.Ready).services)
    }

    @Test
    fun `a valid amount submits and confirms`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()
        vm.selectService("s1")

        vm.amountChanged("150.00")
        vm.confirmAmount()

        assertEquals(AddServicePhase.Confirmed("E1"), vm.uiState.value.phase)
    }

    @Test
    fun `switching currency is remembered`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.currencySelected(Currency("USD", "US Dollar ($)"))

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals("USD", phase.selectedCurrency.value)
    }
```

Add the import `import io.mockk.coVerify`.

The three existing tests that call `vm.submit("s1")` (`a confirmed submission reaches the confirmed state`, `a duplicate is a non-retryable failure`, `a timeout is uncertain, never confirmed`) must now drive the new path. In each, replace the single `vm.submit("s1")` line with:

```kotlin
        vm.selectService("s1")
        vm.amountChanged("150.00")
        vm.confirmAmount()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.ui.addservice.AddServiceViewModelTest"`
Expected: FAIL — compilation error, `Unresolved reference: selectService`.

- [ ] **Step 3: Add the phase**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`, add the imports:

```kotlin
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Money
```

Add to the `AddServicePhase` interface, after `Ready`:

```kotlin
    /**
     * Amount and currency entry for [selected], rendered as a dialog over [services].
     * [selectedCurrency] is non-null: a currency is guaranteed present by the time this phase can
     * exist, so "no currency at submit time" is unrepresentable rather than defensively handled.
     */
    data class EnteringAmount(
        val services: List<Service>,
        val currencies: List<Currency>,
        val selected: Service,
        val selectedCurrency: Currency,
        val amountText: String,
    ) : AddServicePhase
```

- [ ] **Step 4: Add the ViewModel state and transitions**

Add the retained fields beside the existing ones (after line 52, `private var idempotencyKey: String? = null`):

```kotlin
    private var pendingAmount: Money? = null
    private var pendingCurrency: Currency? = null

    /**
     * Load-scoped configuration, not per-screen state: the Ready UI has no use for it, and the
     * ViewModel outlives rotation, so this is as durable as holding it in the phase would be.
     */
    private var currencies: List<Currency> = emptyList()
```

In `start()`, capture the currencies on the success path — replace the `else` branch of the currency check added in Task 3:

```kotlin
                    if (catalog.currencies.isEmpty()) {
                        AddServiceUiState(AddServicePhase.Unavailable(UnavailableReason.NO_CURRENCY))
                    } else {
                        currencies = catalog.currencies
                        AddServiceUiState(AddServicePhase.Ready(catalog.services))
                    }
```

Replace the whole `submit(serviceId)` function (lines 74-79) with these five:

```kotlin
    /** Open amount entry for [serviceId]. Nothing is submitted until [confirmAmount]. */
    fun selectService(serviceId: String) {
        val ready = _uiState.value.phase as? AddServicePhase.Ready ?: return
        val service = ready.services.firstOrNull { it.serviceId == serviceId } ?: return
        val currency = currencies.firstOrNull() ?: return
        _uiState.value = AddServiceUiState(
            AddServicePhase.EnteringAmount(
                services = ready.services,
                currencies = currencies,
                selected = service,
                selectedCurrency = currency,
                amountText = "",
            ),
        )
    }

    fun amountChanged(text: String) {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        _uiState.value = AddServiceUiState(phase.copy(amountText = text))
    }

    fun currencySelected(currency: Currency) {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        _uiState.value = AddServiceUiState(phase.copy(selectedCurrency = currency))
    }

    fun cancelAmount() {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        _uiState.value = AddServiceUiState(AddServicePhase.Ready(phase.services))
    }

    /**
     * Submit with a fresh idempotency key — but only once the text parses, so an invalid amount is
     * unrepresentable at submit time rather than rejected after the fact.
     */
    fun confirmAmount() {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        val amount = Money.parse(phase.amountText) ?: return
        pendingServiceId = phase.selected.serviceId
        pendingCurrency = phase.selectedCurrency
        pendingAmount = amount
        idempotencyKey = UUID.randomUUID().toString()
        runSubmit()
    }
```

- [ ] **Step 5: Point the screen at the new entry point**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt`, change line 48:

```kotlin
        onSelect = viewModel::selectService,
```

and add a branch to the `when` block so it stays exhaustive — for now `EnteringAmount` renders just the list; the dialog arrives in Task 6:

```kotlin
        is AddServicePhase.EnteringAmount -> ServiceList(phase.services, onSelect, modifier)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — full suite green.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: capture amount and currency in an EnteringAmount phase"
```

---

### Task 5: Transmit currency and amount

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/data/remote/EnrollmentApi.kt:44-48`
- Modify: `app/src/main/java/com/mediplus/faceverify/data/repository/EnrollmentRepository.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/domain/model/EnrollmentModels.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCase.kt:35-42`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt:76-77`
- Modify: `docs/openapi.yaml:441-450`
- Test: `app/src/test/java/com/mediplus/faceverify/data/remote/EnrollmentApiContractTest.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCaseTest.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`

**Interfaces:**
- Consumes: `Money` (Task 1), `Currency` (Task 2), `pendingAmount` / `pendingCurrency` (Task 4).
- Produces:
  - `EnrollRequest(serviceId, idempotencyKey, currency, amountCents)`
  - `EnrollmentRepository.enroll(memberNumber: String, serviceId: String, currency: String, amount: Money, idempotencyKey: String): AppResult<Enrollment>`
  - `AddServiceUseCase.invoke(serviceId: String, currency: String, amount: Money, idempotencyKey: String): AppResult<Enrollment>`
  - `Enrollment.currency: String?`, `Enrollment.amount: Money?`

- [ ] **Step 1: Write the failing test**

This is the test that proves the feature works — everything else is plumbing that serves it. Add to `app/src/test/java/com/mediplus/faceverify/data/remote/EnrollmentApiContractTest.kt` (add the import `import com.mediplus.faceverify.domain.model.Money`):

```kotlin
    @Test
    fun `the enroll body carries the currency and the amount in cents`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"enrollmentId":"E1","status":"CONFIRMED","timestamp":"2026-07-20T12:40:00Z"}""",
            ),
        )

        repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("currency missing from $body", body.contains(""""currency":"ZAR""""))
        assertTrue("amountCents missing from $body", body.contains(""""amountCents":15000"""))
    }
```

Update the four existing `repository.enroll("P1", "s1", "key1")` call sites (in `confirmed enrollment succeeds`, `duplicate is prevented`, `ineligible is a specific rejection`, `timeout mid-submit is uncertain, never success`) to:

```kotlin
        val result = repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.data.remote.EnrollmentApiContractTest"`
Expected: FAIL — compilation error, `Too many arguments for ... enroll`.

- [ ] **Step 3: Extend the request DTO**

In `app/src/main/java/com/mediplus/faceverify/data/remote/EnrollmentApi.kt`, replace `EnrollRequest` (lines 44-48):

```kotlin
@Serializable
data class EnrollRequest(
    val serviceId: String,
    val idempotencyKey: String,
    /** The currency's `value`, never its display label. */
    val currency: String,
    /** Minor units, so no floating-point rounding can reach the wire. */
    val amountCents: Long,
)
```

- [ ] **Step 4: Extend the domain record**

In `app/src/main/java/com/mediplus/faceverify/domain/model/EnrollmentModels.kt`, replace the `Enrollment` class:

```kotlin
data class Enrollment(
    val enrollmentId: String?,
    val memberNumber: String,
    val service: Service,
    val idempotencyKey: String,
    val status: EnrollmentStatus,
    val timestampMillis: Long?,
    /**
     * What was charged. Null on the re-check path only: re-check identifies an enrollment by
     * idempotency key alone and the response carries no amount, so the repository has nothing to
     * populate these with there — the same reason [enrollmentId] and [Service.description] are
     * already empty on that path.
     */
    val currency: String? = null,
    val amount: Money? = null,
)
```

- [ ] **Step 5: Extend the repository**

In `app/src/main/java/com/mediplus/faceverify/data/repository/EnrollmentRepository.kt`, add the import:

```kotlin
import com.mediplus.faceverify.domain.model.Money
```

Change the interface method (line 28):

```kotlin
    suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment>
```

Replace the `enroll` implementation:

```kotlin
    override suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val request = EnrollRequest(serviceId, idempotencyKey, currency, amount.cents)
        return apiCall(dispatcher, { api.enroll(memberNumber, request) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null && body.isConfirmed() ->
                    AppResult.Success(body.toEnrollment(memberNumber, serviceId, idempotencyKey, currency, amount))
                response.code() == HttpURLConnection.HTTP_CONFLICT ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, body?.reason))
                response.code() == UNPROCESSABLE_ENTITY ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, body?.reason))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC, body?.reason))
            }
        }
    }
```

In `recheck`, change the success mapping to pass nulls:

```kotlin
                response.isSuccessful && body != null && body.isConfirmed() ->
                    AppResult.Success(
                        body.toEnrollment(memberNumber, serviceId = "", idempotencyKey, currency = null, amount = null),
                    )
```

Replace the `toEnrollment` helper at the bottom of the file:

```kotlin
private fun EnrollmentResponse.toEnrollment(
    memberNumber: String,
    serviceId: String,
    idempotencyKey: String,
    currency: String?,
    amount: Money?,
) = Enrollment(
    enrollmentId = enrollmentId,
    memberNumber = memberNumber,
    service = Service(serviceId, description = "", eligibleForPatient = true, alreadySelected = false),
    idempotencyKey = idempotencyKey,
    status = EnrollmentStatus.Confirmed(enrollmentId ?: ""),
    timestampMillis = timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    currency = currency,
    amount = amount,
)
```

- [ ] **Step 6: Extend the use case**

In `app/src/main/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCase.kt`, add the import `import com.mediplus.faceverify.domain.model.Money` and replace `invoke` (lines 35-42):

```kotlin
    suspend operator fun invoke(
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        if (!evaluate().isCurrentlyVerified) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        }
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
            ?: return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        return enrollmentRepository.enroll(memberNumber, serviceId, currency, amount, idempotencyKey)
    }
```

- [ ] **Step 7: Pass the captured values from the ViewModel**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModel.kt`, replace `runSubmit()`:

```kotlin
    private fun runSubmit() {
        val serviceId = pendingServiceId ?: return
        val currency = pendingCurrency ?: return
        val amount = pendingAmount ?: return
        val key = idempotencyKey ?: return
        _uiState.value = AddServiceUiState(AddServicePhase.Submitting)
        viewModelScope.launch {
            _uiState.value = AddServiceUiState(reduceSubmit(addService(serviceId, currency.value, amount, key)))
        }
    }
```

and tighten the `retry()` guard so a retry can never fire with a half-populated submission:

```kotlin
    /**
     * Retry the last submission, REUSING the idempotency key, amount, and currency so no duplicate
     * is created and nothing disagrees with what the back office already recorded (FR-022).
     */
    fun retry() {
        if (pendingServiceId != null && pendingCurrency != null && pendingAmount != null && idempotencyKey != null) {
            runSubmit()
        } else {
            start()
        }
    }
```

- [ ] **Step 8: Update the debug fakes**

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`, add `import com.mediplus.faceverify.domain.model.Money` and replace `enroll` and `confirmedEnrollment`:

```kotlin
    override suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val settings = store.current()
        delay(settings.latencyMillis)
        landed[idempotencyKey]?.let { return AppResult.Success(it) }
        val confirmed = confirmedEnrollment(memberNumber, serviceId, currency, amount, idempotencyKey)
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
```

```kotlin
    private fun confirmedEnrollment(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): Enrollment {
        val id = "enr-$idempotencyKey"
        val service = FakeData.services.firstOrNull { it.serviceId == serviceId }
            ?: Service(serviceId, "", eligibleForPatient = true, alreadySelected = false)
        return Enrollment(
            enrollmentId = id,
            memberNumber = memberNumber,
            service = service,
            idempotencyKey = idempotencyKey,
            status = EnrollmentStatus.Confirmed(id),
            timestampMillis = null,
            currency = currency,
            amount = amount,
        )
    }
```

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`, add `import com.mediplus.faceverify.domain.model.Money` and replace `SwitchingEnrollmentRepository.enroll`:

```kotlin
    override suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> = pick().enroll(memberNumber, serviceId, currency, amount, idempotencyKey)
```

- [ ] **Step 9: Update the remaining tests**

In `app/src/test/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCaseTest.kt`, add `import com.mediplus.faceverify.domain.model.Money` and update every call. Replace the four `enroll`-related tests with:

```kotlin
    @Test
    fun `unverified identity is blocked and never submitted`() = runTest {
        // not verified (no identity)
        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertEquals(BusinessCode.NOT_CURRENTLY_VERIFIED, (result as AppResult.BusinessRejection).error.code)
        verify { repository wasNot Called }
    }

    @Test
    fun `verified identity submits and confirms`() = runTest {
        markVerified()
        coEvery {
            repository.enroll("P1", "svc", "ZAR", Money(15_000), "key1")
        } returns AppResult.Success(confirmed("key1"))

        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `retry reuses the key, amount, and currency so no duplicate is created`() = runTest {
        markVerified()
        coEvery {
            repository.enroll("P1", "svc", "ZAR", Money(15_000), "key1")
        } returns AppResult.Success(confirmed("key1"))

        useCase("svc", "ZAR", Money(15_000), "key1")
        useCase("svc", "ZAR", Money(15_000), "key1") // retry, identical in every argument

        coVerify(exactly = 2) { repository.enroll("P1", "svc", "ZAR", Money(15_000), "key1") }
    }

    @Test
    fun `timeout is never reported as success`() = runTest {
        markVerified()
        coEvery { repository.enroll(any(), any(), any(), any(), any()) } returns AppResult.Timeout

        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertEquals(AppResult.Timeout, result)
    }
```

In `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt`, update the three `addService` stubs and the one verification to the 4-argument form: replace `addService(any(), any())` with `addService(any(), any(), any(), any())` everywhere it appears. Then add this test:

```kotlin
    @Test
    fun `retry resubmits the same key, amount, and currency`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Timeout
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountChanged("150.00")
        vm.confirmAmount()

        vm.retry()

        coVerify(exactly = 2) { addService("s1", "ZAR", Money(15_000), any()) }
    }
```

In `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`, add `import com.mediplus.faceverify.domain.model.Money` and update every `enroll` call site to the 5-argument form, e.g.:

```kotlin
        val result = FakeEnrollmentRepository(store).enroll("X123", "svc-blood", "ZAR", Money(15_000), "key-1")
```

Do this in all four tests that call `enroll` (`enroll confirmed yields a Confirmed status`, `enroll duplicate is a business rejection`, `timeout on enroll is resolvable by recheck with the same key`, `re-enrolling with the same key after a TIMEOUT replays the original Confirmed enrollment`). Then add:

```kotlin
    @Test
    fun `a replayed key returns the originally submitted amount and currency`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.TIMEOUT, latencyMillis = 0L))
        val repo = FakeEnrollmentRepository(store)
        repo.enroll("X123", "svc-blood", "ZAR", Money(15_000), "key-42")

        val retry = repo.enroll("X123", "svc-blood", "USD", Money(9_900), "key-42")

        val enrollment = (retry as AppResult.Success).data
        assertEquals("ZAR", enrollment.currency)
        assertEquals(Money(15_000), enrollment.amount)
    }
```

This asserts the fake behaves like a real back office: a landed key replays what was originally recorded, so a client bug that changed the amount on retry cannot silently "win".

- [ ] **Step 10: Update the API contract doc**

In `docs/openapi.yaml`, replace the `EnrollRequest` schema (lines 441-450):

```yaml
    EnrollRequest:
      type: object
      required: [serviceId, idempotencyKey, currency, amountCents]
      properties:
        serviceId:
          type: string
        idempotencyKey:
          type: string
          format: uuid
          description: Per-transaction key; retries reuse the same key (FR-022).
        currency:
          type: string
          description: >
            The `value` of a Currency returned by the services endpoint. Retries
            reuse the same currency, so a landed key never disagrees with what was
            recorded.
          example: ZAR
        amountCents:
          type: integer
          format: int64
          minimum: 1
          description: >
            Amount in minor units. The client assumes 2 decimal places for every
            currency and never sends a zero or negative amount.
          example: 15000
```

- [ ] **Step 11: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — full suite green, including `the enroll body carries the currency and the amount in cents`.

- [ ] **Step 12: Commit**

```bash
git add app/src docs/openapi.json
git commit -m "feat: send currency and amountCents on the enrollment request"
```

---

### Task 6: The amount entry dialog

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AddServicePhase.EnteringAmount`, `amountChanged`, `currencySelected`, `cancelAmount`, `confirmAmount` (Task 4); `Money.parse` (Task 1); `Currency` (Task 2).
- Produces: nothing consumed by later tasks.

There is no unit test for this task — the project has no Compose UI test harness for this screen, and the behaviour it drives is already covered by the ViewModel tests in Task 4. Verification is a build plus a manual check on the emulator.

- [ ] **Step 1: Add the strings**

In `app/src/main/res/values/strings.xml`, add after the `addservice_unavailable_no_currency` line:

```xml
    <string name="addservice_amount_label">Amount</string>
    <string name="addservice_currency_label">Currency</string>
```

- [ ] **Step 2: Add the imports**

In `app/src/main/java/com/mediplus/faceverify/ui/addservice/AddServiceScreen.kt`, add:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Money
```

- [ ] **Step 3: Widen the screen's callback surface**

Replace the `AddServiceRoute` call to `AddServiceScreen` (lines 46-53):

```kotlin
    AddServiceScreen(
        state = state,
        onSelect = viewModel::selectService,
        onAmountChange = viewModel::amountChanged,
        onCurrencyChange = viewModel::currencySelected,
        onCancelAmount = viewModel::cancelAmount,
        onConfirmAmount = viewModel::confirmAmount,
        onRetry = viewModel::retry,
        onRecheck = viewModel::recheck,
        onDone = onDone,
        modifier = modifier,
    )
```

Replace the `AddServiceScreen` signature and the `EnteringAmount` branch:

```kotlin
@Composable
fun AddServiceScreen(
    state: AddServiceUiState,
    onSelect: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    onCancelAmount: () -> Unit,
    onConfirmAmount: () -> Unit,
    onRetry: () -> Unit,
    onRecheck: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        AddServicePhase.LoadingServices, AddServicePhase.Submitting -> LoadingState(modifier = modifier)
        is AddServicePhase.Ready -> ServiceList(phase.services, onSelect, modifier)
        is AddServicePhase.EnteringAmount -> {
            ServiceList(phase.services, onSelect, modifier)
            AmountDialog(phase, onAmountChange, onCurrencyChange, onCancelAmount, onConfirmAmount)
        }
        is AddServicePhase.Blocked -> BlockedContent(phase.outstanding, modifier)
        is AddServicePhase.Confirmed -> ConfirmedContent(onDone, modifier)
        is AddServicePhase.Failed -> ErrorState(
            message = phase.message,
            onAction = if (phase.canRetry) onRetry else null,
            modifier = modifier,
        )
        is AddServicePhase.Uncertain -> UncertainContent(phase.message, onRecheck, modifier)
        is AddServicePhase.Unavailable -> UnavailableContent(phase.reason, modifier)
    }
}
```

- [ ] **Step 4: Add the dialog**

Add this composable after `ServiceRow`:

```kotlin
/**
 * Amount + currency entry over the service list. Confirm is disabled until the text parses, so an
 * invalid amount can never be submitted rather than being rejected afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountDialog(
    phase: AddServicePhase.EnteringAmount,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(phase.selected.description) },
        text = {
            Column {
                if (phase.currencies.size == 1) {
                    // A one-option picker is a decision the operator cannot make; showing it as a
                    // control would invite a tap that does nothing.
                    Text(
                        text = phase.selectedCurrency.label,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                    ) {
                        OutlinedTextField(
                            value = phase.selectedCurrency.label,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.addservice_currency_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            phase.currencies.forEach { currency ->
                                DropdownMenuItem(
                                    text = { Text(currency.label) },
                                    onClick = {
                                        onCurrencyChange(currency)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = phase.amountText,
                    onValueChange = onAmountChange,
                    label = { Text(stringResource(R.string.addservice_amount_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = Money.parse(phase.amountText) != null) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
```

- [ ] **Step 5: Verify it compiles and the suite still passes**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, full suite green.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: add the amount and currency entry dialog"
```

---

### Task 7: Dev scenarios for the currency list

Makes all three currency-count paths reachable on a device with no backend.

**Files:**
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`
- Test: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`

**Interfaces:**
- Consumes: `FakeData.currencies` (Task 2), `ServiceCatalog` (Task 2).
- Produces: `enum class CurrencyScenario { MULTIPLE, SINGLE, NONE }`, `DevSettings.currency`, `DevSettingsStore.setCurrency`.

- [ ] **Step 1: Write the failing test**

Add to `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeEnrollmentRepositoryTest.kt`:

```kotlin
    @Test
    fun `the MULTIPLE currency scenario returns every canned currency`() = runTest {
        val store = TestDevSettingsStore(DevSettings(currency = CurrencyScenario.MULTIPLE, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).listServices("X123")

        assertEquals(FakeData.currencies, (result as AppResult.Success).data.currencies)
    }

    @Test
    fun `the SINGLE currency scenario returns exactly one`() = runTest {
        val store = TestDevSettingsStore(DevSettings(currency = CurrencyScenario.SINGLE, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).listServices("X123")

        assertEquals(listOf(FakeData.currencies.first()), (result as AppResult.Success).data.currencies)
    }

    @Test
    fun `the NONE currency scenario returns none, so the step halts`() = runTest {
        val store = TestDevSettingsStore(DevSettings(currency = CurrencyScenario.NONE, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).listServices("X123")

        assertTrue((result as AppResult.Success).data.currencies.isEmpty())
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.dev.FakeEnrollmentRepositoryTest"`
Expected: FAIL — compilation error, `Unresolved reference: CurrencyScenario`.

- [ ] **Step 3: Add the scenario enum**

In `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`, add after `EnrollScenario`:

```kotlin
/** How many currencies the faked services endpoint reports. NONE halts the add-service step. */
enum class CurrencyScenario { MULTIPLE, SINGLE, NONE }
```

- [ ] **Step 4: Persist it**

In `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`:

Add the field to `DevSettings`, after `services`:

```kotlin
    val currency: CurrencyScenario = CurrencyScenario.MULTIPLE,
```

Add the key to `DevPrefKeys`, after `SERVICES`:

```kotlin
    val CURRENCY = stringPreferencesKey("dev_scenario_currency")
```

Add the mapping in `toDevSettings()`, after the `services =` line:

```kotlin
        currency = this[DevPrefKeys.CURRENCY].toEnumOr(defaults.currency),
```

In `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`, add to the interface after `setServices`:

```kotlin
    suspend fun setCurrency(scenario: CurrencyScenario)
```

and to `DataStoreDevSettingsStore`, after `setServices`:

```kotlin
    override suspend fun setCurrency(scenario: CurrencyScenario) =
        edit { it[DevPrefKeys.CURRENCY] = scenario.name }
```

In `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`, add after the `setServices` override:

```kotlin
    override suspend fun setCurrency(scenario: CurrencyScenario) { state.value = state.value.copy(currency = scenario) }
```

- [ ] **Step 5: Drive the fake from the scenario**

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeEnrollmentRepository.kt`, add `import com.mediplus.faceverify.dev.CurrencyScenario` and `import com.mediplus.faceverify.domain.model.Currency`, then replace `listServices`:

```kotlin
    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> {
        val settings = store.current()
        delay(settings.latencyMillis)
        val currencies = currenciesFor(settings.currency)
        return when (settings.services) {
            ServicesScenario.SUCCESS -> AppResult.Success(ServiceCatalog(FakeData.services, currencies))
            ServicesScenario.EMPTY -> AppResult.Success(ServiceCatalog(emptyList(), currencies))
            ServicesScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            ServicesScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    private fun currenciesFor(scenario: CurrencyScenario): List<Currency> = when (scenario) {
        CurrencyScenario.MULTIPLE -> FakeData.currencies
        CurrencyScenario.SINGLE -> FakeData.currencies.take(1)
        CurrencyScenario.NONE -> emptyList()
    }
```

- [ ] **Step 6: Expose it in the dev UI**

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsViewModel.kt`, add `import com.mediplus.faceverify.dev.CurrencyScenario` and, after `setServices`:

```kotlin
    fun setCurrency(scenario: CurrencyScenario) = launchEdit { store.setCurrency(scenario) }
```

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`, add `import com.mediplus.faceverify.dev.CurrencyScenario`, add the callback parameter after `onServices`:

```kotlin
    onCurrency: (CurrencyScenario) -> Unit,
```

and the picker row after the "Services list" row:

```kotlin
        ScenarioPicker("Currencies", CurrencyScenario.entries, settings.currency, onCurrency)
```

In `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsActivity.kt`, add after `onServices = vm::setServices,`:

```kotlin
                        onCurrency = vm::setCurrency,
```

- [ ] **Step 7: Run the full suite and build the debug APK**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, full suite green.

- [ ] **Step 8: Manual verification on a device or emulator**

Install the debug build, open the **Dev Settings** launcher icon, and confirm each path:

1. Set **Currencies → NONE**, run the journey to the add-service step. Expected: "Can't add services" with the no-currency message and no service list.
2. Set **Currencies → SINGLE**. Expected: the service list renders; tapping Confirm opens the dialog with "Rand (R)" as static text and no dropdown; Confirm is disabled until a valid amount is typed.
3. Set **Currencies → MULTIPLE**. Expected: the dialog shows a currency dropdown with both entries; picking USD keeps it selected.
4. With **Enrollment → TIMEOUT**, confirm an amount. Expected: the uncertain screen, and Re-check resolves it without a duplicate.

- [ ] **Step 9: Commit**

```bash
git add app/src
git commit -m "feat(dev): add the CurrencyScenario dev setting"
```

---

## Done criteria

- `./gradlew assembleDebug testDebugUnitTest` is green.
- `EnrollmentApiContractTest.the enroll body carries the currency and the amount in cents` passes — the only test that proves the wire format end to end.
- All four manual scenarios in Task 7 Step 8 behave as described.
- `docs/openapi.yaml` documents `currencies` on `ServicesResponse` and `currency` + `amountCents` on `EnrollRequest`.

## Deployment note for whoever merges this

The backend must return `currencies` on the services response and accept `currency` + `amountCents` on enroll **before** this ships. Until it does, an absent `currencies` key parses to empty and every operator is blocked at load with the no-currency message. That is the intended fail-safe — no enrollment is ever submitted without a currency — but it means this client change cannot lead the server change.
