# Design: In-app Fake Back Office (dev-only)

**Date:** 2026-07-20
**Status:** Approved, pending implementation plan
**Feature area:** developer tooling for `001-identity-verification-enrollment`

## Goal

Let a developer drive the FaceVerify Compose UI through **every** back-office
outcome — happy path and each failure — without a running backend, without
network configuration, on either an emulator or a physical device. This unblocks
frontend iteration when the real back office is unavailable (the current
`BuildConfig.BASE_URL` is a non-existent placeholder).

## Scope

- **In scope:** faking the 8 back-office endpoints behind the four repository
  interfaces, plus a debug-only UI to choose which outcome each returns.
- **Out of scope:** the on-device NFC read and camera face-capture. Those run
  *before* the network calls and stay real. A physical device is still required
  to reach the document and face steps; the fake only replaces the back-office
  response once those steps produce their inputs.
- **Release builds:** contain none of this code and behave exactly as today.

## Decisions (locked during brainstorming)

1. **In-app fake**, not a standalone mock server (no external process, no
   `10.0.2.2` / LAN / cleartext-HTTP setup).
2. **Fake at the repository interface** — the codebase's stated stable seam.
   Repos return `AppResult<DomainType>`, so fakes build domain objects directly;
   no JSON, no Retrofit involvement.
3. **Scenario control via a debug dev screen**, persisted across launches,
   defaulting to the happy path.
4. **Device sensors stay real** — only the endpoints are faked.
5. **Master "Fake backend" toggle kept** — a debug build can still hit a real
   backend by flipping one switch (defaults to ON, since no real backend URL is
   configured today).
6. **Dev screen reached via a second launcher icon** ("FaceVerify Dev") in the
   debug build — fully isolated in `src/debug`, no changes to `MainActivity` or
   `NavGraph`.

## Architecture

### The seam

The four repository interfaces are the swap point:

| Interface | Method(s) | Returns |
|---|---|---|
| `AuthRepository` | `signIn`, `signOut`, `sessionState` | `AppResult<Session>` / `AppResult<Unit>` / `StateFlow<SessionState>` |
| `DocumentRepository` | `validate` | `AppResult<DocumentValidation>` |
| `FaceRepository` | `verify` | `AppResult<FaceDecision>` |
| `EnrollmentRepository` | `listServices`, `enroll`, `recheck` | `AppResult<List<Service>>` / `AppResult<Enrollment>` / `AppResult<Enrollment?>` |

Real `*Impl` classes and the entire Retrofit / OkHttp / serialization stack are
left untouched.

### DI mechanism — source-set-specific `RepositoryModule`

Hilt forbids two `@Binds` for the same interface, so the swap is done per build
variant, not with a runtime-only module:

