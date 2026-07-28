# Contract: Member Card Verification API

> **Superseded — do not implement from this file.** The back office has since published its real
> contract as `docs/openapi.json`, and the app is aligned to that. Paths, field names and status
> codes below are the provisional shapes this feature was designed against and are now wrong in
> places (the response is a `MemberVerificationResource` with `capabilities`, and `memberVerified`/`memberResolved`/`membershipStatus` are gone). What still holds is the *client-side* reasoning — which outcomes are
> business rejections, which are transient, and what must never be reported as success.

**Feature**: 001-identity-verification-enrollment | **Consumer**: FaceVerify Android app | **Direction**: app → back office

Provisional contract for authoritative member card verification (FR-007, FR-008, FR-011, FR-011a).
The **card number is read on-device** from an NDEF text record; this API establishes the
authoritative membership verdict and resolves the member record by card number.

## On-device pre-step (not this API)

The app enables NFC reader mode, waits for a card tap, and reads the first well-known NDEF Text
record off the tag. NDEF is unauthenticated: there is no access key to derive and no secure-messaging
handshake — the tag is simply read. The decoded text is validated against the card-number format rule
(digits only, longer than 6 characters, at most 32) before anything is sent.

A card that carries no readable, well-formed number is a **business rejection**, not a transient
failure — retrying the tap will not help — so the app routes the operator to manual entry of the
number printed on the card. Manually entered numbers pass through the exact same format rule and the
exact same API call.

## POST /members/verify

Verify a scanned member card and resolve the member.

**Request**
```json
{
  "memberNumber": "string (digits only, 7-32 chars; the patient key)"
}
```

**Success 200**
```json
{
  "status": "VALID | INVALID",
  "reason": "string | null (specific reason when INVALID)",
  "memberVerified": true,
  "memberResolved": true,
  "referenceOnFile": true,
  "member": {
    "memberNumber": "string",
    "fullName": "string",
    "dateOfBirth": "YYYY-MM-DD",
    "membershipStatus": "string",
    "plan": "string | null"
  }
}
```

**Failure / verdicts**
| Case | Server signal | App behavior |
|------|---------------|--------------|
| Membership rejected (expired, suspended, …) | `status: INVALID` + `reason` | Reject with the specific reason; NOT member-verified (FR-008, AS-3). |
| Member not resolvable | 404 / `memberResolved: false` | Clear message; halt (cannot key subsequent calls). |
| 5xx / timeout | — | `TransientFailure`/`Timeout`; retry allowed without losing session/prior steps (FR-009). |

**Rules**:
- `memberVerified == true` requires server `VALID` **and** `memberVerified` **and** a resolved
  member. Membership validity is entirely server-owned — a member card carries no expiry, so the app
  performs no local pre-check (FR-008).
- `memberNumber` is the patient key for face verification, service listing, and duplicate checks
  (FR-011a); never logged in the clear (FR-029). The domain type's `toString()` is redacting.
- The reference photo comes from the back office for the resolved member; the card carries none
  (FR-011).

## Contract test expectations (MockWebServer)

- `VALID` + `memberVerified` + resolved → member-verified, returned details shown for confirmation. (AS-1)
- Unreadable card (device signal) → business rejection routing to manual entry. (AS-2)
- `INVALID` + `reason` → rejected with the specific reason, not verified. (AS-3, FR-008)
- 404 → patient-not-found rejection; 5xx → transient; no response → `Timeout`. (FR-009)
- NFC unavailable/disabled (device signal) → clear explanation plus manual entry. (AS-4, FR-010)
