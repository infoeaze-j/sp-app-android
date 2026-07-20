# Contract: Face Verification API

**Feature**: 001-identity-verification-enrollment | **Consumer**: FaceVerify Android app | **Direction**: app → back office

Provisional contract for the authoritative face match + liveness decision (FR-012–FR-017). The
server owns the match threshold and liveness verdict (Decision 2); the app captures, submits, and
**immediately discards** the frame (FR-017).

## Precondition (client-enforced, not this API)

- Session `Active`; document-verified; **biometric consent granted** (FR-028) — no capture without it.
- On-device ML Kit framing guidance ensured a usable frame (one face, adequate light) (FR-016).
- Not currently locked out (FR-015).

## POST /face/verify

Submit a live frame for verification against the trusted reference (DG2 photo and/or server
reference on file), keyed to the patient.

**Request** (multipart or JSON with base64; frame transient in memory only)
```json
{
  "documentNumber": "string (patient key)",
  "image": "base64 (live frame, transient — never persisted client-side)",
  "captureMeta": { "hasLivenessChallengeResponse": true }
}
```

**Success 200**
```json
{
  "decision": "PASS | FAIL",
  "reason": "string | null",
  "liveness": "PASS | FAIL",
  "sameSubject": true,
  "lockout": { "lockedOut": false, "remainingAttempts": 2, "cooldownUntil": null }
}
```

**Semantics / verdicts**
| Case | Server signal | App behavior |
|------|---------------|--------------|
| Match + liveness pass, same subject | `decision: PASS, liveness: PASS, sameSubject: true` | Mark face-verified; record attempt outcome only (FR-013, FR-017). |
| No-match / below threshold | `decision: FAIL` | Record failed attempt; not verified (FR-013, AS-2). |
| Spoof detected | `liveness: FAIL` | Reject regardless of similarity (FR-014, AS-4). |
| Different person | `sameSubject: false` | Halt + record discrepancy (FR-025). |
| Limit reached | `lockout.lockedOut: true` + `cooldownUntil` | Block further attempts; show next step (FR-015, AS-3). Persists across sessions. |
| 5xx / timeout | — | `TransientFailure`/`Timeout`; frame already discarded; safe retry within lockout budget. |

**Rules**:
- The captured image is **held only in memory for this single request** and discarded immediately
  after the response (or on failure/abort) — never written to disk (FR-017).
- Only outcome/metadata is recorded for audit; never the raw image (FR-017, FR-030).
- Lockout state in the response is server-owned and authoritative (FR-015).

## Contract test expectations (MockWebServer)

- PASS + liveness PASS + sameSubject → verified. (AS-1)
- FAIL / below threshold → failed attempt, not verified. (AS-2)
- `lockedOut` after limit → attempts blocked, cooldown shown; not resettable by re-login. (AS-3, FR-015)
- `liveness: FAIL` on spoof → rejected. (AS-4)
- After any response, no image bytes remain referenced / written. (FR-017 — verified via memory/state assertion)
