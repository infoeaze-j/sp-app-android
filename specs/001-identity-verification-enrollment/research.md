# Phase 0 Research: Identity Verification & Service Enrollment

**Feature**: 001-identity-verification-enrollment | **Date**: 2026-07-20

This document resolves the unknowns from the spec's Technical Context and records the
technology decisions that shape Phase 1. Every decision is checked against the FaceVerify
Constitution (Principles I–IV). Format per decision: **Decision / Rationale / Alternatives considered**.

## Unknowns extracted from spec

1. Numeric business thresholds (match-confidence %, face retry/lockout counts, sign-in lockout, verification-freshness window) — spec marks these as back-office-owned config.
2. Where the face match + liveness decision runs (on-device model vs back office).
3. How an NFC eMRTD chip is unlocked and read on Android, including obtaining the access key.
4. How document authenticity/validity is established (on-device vs offloaded).
5. UI framework and architecture for a greenfield Kotlin/Android app under this constitution.
6. How verification state is kept session-bound and biometrics kept transient (privacy).
7. Failure/timeout handling and idempotency for enrollment (no false success, no duplicates).

---

## Decision 1 — Business thresholds are server-owned; the app never hardcodes them

**Decision**: Treat match-confidence threshold, face retry/lockout counts, sign-in lockout policy,
and the verification-freshness window as values the **back office** owns. The app receives the
governing rule/outcome in API responses (e.g., a lockout flag + remaining-attempts + cooldown, a
pass/fail decision already evaluated against the server threshold) and enforces it client-side.
Any value the client legitimately needs locally (e.g., freshness window for UI countdown) is
delivered as remote configuration, not a compile-time constant.

**Rationale**: The spec (Assumptions, FR-015, FR-018/FR-026) is explicit that the back office is
the source of truth and that lockout must survive re-login (so it cannot live only on the client).
Hardcoding thresholds would create client/server drift and let a reinstall reset a lockout —
directly violating FR-015. Server ownership also keeps these as business/config decisions,
matching the spec's "Remaining details … non-blocking" note.

**Alternatives considered**:
- *Client-side thresholds/config file* — rejected: violates FR-015 (lockout resettable by
  reinstall/re-login) and creates drift.
- *Blocking the plan on final numbers* — rejected: the spec explicitly defers them as non-blocking
  config; the architecture is threshold-agnostic, so numbers can be finalized independently.

---

## Decision 2 — Face match + liveness decision runs server-side; on-device ML is capture-quality guidance only

**Decision**: The app captures a live frame with CameraX and submits it to the face-verification
API, which returns the authoritative match + liveness decision (evaluated against the server
threshold). On-device ML Kit Face Detection is used **only** to guide capture quality (exactly one
face present, reasonable size/pose, adequate lighting) per FR-016, and to avoid submitting an
unusable image. The captured frame is held transiently in memory for the single submission and
discarded immediately after the decision returns or on failure/abort (FR-017).

**Rationale**: The spec's core framing is that the app "hooks into" a face-verification API and that
the back office owns thresholds and liveness (FR-013–FR-015, Assumptions). Server-side decisioning
centralizes the security-critical logic, keeps the trusted reference photo server-side, and avoids
shipping/versioning a match model on-device. Constitution Principle IV (off-main-thread, bounded
memory) is satisfied by running ML Kit off the UI thread and closing every `ImageProxy` promptly.

**Alternatives considered**:
- *On-device matching/liveness model* — rejected: contradicts the spec's API-integration model,
  duplicates the server's authority, complicates APK size/perf (Principle IV), and risks
  client/server threshold drift.
- *No on-device face detection at all* — rejected: FR-016 requires actionable guidance for
  no-face/multiple-faces/poor-light before submission; ML Kit provides this cheaply.

---

## Decision 3 — On-device eMRTD reading via Android NFC + JMRTD; access key derived from MRZ/CAN

> **SUPERSEDED (2026-07-21)** by member card verification: the eMRTD/JMRTD path was removed in
> favour of reading a card number from an NDEF text record. See `contracts/member-card-api.md`.
> Retained as the record of the original decision.

