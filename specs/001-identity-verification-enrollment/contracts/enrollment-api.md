# Contract: Service Enrollment API

**Feature**: 001-identity-verification-enrollment | **Consumer**: FaceVerify Android app | **Direction**: app → back office

Provisional contract for listing eligible services and adding one for the current visit
(FR-018–FR-023a). Enrollment is idempotent to prevent duplicates on retry (Decision 7).

## GET /patients/{memberNumber}/services

List services available/eligible for this patient (keyed by document number, FR-011a, FR-023).

**Success 200**
```json
{
  "services": [
    { "serviceId": "string", "description": "string", "eligibleForPatient": true, "alreadySelected": false }
  ]
}
```

**Rules**: Only server-reported services are selectable (the app invents none). `alreadySelected`
supports the duplicate guard (FR-019). Selection is per current visit/transaction (FR-023a).

## POST /patients/{memberNumber}/enrollments

Add the selected service (visit reason) for the current transaction.

**Request**
```json
{
  "serviceId": "string",
  "idempotencyKey": "uuid (per transaction — retries reuse the same key)"
}
```

**Precondition (client-enforced)**: `VerifiedIdentity.isCurrentlyVerified(window) == true` (FR-018,
FR-024, FR-026). Blocked otherwise with an explanation (AS-2).

**Success 201**
```json
{ "enrollmentId": "string", "status": "CONFIRMED", "timestamp": "2026-07-20T12:40:00Z" }
```

**Failure / verdicts**
| Case | Server signal | App behavior |
|------|---------------|--------------|
| Not verified / stale | (client-blocked) or 409 | Block with explanation; do not submit (FR-018, AS-2). |
| Duplicate service | 409 `DUPLICATE` | Prevent; explain why (FR-019, AS-3). |
| Ineligible / conflict / business rule | 422 + `reason` | Surface specific non-technical reason; NOT success (FR-021, AS-4). |
| Timeout / connectivity mid-request | — | `Uncertain`: never show success; offer safe re-check/retry reusing `idempotencyKey` (FR-022, AS-5, SC-003). |

**Rules**:
- Success reported **only** on explicit `CONFIRMED` (FR-020).
- Same `idempotencyKey` on retry guarantees no duplicate enrollment (FR-022, SC-003).
- Each visit is its own enrollment record (FR-023a).

## Idempotent re-check (optional) GET /patients/{memberNumber}/enrollments?idempotencyKey=...

Lets the app resolve an `Uncertain` outcome safely (was it actually created?) without risking a
duplicate (FR-022).

## Contract test expectations (MockWebServer)

- Verified + eligible → `CONFIRMED`, outcome shown. (AS-1)
- Not verified → blocked, no submit. (AS-2)
- Duplicate → prevented + explained. (AS-3, FR-019)
- 422 rejection → specific reason, not success. (AS-4, FR-021)
- Timeout then retry with same key → single enrollment, no duplicate. (AS-5, FR-022, SC-003)
