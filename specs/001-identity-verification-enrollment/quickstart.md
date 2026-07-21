# Quickstart & Validation Guide: Identity Verification & Service Enrollment

**Feature**: 001-identity-verification-enrollment | **Date**: 2026-07-20

How to build, run, and validate that the feature works end-to-end. This is a **run/validation
guide** — implementation details live in `data-model.md`, `contracts/`, and (after `/speckit-tasks`)
`tasks.md`. See [plan.md](./plan.md) for the technical approach and [research.md](./research.md) for
decisions.

## Prerequisites

- **Android Studio** (current stable) with Android SDK for `compileSdk 36`; JDK 11+.
- A **physical Android device** (`minSdk 24`) with **camera + NFC** — face capture and member card
  reading cannot be validated on an emulator. NFC must be enabled in device settings.
- A **member card** carrying its number in an NDEF text record (for full end-to-end validation). The
  manual-entry path and the debug fake reader both work without one.
- Back-office endpoints for the four APIs, **or** the bundled MockWebServer fixtures (below) for CI
  and device-free validation.

## Build & run

```bash
# From repo root (Windows PowerShell shown; bash equivalent works too)
./gradlew.bat assembleDebug            # build the debug APK
./gradlew.bat installDebug             # install on a connected device
# then launch FaceVerify from the launcher, or:
./gradlew.bat :app:test                # JVM unit tests (viewmodels, usecases, mappers, repos)
./gradlew.bat :app:connectedDebugAndroidTest   # instrumented tests (camera/permission/NFC/nav) — needs a device
./gradlew.bat lint detekt ktlintCheck  # quality gates (must be clean — Constitution I)
```

> Point the app at MockWebServer (device-free API validation) via the debug build's configurable
> base URL; use the real back office for on-device end-to-end runs.

## Validation scenarios

Each scenario maps to a user story / acceptance scenario and to contract tests. Run the automated
tests first; then walk the on-device flow for camera/NFC coverage.

### Scenario A — Authenticated access (US1, FR-001–FR-006)
1. Launch app → sign in with **valid** credentials → **Expect**: workspace unlocks (session Active).
2. Sign in with **invalid** credentials → **Expect**: refused with a clear, non-revealing message; no
   session. (AS-2)
3. Force session expiry (fixture returns 401 on a protected call) → attempt any verification action →
   **Expect**: blocked + routed to sign-in; **all prior verification state discarded** (FR-004a). (AS-3)
4. Disable connectivity → sign in → **Expect**: connectivity explained; app not shown as signed in. (AS-4)

### Scenario B — Member card verification (US2, FR-007–FR-011a)
1. With an Active session, tap a **valid** member card → **Expect**: card number read, verified with
   the back office, and the returned member details shown for confirmation. (AS-1)
2. Tap a card carrying **no readable number** → **Expect**: "card couldn't be read" plus the
   **manual-entry** keypad; typing the printed number verifies identically. (AS-2)
3. Verify a card number the back office **rejects** → **Expect**: rejected with a **specific** reason;
   not member-verified. (AS-3, FR-008)
4. Move the card away mid-read → **Expect**: interruption reported; retry works; session/progress
   intact. (FR-009)
5. Toggle NFC **off** in settings → reach this step → **Expect**: clear explanation **and** the
   manual-entry option — no dead end. (AS-4, FR-010)
6. Enter a malformed number (fewer than 7 digits, or letters) → **Expect**: rejected on-device; the
   back office is never called. (FR-011a)

### Scenario C — Live face check (US3, FR-012–FR-017, FR-028)
1. **Consent prompt appears before any capture**; decline → **Expect**: "consent withheld" recorded,
   journey halts cleanly, no capture, no enrollment. (FR-028)
2. Grant consent; capture a **matching** live face → **Expect**: PASS + liveness PASS → identity
   face-verified. (AS-1)
3. Capture a **non-matching** face → **Expect**: failed attempt recorded; not verified. (AS-2)
4. Present a **printed photo / screen replay** → **Expect**: liveness rejects it. (AS-4, FR-014)
5. Exceed the attempt limit → **Expect**: attempts blocked with cooldown; **sign out and back in →
   still locked out** (server-owned, FR-015). (AS-3)
6. Poor capture (no face / two faces / low light) → **Expect**: actionable guidance, no submission.
   (AS-5, FR-016)
7. After every attempt → **Expect**: no face image written to disk; audit shows outcome/metadata
   only. (FR-017)

### Scenario D — Add a service (US4, FR-018–FR-023a)
1. With a currently-verified identity, add an **eligible** service → **Expect**: submitted,
   back-office-confirmed, outcome shown. (AS-1)
2. Attempt to add a service for an **unverified/stale** identity → **Expect**: blocked with
   explanation. (AS-2, FR-018/FR-026)
3. Add a service the patient **already holds** → **Expect**: duplicate prevented + explained. (AS-3,
   FR-019)
4. Fixture returns a **business rejection** (ineligible/conflict) → **Expect**: specific
   non-technical reason; **not** reported as success. (AS-4, FR-021)
5. Fixture **times out** mid-submit, then retry → **Expect**: no false success; retry reuses the
   idempotency key → **single** enrollment, no duplicate. (AS-5, FR-022, SC-003)

## Success-criteria checks (from spec)

| Check | How to validate |
|-------|-----------------|
| SC-002 (no enroll without current verification) | Scenario D2 + `AddServiceUseCase` unit tests. |
| SC-003 (failures never false-success; retries no duplicate) | Scenario D5 + enrollment idempotency contract test. |
| SC-004 (spoof rejection / genuine pass rates) | Scenario C4 + a liveness test set; genuine-pass sampling. |
| SC-005 (no sensitive data in logs/messages) | Grep logs during all scenarios; `ErrorMapper` tests (FR-029). |
| SC-006 (every failure has reason + next step) | Walk every failure branch in A–D; assert message + action. |
| SC-007 (duplicate prevention 100%) | Scenario D3 + duplicate contract test. |

## Quality gates (Constitution I–IV)

- `test`, `connectedDebugAndroidTest`, `lint`, `detekt`, `ktlintCheck` all green.
- LeakCanary (debug) reports **clean** on the face-check and NFC screens (Principle IV).
- Cold start → interactive measured **< 2s** on the reference device; per-attempt latency recorded.
- Coverage on changed code **≥ 80%**, with explicit success **and** denial-path tests (Principle II).
