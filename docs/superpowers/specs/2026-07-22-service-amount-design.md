# Design: Amount and currency on service enrollment

**Date:** 2026-07-22
**Status:** Approved, pending implementation plan
**Feature area:** `001-identity-verification-enrollment` (US4, add-service)

## Goal

When an operator picks a service for the current visit, they must enter the
**amount** to charge and the **currency** it is charged in, and both must reach
the back office on the enrollment request. Today `AddServiceViewModel.submit`
sends only `serviceId` + `idempotencyKey`; nothing anywhere in the app — UI,
domain model, `EnrollRequest`, or `docs/openapi.yaml` — models money at all.

The currency is not invented by the app. The back office returns the list of
currencies it will accept, and the app enumerates that list for the operator.
This mirrors the rule already in force for services (`Service` doc comment:
"Only server-reported services are selectable; the app invents none").

## Scope

- **In scope:** an amount + currency entry step between choosing a service and
  submitting it; the `currencies` field on the services response; `currency` and
  `amountCents` on the enroll request; the no-currency block; dev scenarios for
  all three currency-count cases.
- **Out of scope:**
  - **Multi-currency arithmetic** — no conversion, no rates, no totals. The app
    transmits one amount in one currency and never computes across currencies.
  - **Currencies with other than 2 decimal places** (see Decision 5).
  - **A per-service currency restriction.** The currency list is site-wide for
    the patient, not per service (see Decision 2).
  - **Editing an amount after submission.** An enrollment is confirmed or it is
    not; there is no amend path.
  - Any change to identity verification, the face check, or the card scan.

## Decisions (locked during brainstorming)

1. **Money is required on every enrollment.** There is no "no charge" path and
   no optional-amount service. An enrollment without an amount cannot be
   submitted.

