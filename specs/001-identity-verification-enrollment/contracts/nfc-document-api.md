# Contract: NFC Document Validation API

**Feature**: 001-identity-verification-enrollment | **Consumer**: FaceVerify Android app | **Direction**: app → back office

Provisional contract for authoritative document validation (FR-007, FR-008, FR-011, FR-011a). The
**chip is read on-device** via JMRTD (Decision 3); this API establishes the authoritative
authenticity/validity verdict and resolves the patient record by document number (Decision 4).

## On-device pre-step (not this API)

The app reads the NFC chip with `NfcAdapter`/`IsoDep` + JMRTD, unlocking secure messaging via the
MRZ/CAN-derived access key. It parses DG1 (identity fields, document number) and DG2 (reference photo
if present), and performs a local integrity read. Then it calls the API below.

## POST /documents/validate

Validate a read document and resolve the patient.

**Request**
```json
{
  "documentNumber": "string (patient key, from DG1/MRZ)",
  "identityFields": { "surname": "string", "givenNames": "string", "dateOfBirth": "YYYY-MM-DD", "nationality": "string", "sex": "string", "expiryDate": "YYYY-MM-DD", "issuingAuthority": "string" },
  "securityObject": "base64 (SOD)",
  "dataGroupHashes": { "DG1": "base64", "DG2": "base64" },
  "localIntegrity": "PASSED | FAILED | NOT_CHECKED"
}
```

**Success 200**
```json
{
  "authenticity": "VALID | INVALID",
  "reason": "string | null (specific reason when INVALID)",
  "documentVerified": true,
  "referenceOnFile": true,
  "patientResolved": true
}
```

**Failure / verdicts**
| Case | Server signal | App behavior |
|------|---------------|--------------|
| Expired / failed authenticity / unsupported | `authenticity: INVALID` + `reason` | Reject with the specific reason; NOT document-verified (FR-008, AS-2). |
| Patient not resolvable | 404 / `patientResolved: false` | Clear message; halt (cannot key subsequent calls). |
| 5xx / timeout | — | `TransientFailure`/`Timeout`; retry allowed without losing session/prior steps (FR-009). |

**Rules**:
- `documentVerified == true` requires server `VALID` **and** locally not-expired (FR-008).
- `documentNumber` is the patient key for face-verification, service listing, and duplicate checks
  (FR-011a); never logged in the clear (FR-029).
- DG2 reference photo, if present, is used on-device transiently; otherwise the server reference is
  used (FR-011).

## Contract test expectations (MockWebServer)

- `VALID` + not expired → document-verified, details shown for confirmation. (AS-1)
- `INVALID` (expired/unsupported/integrity fail) → rejected with specific reason, not verified. (AS-2, FR-008)
- Interrupted read (handled on-device) → retry without session loss. (AS-3, FR-009)
- NFC unavailable/disabled (device signal) → clear explanation, no false verification. (AS-4, FR-010)
