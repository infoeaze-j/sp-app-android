# Phase 1 Data Model: Identity Verification & Service Enrollment

**Feature**: 001-identity-verification-enrollment | **Date**: 2026-07-20

Domain entities derived from the spec's **Key Entities** and functional requirements. These are the
in-memory / domain model types (Kotlin `data class` / `sealed` types under `domain/model`). Nothing
here is persisted to disk except non-sensitive UI/config preferences; biometric artifacts are
**never** stored (FR-017, FR-030).

## Conventions

- Immutable value types; state changes produce new instances (UDF).
- Timestamps are monotonic where used for freshness; wall-clock values come from the server when
  authoritative.
- "Transient" = held in memory only for a single operation, then cleared.

---

## Entity: Operator

The authenticated staff user (assisted mode, FR-031).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `operatorId` | String | Server-assigned identifier of the signed-in staff account. |
| `displayName` | String? | For UI only; never used as a security decision. |
| `permissions` | Set\<String\> | Capability flags returned by the back office. |

**Rules**: Operator is the authenticated account; consent for biometrics is obtained from the
**patient**, not the operator (FR-031). Operator identity is derived from the session, never entered.

---

## Entity: Session

Proof of authenticated access attached to every protected request (FR-002).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `token` | String (opaque) | Bearer/opaque credential; **transient in memory**, redacted from logs (FR-029). |
| `operator` | Operator | Owner of the session. |
| `expiresAt` | Instant? | Client-visible expiry hint; server remains authoritative on validity. |
| `state` | SessionState | See below. |

**SessionState** (sealed): `Active` · `Expired` · `Invalidated` · `None`.

**Rules**:
- No protected action is permitted unless `state == Active` (FR-003).
- On `Expired`/`Invalidated`/`None`, all verification state is discarded and re-auth is forced
  (FR-004, FR-004a).
- Token never persisted to disk by default (Decision 6); never logged.

---

## Entity: MemberNumber

The validated member card number, read on-device from an NDEF text record or entered manually
(FR-007, FR-011a). A value class with a private constructor, so an instance is proof the format rule
already passed and no caller re-validates.

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `value` | String | The digits. Constructed only via `parse`. |

**Rules**:
- Format rule (sole owner): digits only, `MIN_LENGTH = 7` to `MAX_LENGTH = 32`. Surrounding
  whitespace is trimmed; leading zeros are preserved; anything else parses to `null`.
- Bounded above so a garbage NDEF payload cannot become an unbounded URL path segment.
- `toString()` is deliberately redacting (`MemberNumber(***)`) so an accidental interpolation into a
  log line or exception message cannot leak the patient key (FR-029). Use `value` to send it.

---

## Entity: MemberVerification

The authoritative back-office verdict for a scanned card (FR-008, FR-011, FR-011a).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `status` | Status | `VALID` · `INVALID`. |
| `reason` | String? | Specific reason when `INVALID`; surfaced as a curated message, never raw. |
| `memberVerified` | Boolean | Server's own flag; required for a verified outcome. |
| `memberResolved` | Boolean | Whether the back office resolved a member for this number. |
| `referenceOnFile` | Boolean | Whether a reference photo exists for face verification (FR-011). |
| `member` | MemberDetails? | Present when resolved. |

**Rules**:
- Member-verified **only** when `status == VALID` **and** `memberVerified` **and** a resolved member
  with details. Membership validity is entirely server-owned — a member card carries no expiry, so
  there is no local pre-check (FR-008).
- Without resolved details there is nothing to key `/face/verify` or `/patients/...` on, so an
  unresolved member halts the journey.

---

## Entity: MemberDetails

The member the back office returned, shown for operator confirmation (FR-011).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `memberNumber` | String | **Patient key** for all subsequent calls (FR-011a). Never logged in the clear (FR-029). |
| `fullName` | String | Shown on the confirmation step. |
| `dateOfBirth` | String | Shown on the confirmation step. |
| `membershipStatus` | String | Shown on the confirmation step. |
| `plan` | String? | Shown when present.