**Decision**: Read the NFC-enabled identity document on-device using Android's `NfcAdapter`
(ISO 14443, `IsoDep`) with **JMRTD** (+ **SCUBA** provider) to establish secure messaging
(BAC or PACE) and parse standard datagroups (DG1 = MRZ/identity fields, DG2 = reference face image
where present). The chip access key is derived from the document's MRZ (document number + date of
birth + expiry date) or a CAN, obtained via an on-device **MRZ read (ML Kit Text Recognition of the
printed zone)** or, as a fallback, operator entry. The document's unique **document number**
(from DG1/MRZ) is the patient key sent to the back office (FR-011a).

**Rationale**: eMRTD chips only unlock with the MRZ/CAN-derived key — reading the chip on-device is
the only way to obtain DG2's reference photo (FR-011) and the identity fields (FR-007) without
transmitting the raw chip session. JMRTD is the de-facto Android/Java library for ICAO 9303 eMRTD
access. Deriving the access key from an OCR'd MRZ keeps the operator flow fast while remaining a
technical sub-step of "scan NFC," not a new business requirement. The document number naturally
serves as the patient lookup key the spec already mandates.

**Alternatives considered**:
- *Send raw NFC APDUs to the server to read the chip* — rejected: the phone holds the RF link to the
  chip; secure messaging must terminate on-device. Impractical and higher-risk.
- *Manual-only key entry* — kept as fallback but not primary: slower and error-prone; MRZ OCR is the
  smoother default. (Some national eIDs use CAN instead of full MRZ — supported by the same path.)
- *Assume all documents expose a photo* — rejected: DG2 may be absent; FR-011 is conditional
  ("where the document provides a reference photo"), so the server reference photo is the fallback.

---

## Decision 4 — Document authenticity/validity: on-device integrity read, back office is source of truth

> **SUPERSEDED (2026-07-21)**: a member card carries no expiry and no security object, so there is
> no on-device pre-check at all — membership validity is entirely server-owned (FR-008).

**Decision**: On-device, JMRTD verifies chip-level integrity where feasible (datagroup hashes vs the
Document Security Object) and reads expiry from DG1. The **authoritative** validity/authenticity
verdict — including passive authentication against trusted CSCA/CVCA material and any
back-office-specific rules — is delegated to the back-office document-validation API (spec
Assumptions: "back office … exposes APIs for … NFC/document validation"). The app submits the read
document data (identity fields, SOD, and datagroup hashes/EF metadata as the API defines) and
enforces the returned verdict; it marks the document "verified" only on a passing server result
(FR-008).

**Rationale**: Trust anchors (CSCA certificates) and revocation are operational/PKI concerns best
centralized server-side; the app shouldn't ship and rotate a country-signing trust store. On-device
integrity checks give fast local feedback and reject obviously malformed reads before a round trip,
while the server holds the final say — consistent with "back office is the source of truth"
(Assumptions) and FR-008's "specific reason" on rejection.

**Alternatives considered**:
- *Full on-device passive authentication with a bundled CSCA store* — rejected: trust-store rotation
  and revocation are a maintenance burden and a drift risk; contradicts server-as-source-of-truth.
- *Skip on-device integrity entirely* — rejected: cheap local hash checks improve UX (fast reject of
  corrupt reads) and reduce needless server calls.

---

## Decision 5 — UI/architecture: Jetpack Compose + Material 3, MVVM + UDF, Hilt, Coroutines/Flow

**Decision**: Build the UI with Jetpack Compose + Material 3 (single themed design system:
color/type/spacing tokens), single-Activity + Navigation Compose, MVVM with unidirectional data
flow (immutable UI state exposed as `StateFlow`), Hilt for DI, and Kotlin Coroutines/Flow for async.
Networking: Retrofit + OkHttp + kotlinx.serialization. Camera: CameraX. Local prefs: DataStore.

**Rationale**: Greenfield project, so the modern first-party stack maximizes maintainability
(Principle I) and directly enables the constitution's design-system, string-resource, and
every-state requirements (Principle III — Compose makes explicit loading/success/empty/error/
permission-denied states natural). MVVM + UDF keeps verification/journey logic in unit-testable
ViewModels/use cases without a device (Principle II). Coroutines + CameraX keep inference/IO off the
main thread (Principle IV). All choices are AndroidX/first-party, satisfying the dependency-
justification rule.