2. **The currency list arrives with the services list**, as a new top-level
   `currencies` array on `GET /patients/{memberNumber}/services`. One round trip,
   and the services and their permitted currencies can never disagree because
   they are the same response. Rejected: a separate `GET /currencies` endpoint
   (a second call with its own loading and failure states to sequence before the
   list can render) and per-service currencies (flexible, but it turns "no
   currency" into a per-row dead end rather than one clear block).

3. **No currency is a terminal halt at load time.** An empty `currencies` array
   short-circuits *before* `Ready` is ever emitted: the service list never
   renders and there is no Confirm button to press. Rejected: letting the list
   render and failing on tap — every service would be a dead end the operator
   only discovers after investing a choice.

   This is a **distinct phase from the existing `Blocked`**, which means identity
   verification is outstanding. Conflating them would tell the operator to
   re-verify a patient when the actual remedy is a back-office configuration fix.

4. **Amount entry is a modal dialog over the list, and a ViewModel phase.**
   `AddServiceViewModel` already models every state as an exhaustive
   `AddServicePhase` ("Every state of the add-service step (Principle III)").
   Amount entry becomes a phase, so it survives rotation for free and is testable
   in the existing JVM `AddServiceViewModelTest` with no Compose test rig.
   Rejected: `remember`ed dialog state in the composable (dies on rotation,
   untestable without an instrumented test) and a nullable field alongside
   `phase` (permits illegal combinations such as an open dialog while
   `Submitting`).

5. **Minor units on the wire, 2 decimal places for every currency.**
   `amountCents: Long` — no floating-point rounding anywhere, and the domain
   holds an integer. Every currency in play is 2-decimal (ZAR, USD, EUR, GBP).

   **This is the assumption most likely to need revisiting.** A 0-decimal (JPY)
   or 3-decimal (KWD) currency appearing in the backend's list would make the app
   send a wrong amount by a factor of 100 or 10 — silently. Mitigation is
   deliberately deferred, not absent: see "Known limitation" below.

6. **Retry reuses the amount and currency verbatim**, alongside the existing
   reused idempotency key. A retry that changed either value under a key that had
   already landed would disagree with what the back office recorded — the same
   class of bug the idempotency key exists to prevent (FR-022).

7. **`value` is transmitted, `label` is only displayed.** The app never parses,
   maps, or validates the label, and never sends it.

## Architecture

### Money — a pure value type

```kotlin
// domain/model/Money.kt  (src/main)
data class Money(val cents: Long) {
    companion object {
        /** Null for anything not a positive amount with at most 2 decimal places. */
        fun parse(text: String): Money?
    }
}
```

A plain `data class`, not a `@JvmInline value class`: value-class parameters are
name-mangled by the Kotlin compiler, which makes MockK's `coEvery` stubs across
the repository seam unreliable. The allocation is not worth defending against
here.

No Android, no Compose, no coroutines — a straight JVM unit-test target.
Parsing is **locale-independent**: `.` is the only decimal separator and only
ASCII digits are accepted, so a device locale can never change what is sent.

Valid: `"150"`, `"150.5"`, `"150.00"`, `"0.01"`.
Invalid (all yield `null`): `""`, `"0"`, `"-1"`, `"1.234"`, `"abc"`, `"1,50"`,
`" 150 "`, and any value that would overflow `Long`.

### Currency and the catalog

```kotlin
// domain/model/EnrollmentModels.kt  (src/main)
/** A currency the back office will accept. The app enumerates; it invents none. */
data class Currency(val value: String, val label: String)

/** What one services call returns: what can be added, and in what currencies. */
data class ServiceCatalog(
    val services: List<Service>,
    val currencies: List<Currency>,
)
```

`ListEligibleServicesUseCase` returns `AppResult<ServiceCatalog>` instead of
`AppResult<List<Service>>`, so the services and their currencies travel together
and cannot drift apart in the state.

### The new phases

```kotlin
// ui/addservice/AddServiceViewModel.kt
enum class UnavailableReason { NO_CURRENCY }

sealed interface AddServicePhase {
    // ...existing phases unchanged...

    /** Amount + currency entry for [selected], shown as a dialog over [services]. */
    data class EnteringAmount(
        val services: List<Service>,
        val currencies: List<Currency>,
        val selected: Service,
        val selectedCurrency: Currency,
        val amountText: String,
    ) : AddServicePhase

    /** The step cannot proceed for a reason the operator cannot fix by retrying. */
    data class Unavailable(val reason: UnavailableReason) : AddServicePhase
}
```

`selectedCurrency` is **non-null**. A currency is guaranteed present by the time
`EnteringAmount` can exist (Decision 3), so "no currency at submit time" is
unrepresentable rather than defensively handled.

`Ready` keeps its existing `services`-only shape. The currency list is retained
in a private `currencies: List<Currency>` field on the ViewModel, set once when
the catalog loads, and copied into `EnteringAmount` when a service is chosen.
Currencies are load-scoped configuration rather than per-screen state, and the
`Ready` UI has no use for them; the ViewModel survives rotation, so this gives
the same durability as holding them in the phase.

### State transitions

```
start()
  ├─ not currently verified ────────────────→ Blocked(outstanding)      [unchanged]
  ├─ catalog.currencies.isEmpty() ──────────→ Unavailable(NO_CURRENCY)  [new]
  └─ otherwise ─────────────────────────────→ Ready(services)

Ready ──onSelect(serviceId)──→ EnteringAmount        [was: straight to Submitting]
EnteringAmount ──amountChanged(text)──→ EnteringAmount
EnteringAmount ──currencySelected(currency)──→ EnteringAmount
EnteringAmount ──cancelAmount()──→ Ready(services)
EnteringAmount ──confirmAmount()──→ Submitting       [only when Money.parse succeeds]
Submitting ──→ Confirmed | Failed | Uncertain        [unchanged]
```

`confirmAmount()` parses the text and generates the idempotency key **only on a
valid parse**. The dialog's Confirm button is disabled while the text does not
parse, so an invalid amount is unrepresentable at submit time rather than merely
rejected after the fact.

### Retained submission state

`AddServiceViewModel` currently holds `pendingServiceId` and `idempotencyKey`
across retries. It gains `pendingAmount: Money?` and `pendingCurrency: Currency?`,
set together with the key in `confirmAmount()` and reused verbatim by `retry()`
(Decision 6). `recheck()` is unchanged — it identifies the enrollment by key
alone.

### Use case and repository signatures

```kotlin
// domain/usecase/AddServiceUseCase.kt
suspend operator fun invoke(
    serviceId: String,
    currency: String,
    amount: Money,
    idempotencyKey: String,
): AppResult<Enrollment>

// data/repository/EnrollmentRepository.kt
suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog>
suspend fun enroll(
    memberNumber: String,
    serviceId: String,
    currency: String,
    amount: Money,
    idempotencyKey: String,
): AppResult<Enrollment>
```

`recheck` is unchanged.

`Enrollment` gains **nullable** `currency: String?` and `amount: Money?` so a
confirmed enrollment records what was actually charged. They are nullable
because `recheck` identifies an enrollment by idempotency key alone and the
response carries no amount — the repository has nothing to populate them with on
that path. This follows the precedent already in the code, where the recheck path
builds an `Enrollment` with a null `enrollmentId` and a `Service` reconstructed
with an empty `serviceId` and `description`. Nothing in the UI reads these fields;
they exist so the record is complete on the submit path and so the dev fake can
replay what was submitted.

### Wire contract

`GET /patients/{memberNumber}/services`:

```json
{
  "services": [
    { "serviceId": "svc-blood", "description": "Blood test",
      "eligibleForPatient": true, "alreadySelected": false }
  ],
  "currencies": [
    { "value": "ZAR", "label": "Rand (R)" },
    { "value": "USD", "label": "US Dollar ($)" }
  ]
}
```

`currencies` defaults to an empty list when the key is absent, which resolves to
`Unavailable(NO_CURRENCY)` — the fail-safe direction (Decision 3).

`POST /patients/{memberNumber}/enrollments`:

```json
{
  "serviceId": "svc-blood",
  "idempotencyKey": "3f0c...",
  "currency": "ZAR",
  "amountCents": 15000
}
```

```kotlin
// data/remote/EnrollmentApi.kt
@Serializable
data class CurrencyDto(val value: String, val label: String)

@Serializable
data class ServicesResponse(
    val services: List<ServiceDto> = emptyList(),
    val currencies: List<CurrencyDto> = emptyList(),
)

@Serializable
data class EnrollRequest(
    val serviceId: String,
    val idempotencyKey: String,
    val currency: String,
    val amountCents: Long,
)
```

`docs/openapi.yaml` is updated to match: `currencies` on `ServicesResponse` and
required `currency` (string) + `amountCents` (integer, `minimum: 1`) on
`EnrollRequest`.

### UI

An `AlertDialog` over the existing list, rendered when the phase is
`EnteringAmount`. Title is the service description. Body is the currency control
plus a single `OutlinedTextField` with `KeyboardType.Decimal`. Actions are Cancel
and Confirm, Confirm disabled while `Money.parse(amountText) == null`.

The currency control depends on how many currencies there are:

- **Exactly one** — auto-selected, rendered as a **static label** beside the
  amount field, not a disabled dropdown. A one-option picker is a decision the
  operator cannot make; presenting it as a control invites a tap that does
  nothing.
- **Two or more** — an `ExposedDropdownMenuBox` listing each `label`, defaulting
  to the first entry in the backend's order.

`Unavailable(NO_CURRENCY)` renders in the shape of the existing `BlockedContent`
— centred, `liveRegion = Polite`, no action button — with copy that names the
cause and the remedy, e.g. "Services can't be added right now because no currency
is set up for this site. Contact the back office."

All new copy goes in `strings.xml` beside the existing `addservice_*` keys. The
currency symbol is never hardcoded in a composable — the backend's `label` is the
only currency text shown.

`Submitting`, `Confirmed`, `Failed`, and `Uncertain` render exactly as they do
today.

### Dev tooling

`FakeData` gains a `currencies` list. `DevScenarios` gains:

```kotlin
enum class CurrencyScenario { MULTIPLE, SINGLE, NONE }
```

`FakeEnrollmentRepository.listServices` returns a `ServiceCatalog` whose
currencies follow the scenario, making the no-currency block, the auto-select
path, and the dropdown all reachable on-device with no backend. `enroll` accepts
and echoes the currency and amount into the `Enrollment` it records, so the
idempotent replay in `landed` still returns what was actually submitted.

## Error handling

| Situation | Behaviour |
|---|---|
| `currencies` empty or key absent | `Unavailable(NO_CURRENCY)` at load; list never renders |
| Amount empty / `0` / negative / >2dp / non-numeric | Confirm disabled; no submission attempted |
| Services call fails | `Failed(canRetry = true)` — unchanged |
| Enroll rejected (duplicate / ineligible) | `Failed(canRetry = false)` — unchanged |
| Enroll times out | `Uncertain` → `recheck()` by key — unchanged |
| Backend rejects the amount or currency by policy | Ordinary business rejection; surfaces through the existing `ErrorMapper` path |

No client-side maximum is enforced. Amount ceilings are back-office policy, and a
constant in the app would silently drift from it.

## Testing

**`MoneyTest`** (new, JVM) — the parse table in full, both valid and invalid
columns from the Money section above, plus `Long` overflow.

**`AddServiceViewModelTest`** (extended, JVM)
- empty `currencies` yields `Unavailable(NO_CURRENCY)` and never `Ready`
- a single currency arrives pre-selected in `EnteringAmount`
- `onSelect` reaches `EnteringAmount`, not `Submitting`
- `confirmAmount` with unparseable text does not submit
- `cancelAmount` returns to `Ready` with the list intact
- a currency chosen from a multi-currency list is the one submitted
- `retry()` resubmits the same key, amount, **and** currency

**`AddServiceUseCaseTest`** (extended, JVM) — currency and amount are passed
through to the repository unaltered; the not-currently-verified guard still fires
before any of it.

**`EnrollmentApiContractTest`** (extended, JVM, MockWebServer)
- the recorded enroll body carries both `"currency":"ZAR"` and `"amountCents":15000`
- a services response containing `currencies` parses into the catalog
- a services response with **no** `currencies` key parses to an empty list rather
  than throwing

This last group is the one that matters most: it is the only test that proves the
amount and currency actually reach the backend in the agreed shape.

**`FakeEnrollmentRepositoryTest`** (extended) — each `CurrencyScenario` yields the
expected catalog, and a replayed idempotency key returns the originally submitted
amount and currency.

## Deployment constraint

**The backend must ship first.** This design requires the server to return
`currencies` on the services response and to accept `currency` + `amountCents` on
enroll. Until it does, the app blocks every operator at load with the
no-currency message, because an absent `currencies` key parses to empty
(Decision 3). That is the correct fail-safe behaviour — no enrollment is ever
submitted without a currency — but it does mean **this client change cannot ship
ahead of the server change.**

## Known limitation

The 2-decimal assumption (Decision 5) is unguarded: if the backend ever returns a
0-decimal or 3-decimal currency, the app will send an amount wrong by a factor of
100 or 10 with no error. The fix, if that day comes, is a `decimals` field on
`CurrencyDto` driving both `Money.parse` and the minor-unit conversion. It is
deferred rather than built now because no such currency is in use, and the
contract change is additive.