---

## Entity: BiometricConsent

Records the patient's consent decision before any capture (FR-028).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `status` | ConsentStatus | See below. |
| `recordedAt` | Instant | When the decision was captured (audit metadata). |

**ConsentStatus** (sealed): `Granted` · `Withheld`.

**Rules**:
- If `Withheld`: the app MUST NOT capture or submit any face image, MUST record "consent withheld"
  to the audit trail, and MUST halt the journey cleanly with no enrollment (FR-028). No non-biometric
  alternative exists.
- Consent is obtained from the patient (FR-031), gathered after the document is verified, and MUST
  precede any face-verification capture/submission (FR-028, FR-032).

---

## Entity: VerificationAttempt

A single face-verification event, for audit and retry/lockout (FR-015, FR-017, FR-030).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `attemptId` | String | Correlation id (metadata only). |
| `outcome` | AttemptOutcome | See below. |
| `reason` | String? | Non-sensitive reason code/text for failures. |
| `confidenceResult` | ConfidenceResult | Server verdict vs threshold (pass/fail); no raw score stored if sensitive. |
| `livenessResult` | LivenessResult | `Passed` · `Failed`. |
| `timestamp` | Instant | Audit metadata. |

**AttemptOutcome** (sealed): `Passed` · `Failed(reason)` · `Aborted`.

**Rules**:
- The captured image is **never** part of this record — outcome/metadata only (FR-017, FR-030).
- Attempts count toward the **server-owned** lockout keyed to the patient/document; the client
  enforces the server-returned remaining-attempts/lockout (FR-015). Lockout persists across sessions.

---

## Entity: FaceLockoutState (server-derived)

Client mirror of the back-office lockout rule (FR-015).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `lockedOut` | Boolean | When true, further attempts are blocked client-side. |
| `remainingAttempts` | Int? | As returned by the server. |
| `cooldownUntil` | Instant? | When the operator may retry. |

**Rules**: Derived only from server responses; never reset by sign-out/re-login (FR-015). The client
enforces it but the server owns it.

---

## Entity: VerifiedIdentity

Composite precondition for enrollment (FR-024, FR-025, FR-026).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `memberNumber` | String | Ties the composite to one patient. |
| `memberVerified` | Boolean | Set only on server `VALID` + `memberVerified`. |
| `faceVerified` | Boolean | Set only on server pass + liveness pass (FR-013). |
| `sameSubject` | Boolean | Member on file and face subject correspond (FR-025). |
| `verifiedAt` | Instant? | Freshness anchor for the verification-freshness window (FR-026). |

**Derived rule** — `isCurrentlyVerified(window)`:
`memberVerified && faceVerified && sameSubject && verifiedAt != null && (now - verifiedAt) <= window`.

**Rules**:
- Enrollment is permitted **only** when `isCurrentlyVerified(window)` is true (FR-018, FR-024).
- If `sameSubject == false`, the flow halts and records a discrepancy (FR-025).
- Any session loss clears this entity entirely (FR-004a).

---

## Entity: Service

The reason/purpose of the current visit, chosen per transaction (FR-023, FR-023a).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `serviceId` | String | Catalog identifier. |
| `description` | String | Human-readable label (from server; shown as-is). |
| `eligibleForPatient` | Boolean | Server-reported eligibility for this patient. |
| `alreadySelected` | Boolean | Patient already holds/added this for the relevant scope (duplicate guard, FR-019). |

**Rules**: Selectable list comes from the back office for the specific patient (keyed by
`memberNumber`); the app does not invent services. Selection applies to the **current
visit/transaction only** (FR-023a), not a standing subscription.

---

## Entity: Enrollment (Visit / Transaction)

The record that a verified patient had a service added for the current visit (FR-020, FR-022).

