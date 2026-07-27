# Provider name display — design

**Date:** 2026-07-27
**Status:** Approved for planning

## Problem

After an operator logs in, the app shows no indication of which service provider
(clinic/organization) the session belongs to. The operator should see the provider's name in
the app chrome throughout the journey, and again at the final confirmation step so it is present
on the last screen before a transaction is submitted to the back office.

"Provider" here means the **clinic/organization** the operator works for — not the logged-in
operator (`Operator.displayName`) and not the patient.

## Current state

- Login flow: `AuthApi.login` returns `LoginResponse` → mapped to `Session` by
  `AuthRepositoryImpl.toSession()` → stored in `SessionManager` (`session` StateFlow).
  `LoginResponse` carries `token`, `operator` (nested `OperatorDto`), `expiresAt`, and optional
  `config`. **There is no provider/organization name anywhere in the response or model.**
- Header: `NavGraph` owns the app's only chrome — a Material 3 `TopAppBar` whose title is the
  static `R.string.appbar_title`, with a log-out action. Shown on every destination except sign-in.
- Final confirmation: `AddServiceSummaryDrawer`, fed by `AddServicePhase.ReviewingSummary`. That
  phase already captures a `patient: MemberDetails?` snapshot at `AddServiceViewModel.confirmAmount()`
  so the summary can never name a different patient than the one that was verified.

## Data flow (new)

```
GET auth/login → provider (new, nullable)
      │
      ▼
LoginResponse.provider ──► Session.provider (Provider?) ──► SessionManager.session
                                                               │
              ┌────────────────────────────────────────────────┴───────────────┐
              ▼                                                                  ▼
   AppViewModel.providerName: StateFlow<String?>              AddServiceViewModel (inject SessionManager)
              │                                                    captured at confirmAmount()
              ▼                                                                  ▼
   NavGraph AppBar — subtitle under app title            ReviewingSummary.providerName: String?
                                                                                 ▼
                                                          AddServiceSummaryDrawer — top-of-drawer row
```

The `provider` value rides on the existing `session` StateFlow, so it is set once at login and
already wiped by `SessionManager.clearAll()` on any session loss — no new `SessionManager` surface.

## Decisions

1. **Wire/model shape — nested `provider` object.** `ProviderDto { name }` → domain
   `Provider(val name: String)` on `Session`, mirroring how `operator` already nests. Chosen over a
   flat `providerName: String?` for symmetry with the existing operator shape and room to grow
   (e.g. a future `providerId`) without another reshape.

2. **Confirmation plumbing — capture in the ViewModel.** Inject `SessionManager` into
   `AddServiceViewModel` and snapshot `providerName` into `ReviewingSummary` at `confirmAmount()`,
   exactly as `patient` is captured today. Chosen over reading it in the composable via a Hilt
   `@EntryPoint` (splits the drawer's data sourcing) or threading it through
   `EvaluateVerifiedIdentityUseCase` (conflates identity verification with org display).

3. **Header presentation — subtitle under the app title.** The `TopAppBar` title slot renders a
   two-line `Column`: line 1 = `appbar_title`, line 2 = the clinic name. The app keeps its own
   identity while surfacing the provider.

4. **Drawer position — top, above Patient.** The provider section is first in the summary, framing
   "who is providing" before "who it's for", then Service and Charge.

5. **Fail-safe = graceful omit (fail-open).** The clinic name is display-only and never a security
   decision, so a missing name never blocks the operator. This matters because the backend has not
   shipped the field yet — it is null on every real login today. When absent (or blank):
   - Header renders a single line (just `appbar_title`), exactly as today.
   - The confirmation drawer omits the provider section entirely.

## Changes

### Wire + model
- `data/remote/AuthApi.kt`
  - Add `provider: ProviderDto? = null` to `LoginResponse`.
  - New `@Serializable data class ProviderDto(val name: String? = null)`.
- `domain/model/Session.kt`
  - New `data class Provider(val name: String)`.
  - Add `val provider: Provider? = null` to `Session` (kept out of the redacted `toString()`; a
    clinic name is not sensitive, but the field is display-only and there is no reason to log it).
- `data/repository/AuthRepository.kt`
  - `toSession()` maps `provider?.name` → `Provider(name)` only when the name is non-blank;
    absent or blank → null (feeds the graceful-omit path).

### Header
- `ui/navigation/AppViewModel.kt`
  - Add `val providerName: StateFlow<String?>` derived from `sessionManager.session`
    (`map { it?.provider?.name }`, `stateIn` with an initial `null`).
- `ui/navigation/NavGraph.kt`
  - Collect `providerName` and pass it into `AppBar`.
  - `AppBar` renders the title slot as a `Column`: `appbar_title` (`titleMedium`) plus, when the
    name is non-null, a second line (`labelMedium`, `onSurfaceVariant`). Null → single line as today.
  - Keep the log-out action and inset behavior unchanged.

### Final confirmation
- `ui/addservice/AddServiceViewModel.kt`
  - Inject `SessionManager`.
  - Capture `providerName` (from `session.value?.provider?.name`) into `ReviewingSummary` at
    `confirmAmount()`.
  - Add `val providerName: String?` to `AddServicePhase.ReviewingSummary`.
- `ui/addservice/AddServiceSummaryDrawer.kt`
  - Add a `ProviderSection` (heading + name row) at the top of the drawer body, above the patient
    section. Rendered only when `providerName != null` (mirrors the existing
    `patient.plan?.let { … }` null-handling).

### Strings
- `res/values/strings.xml`
  - `addservice_summary_provider_heading` (section heading in the drawer).
  - `addservice_summary_provider_label` if the row uses a label/value pair consistent with the
    other `SummaryField` rows.
  - The header subtitle is the dynamic clinic name — no new static string.

### Docs
- `docs/openapi.yaml`
  - Add the nullable `provider` object (`{ name: string }`) to the login response schema, flagged
    as an app-invented placeholder — same convention as `POST /members/verify` — to be reconciled
    when the server publishes its real shape.

## Testing (test-first, ≥80% on changed code, success + absence paths)

- **`AuthRepository` mapping** — `provider` with a name → `Session.provider.name`; provider absent
  → null; provider present but blank name → null.
- **`AppViewModel`** — `providerName` emits the clinic name when a session with a provider is
  active; emits null when there is no session / no provider.
- **`AddServiceViewModel`** — `confirmAmount()` populates `ReviewingSummary.providerName` from the
  session (present case); populates null when the session has no provider.
- **Existing tests** — update any that assert the old header title shape or the summary drawer's
  field set so they reflect the new subtitle / provider row.

## Out of scope / not doing (YAGNI)

- No provider logo, address, or any field beyond the name.
- No provider selection/switching UI — the provider is fixed by the authenticated session.
- No persistence — provider name lives only in the in-memory session, like everything else.
- No blocking behavior on a missing name (explicitly fail-open).

## Constraints to honor

- No hardcoded dp/colors — design tokens and theme typography only.
- Functions ≤ 50 lines, line length ≤ 120 (detekt / constitution).
- Screens do not apply their own window insets — the header keeps owning them.
- No user-facing free text beyond the server-supplied clinic name itself (it is data, rendered
  verbatim, not an app-authored message).