- **Delete** `src/main/.../core/di/RepositoryModule.kt`.
- **Add** `src/release/.../core/di/RepositoryModule.kt` — binds the real `*Impl`s
  (identical to today's bindings).
- **Add** `src/debug/.../core/di/RepositoryModule.kt` — binds **switching**
  repositories and provides the `DevSettingsStore`.

A `SwitchingXRepository(real, fake, devStore)` delegates to `fake` when the
master toggle is on, otherwise to `real`. Both `real` (`XRepositoryImpl`) and
`fake` (`FakeXRepository`) are `@Inject`-constructable, so Hilt supplies both.

```
UI → ViewModel → XRepository (interface)
                     │
        debug: SwitchingXRepository ──► FakeXRepository   (toggle ON)
                     │             └──► XRepositoryImpl    (toggle OFF → real Retrofit)
        release:                      XRepositoryImpl
```

### Components

All new code lives in `src/debug/java/com/mediplus/faceverify/dev/`, except the
two variant `RepositoryModule`s (in `core/di`).

- **`DevSettingsStore`** — interface + DataStore-Preferences-backed impl
  (`androidx.datastore.preferences` is already a dependency;
  `PrefsDataStore`/`StorageModule` show the existing pattern). Holds:
  - `fakeEnabled: Boolean` (default `true`)
  - one scenario enum per endpoint group (default = the success scenario)
  - `latencyMillis: Long` (simulate loading spinners; default e.g. 500)
  - `verificationWindowSeconds: Long` for the fake session (default e.g. 300)

  All exposed as `Flow`s so the dev screen and the fakes read one consistent
  source of truth.
- **`FakeData`** — canned patient ("Jane Doe" + identity fields), a fixed
  service list, a deterministic token/`Session`.
- **`FakeScenarios`** — the scenario enums (see below).
- **`FakeAuthRepository` / `FakeDocumentRepository` / `FakeFaceRepository` /
  `FakeEnrollmentRepository`** — each reads the current scenario from the store,
  `delay(latencyMillis)`, and returns the matching `AppResult`.
  - `FakeAuthRepository.signIn` calls `SessionManager.set(...)` **and**
    `setVerificationWindow(...)` exactly like `AuthRepositoryImpl`, so downstream
    verification-freshness logic works. `signOut` calls `clearAll()`.
  - `FakeEnrollmentRepository` keeps an in-memory `idempotencyKey → Enrollment`
    map so `recheck` is consistent after a simulated `Timeout` on `enroll`
    (mirrors FR-022).
- **`DevSettingsActivity` + `DevSettingsScreen` + `DevSettingsViewModel`** — a
  second launcher activity declared in `src/debug/AndroidManifest.xml`
  (label "FaceVerify Dev"). Contains: master toggle, per-group scenario
  dropdowns, latency presets, and a **"Force session expired"** button that
  calls `SessionManager.markSessionExpired()` — the existing `NavGraph` guard
  then bounces the app to sign-in, exercising FR-004/FR-004a.

### Scenarios per group

Each maps to an existing `AppResult` variant / `BusinessCode`, so no new UI
handling is required.

- **Auth (`signIn`):**
  `Success` · `InvalidCredentials` (→ `BusinessRejection(INVALID_CREDENTIALS)`) ·
  `AccountLocked` (→ `ACCOUNT_LOCKED`) · `Throttled` (→ `ACCOUNT_LOCKED`) ·
  `ServerError` (→ `TransientFailure(SERVER_ERROR)`)
- **Document (`validate`):**
  `Success` (VALID + patient resolved) · `Invalid` (INVALID + reason, still a
  `Success<DocumentValidation>` with `authenticity = INVALID`) ·
  `PatientNotFound` (→ `PATIENT_NOT_FOUND`) · `ServerError`
- **Face (`verify`):**
  `Pass` · `FailNoMatch` · `FailLiveness` · `SubjectMismatch` · `LockedOut`
  (populates `FaceLockoutState`) · `ServerError`
- **Enrollment:**
  - `listServices`: `Success(N)` · `Empty` · `PatientNotFound` · `ServerError`
  - `enroll`: `Confirmed` · `Duplicate` (→ `DUPLICATE_SERVICE`) · `Ineligible`
    (→ `SERVICE_INELIGIBLE`) · `Timeout` (→ `AppResult.Timeout`, uncertain) ·
    `ServerError`
  - `recheck`: resolves the `Timeout` case using the in-memory idempotency map.
- **Session:** the "Force session expired" action (immediate); optional
  per-endpoint 401-style expiry can be added later if needed.

## Data flow

1. Developer opens **FaceVerify Dev**, sets scenarios, returns to the app.
2. UI action → ViewModel → repository interface → (debug) `SwitchingX` →
   `FakeX`.
3. `FakeX` reads the persisted scenario, waits `latencyMillis`, returns the
   corresponding `AppResult`.
4. Existing ViewModel/UI code renders the state — unchanged.

## Error handling & isolation

- Fakes only ever produce the `AppResult`/`AppError`/`BusinessCode` values the UI
  already handles. No new error surfaces.
- Every dev artefact is `src/debug`-only, plus the `src/release`
  `RepositoryModule`. Release builds compile and behave exactly as today; the
  second launcher icon and all fake code are absent from release.

## Testing

- Fake repos are pure (persisted scenario in → `AppResult` out) and unit-testable
  on the JVM.
- **First checkpoint (build gate):** the source-set `RepositoryModule` swap must
  not break existing Hilt/repository tests. `testDebugUnitTest` compiles against
  the debug variant, so the debug `RepositoryModule` (and its `DevSettingsStore`
  dependency) must resolve cleanly. Verify with `assembleDebug` + the JVM unit
  suite before building any dev UI.
- Confirm `assembleRelease` still builds and contains no `dev/` code.

## Alternative considered

A debug **OkHttp interceptor** returning canned JSON — exercises the real
wire→domain mapping and leaves repositories/DI untouched, at the cost of writing
JSON fixtures and a small multibinding seam in `NetworkModule`. Repository-level
faking was chosen for simplicity (domain objects, no JSON). Recorded here in case
the DI-variant swap proves heavier than desired during implementation.

## Build-toolchain note

AGP 9 / Gradle 9.4 / Kotlin 2.3.10 constraints apply (see
`build-toolchain-constraints` memory): no `kotlin.android` plugin, Hilt ≥ 2.60,
compileSdk 37, Android Lint is the in-build static-analysis gate
(`abortOnError = true`). New debug code must pass Lint. Build with the Android
Studio JBR (`JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`).