| Field | Type | Notes / Rules |
|-------|------|---------------|
| `enrollmentId` | String? | Assigned by the back office on confirmation. |
| `memberNumber` | String | Patient key. |
| `service` | Service | The selected visit reason. |
| `idempotencyKey` | String | Per-transaction key so retries never duplicate (FR-022, Decision 7). |
| `status` | EnrollmentStatus | See below. |
| `timestamp` | Instant | Audit metadata. |

**EnrollmentStatus** (sealed): `Confirmed(enrollmentId)` · `Rejected(reason)` · `Uncertain` · `Pending`.

**Rules**:
- `Confirmed` is set **only** on explicit back-office confirmation (FR-020).
- `Rejected` surfaces the specific non-technical reason (FR-021).
- `Uncertain` (timeout/connectivity loss) never shows as success and offers safe re-check/retry
  bound to the same `idempotencyKey` (FR-022, SC-003).
- Each visit is its own record (FR-023a).

---

## Cross-cutting result & error types

**`AppResult<T>`** (sealed) — every back-office interaction returns one (FR-027):

| Variant | Meaning |
|---------|---------|
| `Success(data)` | Confirmed successful outcome. |
| `BusinessRejection(reason)` | Server business rule said no (ineligible, conflict, invalid doc, no-match). |
| `TransientFailure(cause)` | Retriable network/server error. |
| `Timeout` | No definitive outcome; treat as uncertain. |

**`AppError`** — mapped by a single shared error-mapper to a clear, **non-revealing** user message
(FR-021, FR-029). No `AppError` message contains identity/biometric data.

---

## Journey state (screen-gating)

The single sequential journey (FR-032) is modeled as an ordered, gated state. A later step is
unreachable until its prerequisite succeeds.

```text
NotSignedIn
  → SignedIn (Session.Active)
    → DocumentScanning → DocumentVerified (server Valid)
      → ConsentPending → ConsentWithheld (halt) | ConsentGranted
        → FaceChecking → FaceVerified (server pass + liveness + sameSubject)
          → ReadyToEnroll (isCurrentlyVerified == true)
            → EnrollmentSubmitting → Confirmed | Rejected | Uncertain
```

**Transitions & guards**:
- Any `Session` transition to non-`Active` from any state → `NotSignedIn`, clearing all verification
  state (FR-004a).
- `ConsentWithheld` is terminal for the journey (records "consent withheld", no enrollment) (FR-028).
- Freshness expiry (`now - verifiedAt > window`) from `ReadyToEnroll` → back to `DocumentScanning`
  (re-verify) (FR-026).
- `sameSubject == false` at face step → halt + record discrepancy (FR-025).
- Face lockout (server) blocks re-entry to `FaceChecking` until `cooldownUntil` (FR-015).

## Validation summary (traceability)

| Rule | Enforced by | Requirement |
|------|-------------|-------------|
| No action without `Session.Active` | Journey guard | FR-003 |
| Session loss discards verification | Journey + SessionManager | FR-004a |
| Member verified only on server `VALID` + `memberVerified` | MemberVerification rules | FR-008 |
| Patient keyed by `memberNumber` | MemberNumber / repositories | FR-011a |
| Face-verified only on server pass + liveness | VerifiedIdentity/VerificationAttempt | FR-013 |
| Lockout server-owned, persists across sessions | FaceLockoutState | FR-015 |
| Face image never persisted | VerificationAttempt (no image field) | FR-017 |
| Enrollment only when currently verified | VerifiedIdentity.isCurrentlyVerified | FR-018, FR-024 |
| Duplicate prevented | Service.alreadySelected + server | FR-019 |
| Success only on confirmation | Enrollment.status | FR-020 |
| Uncertain never shows success; no duplicates | Enrollment idempotencyKey | FR-022 |
| Consent withheld halts cleanly | BiometricConsent rules | FR-028 |
| Freshness window | VerifiedIdentity.isCurrentlyVerified | FR-026 |
| Discrepancy halts | VerifiedIdentity.sameSubject | FR-025 |
