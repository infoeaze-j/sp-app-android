# Contract: Client Interfaces (Repositories & Journey Use Cases)

**Feature**: 001-identity-verification-enrollment

The stable, testable seams inside the app. These interfaces are **independent of the back-office
wire shape** — they are the boundary unit tests (MockWebServer for repos, fakes for use cases) target.
Every method returns `AppResult<T>` (see `data-model.md`) so success / business-rejection / transient /
timeout are handled explicitly (FR-027).

## Repositories (`data/repository`)

```kotlin
interface AuthRepository {
    suspend fun signIn(identifier: String, secret: String): AppResult<Session>
    suspend fun signOut(): AppResult<Unit>          // clears session-bound state (FR-004a)
    fun sessionState(): StateFlow<SessionState>     // Active/Expired/Invalidated/None
}

interface DocumentRepository {
    // On-device chip read happens in core/nfc; this validates + resolves the patient.
    suspend fun validate(read: ReadDocument): AppResult<DocumentValidation>   // FR-008, FR-011a
}

interface FaceRepository {
    // image is transient: caller passes an in-memory frame and MUST clear it after this returns (FR-017).
    suspend fun verify(documentNumber: String, frame: TransientFrame): AppResult<FaceDecision> // FR-013..FR-015
}

interface EnrollmentRepository {
    suspend fun listServices(documentNumber: String): AppResult<List<Service>>              // FR-023
    suspend fun enroll(documentNumber: String, serviceId: String, idempotencyKey: String):
        AppResult<Enrollment>                                                                // FR-020, FR-022
    suspend fun recheck(documentNumber: String, idempotencyKey: String): AppResult<Enrollment?> // FR-022
}
```

## Core services (`core/`)

```kotlin
interface SessionManager {                          // in-memory, session-bound (Decision 6)
    val session: StateFlow<Session?>
    fun set(session: Session)
    fun clearAll()                                  // drops session + ALL verification state (FR-004a)
}

interface NfcReader {                               // core/nfc — JMRTD/SCUBA
    suspend fun read(accessKey: DocAccessKey): AppResult<ReadDocument>   // DG1/DG2 + integrity (FR-007, FR-011)
    fun isAvailable(): NfcAvailability              // supported/disabled/unavailable (FR-010)
}

interface FaceFramingAnalyzer {                     // core/camera — ML Kit, capture-quality ONLY (FR-016)
    fun evaluate(imageProxy: ImageProxy): FramingGuidance   // one-face/lighting/pose; no matching
}

interface ErrorMapper {                             // core/result
    fun toUserMessage(error: AppError): UiMessage   // clear + NON-revealing (FR-021, FR-029)
}
```

## Journey use cases (`domain/usecase`)

Encapsulate the enforced sequential rules (FR-032) so screen-gating is unit-testable without a device.

```kotlin
class RecordConsentUseCase          // FR-028: Withheld → clean halt, record, no enroll
class VerifyDocumentUseCase         // FR-007, FR-008: read → validate → mark document-verified
class VerifyFaceUseCase             // FR-013..FR-015, FR-025: consent-gated, lockout-aware, sameSubject
class EvaluateVerifiedIdentityUseCase  // FR-024, FR-026: composite + freshness window
class ListEligibleServicesUseCase   // FR-023, FR-019: eligibility + duplicate flags
class AddServiceUseCase             // FR-018, FR-020, FR-022: precondition-gated, idempotent
class CanAdvanceUseCase             // FR-032: is next step reachable? which requirement is outstanding?
```

## Invariants the interfaces guarantee (tested)

- No repository call is dispatched without `SessionState.Active` (FR-003) — enforced by an
  auth interceptor + `CanAdvanceUseCase`.
- `FaceRepository.verify` is unreachable unless consent `Granted` and not locked out (FR-028, FR-015).
- `AddServiceUseCase` refuses unless `EvaluateVerifiedIdentityUseCase` reports currently-verified
  (FR-018, FR-024, FR-026).
- `SessionManager.clearAll()` on any non-`Active` transition wipes verification state (FR-004a).
- No interface exposes or returns raw biometric bytes for storage; `TransientFrame` is memory-only
  and cleared by the caller (FR-017).
- `ErrorMapper` output never contains identity/biometric data (FR-029) — asserted in tests.
