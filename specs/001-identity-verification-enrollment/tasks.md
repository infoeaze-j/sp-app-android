---
description: "Task list for Identity Verification & Service Enrollment"
---

# Tasks: Identity Verification & Service Enrollment

**Input**: Design documents from `/specs/001-identity-verification-enrollment/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUDED. The FaceVerify Constitution Principle II makes testing NON-NEGOTIABLE (test-first, layered unit + instrumented, explicit success/denial paths, ≥80% coverage on changed code). Contract and unit tests are therefore required and written before implementation.

**Organization**: Tasks are grouped by user story (US1–US4) to enable independent implementation and testing. Note that the runtime *journey* is sequential (sign in → NFC → face → add service, FR-032); the *build order* still lets each story be implemented and tested independently against fakes/MockWebServer.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1, US2, US3, US4 (setup/foundational/polish carry no story label)
- All paths are relative to repository root. Base package: `com.mediplus.faceverify` under `app/src/main/java/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, dependencies, tooling, and quality gates.

- [X] T001 Configure Gradle version catalog with all Phase-1 dependencies (Compose BOM + Material 3, Navigation Compose, Activity Compose, Hilt, Coroutines/Flow, Retrofit/OkHttp/kotlinx.serialization, CameraX, ML Kit Face Detection + Text Recognition, JMRTD + SCUBA, DataStore) in `gradle/libs.versions.toml`
- [X] T002 Configure `app/build.gradle.kts` — plugins (Kotlin K2, Hilt, kotlinx.serialization), `minSdk 24` / `targetSdk 36` / `compileSdk 36`, JVM target 11, camera/NFC feature declarations
- [X] T003 Create the layered package skeleton (`core/{di,session,nfc,camera,result,ui}`, `data/{remote,local,repository}`, `domain/{model,usecase}`, `ui/{signin,nfcscan,facecheck,addservice,navigation}`) under `app/src/main/java/com/mediplus/faceverify/`
- [X] T004 [P] Configure `detekt`, `ktlint`, and Android Lint as warning-clean build gates in `app/build.gradle.kts` and `config/detekt/detekt.yml`
- [X] T005 [P] Create `FaceVerifyApp.kt` (`@HiltAndroidApp`) and register it in `app/src/main/AndroidManifest.xml` with camera + NFC hardware/permission declarations
- [X] T006 [P] Add LeakCanary (debug only) to `app/build.gradle.kts` and create the test source sets scaffolding under `app/src/test/java/com/mediplus/faceverify/` and `app/src/androidTest/java/com/mediplus/faceverify/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core cross-cutting infrastructure every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T007 [P] Create `AppResult<T>` sealed type (`Success`/`BusinessRejection`/`TransientFailure`/`Timeout`) and `AppError` types in `app/src/main/java/com/mediplus/faceverify/core/result/AppResult.kt` (FR-027)
- [X] T008 [P] Create `Session`, `Operator`, and `SessionState` (`Active`/`Expired`/`Invalidated`/`None`) domain models in `app/src/main/java/com/mediplus/faceverify/domain/model/Session.kt`
- [X] T009 [P] Create `VerifiedIdentity` domain model with `isCurrentlyVerified(window)` and the `JourneyState` machine (NotSignedIn → SignedIn → DocumentScanning → ConsentPending → FaceChecking → ReadyToEnroll → EnrollmentSubmitting) — consent gathered after document-verified and before face capture (FR-028, FR-032) — in `app/src/main/java/com/mediplus/faceverify/domain/model/JourneyState.kt` (FR-024, FR-026, FR-032)
- [X] T010 [P] Unit test the error mapper in `app/src/test/java/com/mediplus/faceverify/core/result/ErrorMapperTest.kt` — asserts every mapped message is non-revealing and contains no identity/biometric data (FR-021, FR-029)
- [X] T011 Implement `ErrorMapper` (`AppError` → non-revealing `UiMessage`) in `app/src/main/java/com/mediplus/faceverify/core/result/ErrorMapper.kt` (depends on T007, T010)
- [X] T012 Implement in-memory `SessionManager` with `session: StateFlow`, `set()`, and `clearAll()` that wipes ALL verification state, in `app/src/main/java/com/mediplus/faceverify/core/session/SessionManager.kt` (FR-004a, Decision 6; depends on T008, T009)
- [X] T013 Create Hilt DI modules for dispatchers and storage in `app/src/main/java/com/mediplus/faceverify/core/di/` (`DispatchersModule.kt`, `StorageModule.kt`) and DataStore for non-sensitive prefs in `app/src/main/java/com/mediplus/faceverify/data/local/PrefsDataStore.kt`
- [X] T014 Create the network Hilt module (Retrofit + OkHttp + kotlinx.serialization) with an auth interceptor that attaches the session token and treats HTTP 401 as `Expired`/`Invalidated`, plus a debug-only redacting logging interceptor, in `app/src/main/java/com/mediplus/faceverify/core/di/NetworkModule.kt` (FR-002, FR-004, FR-029; depends on T012)
- [X] T015 [P] Create the Material 3 theme with color/type/spacing tokens and shared state composables (loading/error/permission-denied) in `app/src/main/java/com/mediplus/faceverify/core/ui/theme/` and `core/ui/components/`, and seed `app/src/main/res/values/strings.xml` with all shared user-facing text (Principle III)
- [X] T016 Create the single-Activity host `MainActivity.kt` and the sequential-journey `NavGraph` with step guards backed by `JourneyState`/`SessionManager` in `app/src/main/java/com/mediplus/faceverify/ui/navigation/` (FR-032; depends on T009, T012, T015)
- [X] T016a Capture the back-office-owned verification-freshness window from the login/session response (`config.verificationWindowSeconds`) into `SessionManager`/a config provider, exposing it to `EvaluateVerifiedIdentityUseCase` and the journey freshness guard; treat an absent value as immediately-stale (fail-safe re-verification), in `app/src/main/java/com/mediplus/faceverify/core/session/` (FR-026; depends on T012, T014)

**Checkpoint**: Foundation ready — user stories can now be implemented.

---

## Phase 3: User Story 1 - Authenticated access to the verification workspace (Priority: P1) 🎯 MVP

**Goal**: Operator signs in against the back office; a valid session unlocks the workspace and is attached to every protected request; missing/expired/invalid sessions block all actions and force re-auth.

**Independent Test**: Sign in with valid credentials → workspace unlocks; invalid credentials → refused with a non-revealing error and no session; expire the session → any protected action forces re-authentication; no network → clear connectivity error, not a false signed-in state.

### Tests for User Story 1 ⚠️ (write first, must FAIL before implementation)

- [X] T017 [P] [US1] Contract test for `POST /auth/login`, `POST /auth/logout`, and 401→session-invalidation against MockWebServer in `app/src/test/java/com/mediplus/faceverify/data/remote/AuthApiContractTest.kt` — includes assertion that the token never appears in logged output (FR-005, FR-029)
- [X] T018 [P] [US1] Unit test `AuthRepository` mapping (success/invalid-creds/lockout/timeout → `AppResult`) in `app/src/test/java/com/mediplus/faceverify/data/repository/AuthRepositoryTest.kt` (FR-003, FR-005, FR-006)
- [X] T019 [P] [US1] Unit test `SignInViewModel` state (idle/loading/error/locked-out/success) and expired-session routing in `app/src/test/java/com/mediplus/faceverify/ui/signin/SignInViewModelTest.kt`

### Implementation for User Story 1

- [X] T020 [P] [US1] Create auth DTOs and `AuthApi` Retrofit interface (`/auth/login`, `/auth/logout`, `/auth/session`) in `app/src/main/java/com/mediplus/faceverify/data/remote/AuthApi.kt`
- [X] T021 [US1] Define `AuthRepository` interface and implement it (maps responses to `AppResult<Session>`, feeds `SessionManager`) in `app/src/main/java/com/mediplus/faceverify/data/repository/AuthRepository.kt` (depends on T020, T012)
- [X] T022 [US1] Wire session-expiry detection: on interceptor 401, transition `SessionState` and `SessionManager.clearAll()` so verification state is discarded and re-auth is forced (FR-004, FR-004a; depends on T014, T021)
- [X] T023 [US1] Implement `SignInScreen` + `SignInViewModel` + UI state (credential entry, loading, non-revealing error, sign-in lockout message) in `app/src/main/java/com/mediplus/faceverify/ui/signin/` with all strings in `strings.xml` (FR-001, FR-005, FR-006; depends on T021)
- [X] T024 [US1] Add the auth entry point to the nav graph and guard all protected destinations on `SessionState.Active` (FR-003; depends on T016, T023)

**Checkpoint**: Sign-in gate is fully functional and independently testable (MVP).

---

## Phase 4: User Story 2 - Verify a person's identity with their NFC document (Priority: P1)

**Goal**: With an active session, read the subject's eMRTD chip on-device (DG1 identity fields, DG2 reference photo where present), validate it against the back office, use the document number as the patient key, and present details for operator confirmation.

**Independent Test**: Scan a valid document → identity fields read and displayed, server `Valid` → document-verified; expired/unsupported/failed-integrity → rejected with a specific reason, not verified; interrupted read → reported with retry, session/prior steps preserved; NFC disabled/unavailable → clear explanation.

### Tests for User Story 2 ⚠️ (write first, must FAIL before implementation)

- [X] T025 [P] [US2] Contract test for `POST /documents/validate` (Valid / Invalid+reason / transient / timeout) against MockWebServer in `app/src/test/java/com/mediplus/faceverify/data/remote/DocumentApiContractTest.kt` (FR-008)
- [X] T026 [P] [US2] Unit test `VerifyDocumentUseCase` (marks document-verified only on server `Valid` + not-expired; surfaces reason on reject) in `app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyDocumentUseCaseTest.kt` (FR-008, FR-011a)
- [X] T027 [P] [US2] Unit test `NfcScanViewModel` states (idle/scanning/interrupted-retry/unavailable/read-success/confirm) in `app/src/test/java/com/mediplus/faceverify/ui/nfcscan/NfcScanViewModelTest.kt` (FR-009, FR-010)
- [X] T028 [P] [US2] Instrumented test for NFC availability/disabled handling in `app/src/androidTest/java/com/mediplus/faceverify/nfc/NfcAvailabilityTest.kt` (FR-010)

### Implementation for User Story 2

- [X] T029 [P] [US2] Implement `NfcReader` (Android `NfcAdapter`/`IsoDep` + JMRTD/SCUBA secure messaging, DG1/DG2 parse, on-device integrity read) and `isAvailable()` in `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcReader.kt` (FR-007, FR-010, FR-011, Decision 3/4)
- [X] T030 [P] [US2] Implement access-key derivation from MRZ via ML Kit Text Recognition with operator-entry fallback in `app/src/main/java/com/mediplus/faceverify/core/nfc/AccessKeyDeriver.kt` (Decision 3)
- [X] T031 [P] [US2] Create document DTOs and `DocumentApi` Retrofit interface (`/documents/validate`) in `app/src/main/java/com/mediplus/faceverify/data/remote/DocumentApi.kt`
- [X] T032 [US2] Define `DocumentRepository` interface and implement `validate(ReadDocument): AppResult<DocumentValidation>` in `app/src/main/java/com/mediplus/faceverify/data/repository/DocumentRepository.kt` (FR-008, FR-011a; depends on T031)
- [X] T033 [US2] Implement `VerifyDocumentUseCase` (read → validate → set `documentVerified` + `documentNumber` patient key) in `app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyDocumentUseCase.kt` (depends on T029, T032, T009)
- [X] T034 [US2] Implement `NfcScanScreen` + `NfcScanViewModel` + UI state (scan prompt, NFC-unavailable/disabled message, interrupted-retry, identity-details confirmation) in `app/src/main/java/com/mediplus/faceverify/ui/nfcscan/` with strings in `strings.xml` (FR-007, FR-009, FR-010; depends on T033)
- [X] T035 [US2] Gate the NFC step in the nav graph behind `SessionState.Active` and advance to the face step only on document-verified (FR-032; depends on T016, T034)

**Checkpoint**: NFC document verification works independently against MockWebServer + a test chip.

---

## Phase 5: User Story 3 - Confirm the person with a live face check (Priority: P1)

**Goal**: After consent, capture a live frame with CameraX (ML Kit framing guidance only), submit to the back office for the authoritative match + liveness decision, enforce the server-owned lockout, and mark the identity face-verified only on pass + liveness + same-subject — discarding the frame immediately.

**Independent Test**: Matching face + liveness pass → verified; non-match/low-confidence → recorded fail, not verified; spoof → liveness rejects regardless of similarity; repeated failures → server lockout blocks further attempts (persists across re-login); poor capture → actionable guidance, no submission; consent withheld → clean halt, no capture, no enroll.

### Tests for User Story 3 ⚠️ (write first, must FAIL before implementation)

- [X] T036 [P] [US3] Contract test for `POST /face/verify` (pass+liveness / no-match / spoof-rejected / locked-out / timeout) against MockWebServer in `app/src/test/java/com/mediplus/faceverify/data/remote/FaceApiContractTest.kt` (FR-013, FR-014, FR-015)
- [X] T037 [P] [US3] Unit test `VerifyFaceUseCase` (consent-gated, lockout-aware, `sameSubject` discrepancy halt, face-verified only on pass+liveness) in `app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyFaceUseCaseTest.kt` (FR-013, FR-015, FR-025, FR-028)
- [X] T038 [P] [US3] Unit test `RecordConsentUseCase` (Withheld → record + clean halt, no enroll) and `FaceLockoutState` mirroring in `app/src/test/java/com/mediplus/faceverify/domain/usecase/ConsentAndLockoutTest.kt` (FR-028, FR-015)
- [X] T039 [P] [US3] Unit test that a captured `TransientFrame` is cleared after `FaceRepository.verify` returns (and on failure/abort) in `app/src/test/java/com/mediplus/faceverify/data/repository/FaceFrameDisposalTest.kt` (FR-017)
- [X] T040 [P] [US3] Instrumented camera-permission + framing-guidance test in `app/src/androidTest/java/com/mediplus/faceverify/camera/FaceCaptureTest.kt` (FR-016)

### Implementation for User Story 3

- [X] T041 [P] [US3] Create `BiometricConsent`/`ConsentStatus`, `VerificationAttempt`, and `FaceLockoutState` domain models in `app/src/main/java/com/mediplus/faceverify/domain/model/FaceModels.kt` (FR-015, FR-017, FR-028)
- [X] T042 [P] [US3] Implement `TransientFrame` (in-memory-only, explicit `clear()`), CameraX controller, and `FaceFramingAnalyzer` (ML Kit, capture-quality only) in `app/src/main/java/com/mediplus/faceverify/core/camera/` (FR-016, FR-017, Decision 2)
- [X] T043 [P] [US3] Create face DTOs and `FaceApi` Retrofit interface (`/face/verify`) in `app/src/main/java/com/mediplus/faceverify/data/remote/FaceApi.kt`
- [X] T044 [US3] Define `FaceRepository` interface and implement `verify(documentNumber, TransientFrame): AppResult<FaceDecision>` that clears the frame after the decision returns/aborts in `app/src/main/java/com/mediplus/faceverify/data/repository/FaceRepository.kt` (FR-012, FR-013, FR-017; depends on T042, T043)
- [X] T045 [US3] Implement `RecordConsentUseCase` and `VerifyFaceUseCase` (consent gate, lockout enforcement, `sameSubject` check, set `faceVerified`) in `app/src/main/java/com/mediplus/faceverify/domain/usecase/` (FR-013–FR-015, FR-025, FR-028; depends on T041, T044, T009)
- [X] T046 [US3] Implement consent capture UI (consent obtained from the patient, not the operator) + `FaceCheckScreen` + `FaceCheckViewModel` + UI state (consent prompt, framing guidance, no-match/spoof messaging, lockout + cooldown, discrepancy halt) in `app/src/main/java/com/mediplus/faceverify/ui/facecheck/` with strings in `strings.xml` (FR-016, FR-025, FR-028, FR-031; depends on T045)
- [X] T047 [US3] Gate the face step behind document-verified + consent-granted + not-locked-out, and advance to enrollment only when `isCurrentlyVerified` (FR-032; depends on T016, T046)

**Checkpoint**: Face verification works independently; frame disposal and lockout are verified.

---

## Phase 6: User Story 4 - Add a service for the verified person (Priority: P2)

**Goal**: For a currently-verified identity (within the freshness window), list back-office-eligible services, submit an idempotent per-visit enrollment, and report success only on confirmation — blocking duplicates, ineligible cases, and never showing false success on timeout.

**Independent Test**: Verified identity + eligible service → submitted, confirmed, outcome shown; unverified/stale identity → blocked with explanation; already-held service → duplicate prevented; back-office rejection → specific reason, no success; timeout mid-submit → not shown as success, safe re-check/retry with no duplicate.

### Tests for User Story 4 ⚠️ (write first, must FAIL before implementation)

- [X] T048 [P] [US4] Contract test for `GET /patients/{documentNumber}/services`, `POST .../enrollments`, and the idempotent re-check `GET` against MockWebServer in `app/src/test/java/com/mediplus/faceverify/data/remote/EnrollmentApiContractTest.kt` (FR-020, FR-022, FR-023)
- [X] T049 [P] [US4] Unit test `EvaluateVerifiedIdentityUseCase` (composite + server-supplied freshness window → blocks stale/unverified; absent window → treated as stale) in `app/src/test/java/com/mediplus/faceverify/domain/usecase/EvaluateVerifiedIdentityUseCaseTest.kt` (FR-018, FR-024, FR-026)
- [X] T050 [P] [US4] Unit test `AddServiceUseCase` idempotency and uncertain-outcome handling (timeout never shows success; retry reuses idempotency key → no duplicate) in `app/src/test/java/com/mediplus/faceverify/domain/usecase/AddServiceUseCaseTest.kt` (FR-020, FR-022)
- [X] T051 [P] [US4] Unit test `AddServiceViewModel` states (loading services, eligible/duplicate, confirmed/rejected/uncertain) in `app/src/test/java/com/mediplus/faceverify/ui/addservice/AddServiceViewModelTest.kt` (FR-019, FR-021)

### Implementation for User Story 4

- [X] T052 [P] [US4] Create `Service` and `Enrollment`/`EnrollmentStatus` domain models (per-visit/transaction scope, not a standing subscription) in `app/src/main/java/com/mediplus/faceverify/domain/model/EnrollmentModels.kt` (FR-019, FR-020, FR-022, FR-023, FR-023a)
- [X] T053 [P] [US4] Create enrollment DTOs and `EnrollmentApi` Retrofit interface (services list, enroll, re-check) in `app/src/main/java/com/mediplus/faceverify/data/remote/EnrollmentApi.kt`
- [X] T054 [US4] Define `EnrollmentRepository` interface and implement `listServices` / `enroll` (idempotency key) / `recheck` in `app/src/main/java/com/mediplus/faceverify/data/repository/EnrollmentRepository.kt` (FR-020, FR-022, FR-023; depends on T053)
- [X] T055 [US4] Implement `ListEligibleServicesUseCase`, `EvaluateVerifiedIdentityUseCase` (freshness window injected from the server-supplied config via T016a), and `AddServiceUseCase` (precondition-gated, idempotent) in `app/src/main/java/com/mediplus/faceverify/domain/usecase/` (FR-018, FR-019, FR-024, FR-026; depends on T052, T054, T009, T016a)
- [X] T056 [US4] Implement `AddServiceScreen` + `AddServiceViewModel` + UI state (service picker, duplicate/ineligible block, confirmed/rejected/uncertain with safe re-check) in `app/src/main/java/com/mediplus/faceverify/ui/addservice/` with strings in `strings.xml` (FR-019, FR-021, FR-022; depends on T055)
- [X] T057 [US4] Gate the enrollment step behind `isCurrentlyVerified(window)` in the nav graph, showing which requirement is outstanding when blocked (FR-018, FR-024, FR-032; depends on T016, T056)

**Checkpoint**: All four user stories are independently functional; the full sequential journey is wired.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Constitution compliance, hardening, and validation across all stories.

- [X] T058 [P] Add a logging/redaction guard test that asserts no session token, `documentNumber`, or biometric data appears in any logged output across the suite in `app/src/test/java/com/mediplus/faceverify/core/LoggingRedactionTest.kt` (FR-029, FR-030, SC-005)
- [X] T059 [P] Add an end-to-end audit-trail test verifying sign-in, document, face, and enrollment outcomes record metadata only (never raw biometrics) in `app/src/test/java/com/mediplus/faceverify/AuditTrailTest.kt` (FR-017, FR-030)
- [X] T060 [P] Enable LeakCanary on the camera/NFC verification screens and add a bounded-memory/leak check note per Principle IV
- [X] T061 [P] Accessibility pass — 48dp targets, content descriptions, dynamic font, TalkBack labels across all screens (Principle III)
- [X] T062 [P] Measure and record cold-start (<2s), per-attempt end-to-end latency, and the full sign-in→enroll journey time (<5 min for a cooperative subject, SC-001) on the designated reference device (Google Pixel 6a, or an equivalent ~2022 mid-range device — record the actual device used) per Principle IV
- [X] T063 Configure Android backup/data-extraction rules to exclude all sensitive/session state in `app/src/main/res/xml/` and the manifest (Decision 6, FR-030)
- [X] T064 Run `quickstart.md` validation end-to-end and confirm ≥80% coverage on changed code with success + denial paths (Principle II)
- [X] T065 Configure CI to run build + Android Lint + `detekt`/`ktlint` + the full unit/instrumented suite as required status checks blocking merge, enforcing ≥80% coverage on changed code, in `.github/workflows/` (Constitution II & Quality Standards; depends on T004, T064)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Stories (Phase 3–6)**: All depend on Foundational. US1 (P1) is the MVP. US2 and US3 (both P1) and US4 (P2) can each be built and tested independently against fakes/MockWebServer once the foundation is ready; the runtime journey chains them via the nav guards.
- **Polish (Phase 7)**: Depends on the targeted user stories being complete.

### User Story Dependencies

- **US1 (P1)**: Foundational only. Establishes the session everything else attaches to.
- **US2 (P1)**: Foundational only. Independently testable; runtime-gated behind US1's active session.
- **US3 (P1)**: Foundational only. Independently testable; runtime-gated behind US2's document-verified.
- **US4 (P2)**: Foundational only. Independently testable; runtime-gated behind US3's face-verified/`isCurrentlyVerified`.
- Shared composite (`VerifiedIdentity`) and `JourneyState` live in Foundational (T009) so stories don't depend on each other's internals.

### Within Each User Story

- Tests are written first and MUST FAIL before implementation.
- DTOs/API → Repository → Use case → ViewModel/Screen → nav gating.

### Parallel Opportunities

- Setup: T004, T005, T006 in parallel.
- Foundational: T007, T008, T009, T010, T015 in parallel; then T011–T014, T016.
- Within each story, all `[P]` test tasks run together, and `[P]` model/API/core tasks (different files) run together.
- With capacity, US1–US4 can be developed in parallel by different developers once Foundational completes.

---

## Parallel Example: User Story 3

```bash
# Tests first (all fail), in parallel:
Task: "Contract test POST /face/verify in app/src/test/.../FaceApiContractTest.kt"
Task: "Unit test VerifyFaceUseCase in app/src/test/.../VerifyFaceUseCaseTest.kt"
Task: "Unit test consent + lockout in app/src/test/.../ConsentAndLockoutTest.kt"
Task: "Unit test frame disposal in app/src/test/.../FaceFrameDisposalTest.kt"

# Then independent implementation files, in parallel:
Task: "Face models in domain/model/FaceModels.kt"
Task: "CameraX + FaceFramingAnalyzer in core/camera/"
Task: "FaceApi + DTOs in data/remote/FaceApi.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1 (Setup) and Phase 2 (Foundational — blocks everything).
2. Complete Phase 3 (US1 sign-in gate).
3. **STOP and VALIDATE**: independently test sign-in, invalid-creds, session-expiry, and no-network paths.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → the security boundary (MVP).
3. US2 → NFC document verification.
4. US3 → live face check (completes a verified identity).
5. US4 → add a service (the business outcome).
6. Polish → privacy/perf/accessibility/audit hardening.

### Parallel Team Strategy

After Foundational: Developer A → US1, Developer B → US2, Developer C → US3, Developer D → US4, each against MockWebServer/fakes; integrate via the shared nav guards and `SessionManager`.

---

## Notes

- `[P]` = different files, no dependencies on incomplete tasks.
- Every task follows `- [ ] Txxx [P?] [Story?] Description + file path`.
- Verify each test fails before implementing (Constitution Principle II).
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
- Biometric frames are transient-in-memory-only and never persisted (FR-017); no identity/biometric data in logs or user-facing errors (FR-029).