**Alternatives considered**:
- *Android Views + XML (current AppCompat/Material scaffold)* — rejected for new UI: more
  boilerplate for exhaustive state handling and theming; Compose better serves Principle III.
  (Material components dependency remains only as needed for interop.)
- *No DI / manual wiring* — rejected: Hilt improves testability (swap fakes) and lifecycle-scoping,
  reducing leak risk (Principle IV).

---

## Decision 6 — Session-bound state in memory; biometrics never persisted

**Decision**: Keep the session token and all verification state (document-verified, face-verified,
verified-identity, freshness timestamp) in an in-memory `SessionManager` tied to the app process /
authenticated session. On any session loss (expiry, invalidation, sign-out), clear all verification
state so the patient must be fully re-verified after re-login (FR-004a). Captured face frames live
only in memory for the single submission and are cleared immediately after the decision or on
failure/abort; nothing biometric is written to disk, DataStore, logs, or backups (FR-017, FR-029,
FR-030). The Android backup/data-extraction rules exclude any sensitive state.

**Rationale**: Directly implements the spec's strongest privacy constraints and the "verification is
strictly session-bound" clarification. In-memory-only state makes session-scoped invalidation
trivial and eliminates a class of at-rest data risks — aligning with the constitution's
security/privacy constraints.

**Alternatives considered**:
- *Persist verification results across sessions* — rejected: violates FR-004a (session-bound) and
  the clarified requirement for full re-verification after any session loss.
- *Cache face image to retry* — rejected: violates FR-017 (immediate discard, never on disk).

---

## Decision 7 — Defined behavior for every back-office interaction; idempotent enrollment

**Decision**: Model every API call with an explicit result type covering success, business
rejection, transient failure, and timeout (FR-027). No path silently reports success. Enrollment
(FR-020, FR-022) uses an **idempotency key** (per transaction/visit) so a retry after an uncertain
outcome (timeout/connectivity loss mid-request) cannot create a duplicate; on uncertainty the app
offers a safe re-check/retry and never shows success until the back office confirms. Timeouts and
retries use bounded backoff; user-facing errors are mapped to clear, non-revealing messages via a
single shared error-mapper (FR-021, FR-029).

**Rationale**: SC-003 requires 100% of failures/timeouts to read as unresolved and 100% duplicate
prevention on retry. An idempotency key is the standard, reliable way to make "retry after unknown
outcome" safe. A single sealed result type + shared mapper guarantees consistent, testable handling
across all four APIs (Principle III consistency, Principle II testability of denial paths).

**Alternatives considered**:
- *Blind client retry without idempotency* — rejected: risks duplicate enrollments (violates
  FR-022/SC-003).
- *Per-call ad-hoc error handling* — rejected: inconsistent messaging and untestable; violates
  Principle III and complicates coverage of denial paths (Principle II).

---

## Cross-cutting testing & tooling decisions

- **Testing stack**: JUnit4 + MockK (unit), Turbine (Flow assertions), MockWebServer (API contract
  tests against the four contracts), Compose UI Test + Espresso (instrumented camera/permission/
  navigation), androidTest for on-device NFC/camera. Justification: satisfies Principle II's layered
  coverage and both success/denial paths; MockWebServer lets contract tests run without a live back
  office.
- **Static analysis / gates**: Android Lint + `detekt` + `ktlint`; build must be warning-clean;
  LeakCanary in debug for verification screens. Justification: Principle I (warnings-as-defects) and
  Principle IV (leak-clean verification screens).
- **Redaction**: OkHttp logging interceptor enabled only in debug and configured to redact
  auth/identity/biometric fields; a lint/detekt rule (or review checklist item) guards against
  logging sensitive data. Justification: FR-029, FR-030, constitution security/privacy.

## Open items (non-blocking, to finalize as config/business values)

These do not block Phase 1 — the architecture is agnostic to their exact values:

- Match-confidence threshold %, face retry/lockout counts + cooldown, sign-in lockout policy,
  verification-freshness window duration → delivered by the back office (Decision 1).
- Exact per-attempt end-to-end latency budget (network-dependent) → to be measured on the reference
  device and recorded per Constitution Principle IV.
- Precise wire shape of the four APIs (paths, field names, auth scheme, idempotency-key mechanism) →
  captured as provisional contracts in `contracts/` and to be reconciled with the real back office;
  the client interfaces (`client-interfaces.md`) are stable regardless.
