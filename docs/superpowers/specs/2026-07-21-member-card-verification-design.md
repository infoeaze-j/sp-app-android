# Member card verification replaces eMRTD document scan

**Date:** 2026-07-21
**Status:** Approved, ready for planning
**Branch:** `feat/member-card-verification`
**Amends:** `specs/001-identity-verification-enrollment/`

## Problem

After signing in, the operator must be asked to scan a **member card**. The card
carries a numeric-only number longer than 6 characters. That number goes to the
back office for verification, which returns the member's details.

Today the post-login step reads an NFC identity document (eMRTD passport/ID) via
JMRTD with BAC secure messaging, and the eMRTD **document number** is the patient
key threaded through face verification and enrollment. The member card replaces
that step entirely.

## Decisions

| Decision | Choice | Rejected alternative |
| --- | --- | --- |
| How the card is read | NFC tap, number in an NDEF **Text** record | Tag UID (chip-fixed format cannot guarantee digits-only and length > 6) |
| Scope | Member card **replaces** the eMRTD read and `/documents/validate` | Keeping both paths — doubles the surface area and the dev-fake matrix for a path being retired |
| Patient key naming | Rename `documentNumber` → `memberNumber` end-to-end | Reusing the `documentNumber` field to carry a member card number — the name would lie in five files and in the REST paths |
| Membership validity | Server-authoritative via `status`/`reason` | A local expiry pre-check — a member card carries no expiry date |
| Spec home | Amend `001-` in place | A new `002-` spec, which would leave `001-` documenting a deleted flow |

## Journey

`sign in → scan member card → live face check → add service`

This rewrites FR-032, which currently reads
`sign in → scan NFC document → live face check → add service`.

## Architecture

### Added

| File | Responsibility |
| --- | --- |
| `domain/model/MemberNumber.kt` | Value class plus `MemberNumber.parse(raw): MemberNumber?`. The single source of truth for the format rule. Pure and fully unit-testable. |
| `core/nfc/MemberCardReader.kt` | `interface MemberCardReader { fun isAvailable(): NfcAvailability; suspend fun awaitAndRead(host: NfcHost): AppResult<MemberNumber> }`. Owns reader-mode setup, so `android.nfc.Tag` never reaches the ViewModel. |
| `core/nfc/NdefMemberCardReader.kt` | Real implementation: `enableReaderMode` → `Ndef.get(tag)` → first well-known Text record → decode → `MemberNumber.parse`. |
| `domain/model/MemberModels.kt` | `MemberDetails` (the fields the Confirm screen shows) and `MemberVerification` (the server verdict — `status`, `reason`, `memberVerified`, `referenceOnFile`, `memberResolved`), replacing `DocumentValidation`. |
| `data/remote/MemberApi.kt` | `POST members/verify` plus request/response DTOs. |
| `data/repository/MemberRepository.kt` (+ `Impl`) | HTTP → `AppResult`, mirroring `DocumentRepositoryImpl`. |
| `domain/usecase/VerifyMemberUseCase.kt` | Interprets the verdict and sets `VerifiedIdentity` on success. |
| `ui/memberscan/MemberScanScreen.kt`, `MemberScanViewModel.kt` | Replaces `ui/nfcscan/`. |

### Removed

`core/nfc/NfcReader.kt` (JMRTD/BAC), `core/nfc/AccessKeyDeriver.kt`, `ui/nfcscan/`,
`data/remote/DocumentApi.kt`, `data/repository/DocumentRepository.kt`,
`domain/usecase/VerifyDocumentUseCase.kt`, and all of
`domain/model/DocumentModels.kt` except `NfcAvailability` — that is,
`DocAccessKey`, `DocumentIdentity`, `ReadDocument`, `DocIntegrityResult`, and
`DocumentValidation` (superseded by `MemberVerification`) — with their tests.

`NfcAvailability` survives: it describes device hardware, not documents. Since it
would be the file's only remaining type, `DocumentModels.kt` is deleted and
`NfcAvailability` moves to `domain/model/NfcModels.kt`.

The `jmrtd`, `scuba-sc-android`, and `bouncycastle-prov` dependencies are removed
from `gradle/libs.versions.toml` and `app/build.gradle.kts`. `NfcReader.kt` is
their only consumer.

### Renamed (29 files)

- `documentNumber` → `memberNumber` in `VerifiedIdentity`, `FaceVerifyRequest`,
  `EnrollmentApi` paths (`patients/{memberNumber}/…`), `EnrollmentModels`, use
  cases, and tests
- `VerifiedIdentity.documentVerified` → `memberVerified`
- `JourneyStep.DOCUMENT_SCAN` → `MEMBER_SCAN`
- `JourneyGate.furthestReachable(documentVerified =)` → `memberVerified =`
- `AppRoute.NfcScan` → `MemberScan("memberscan", JourneyStep.MEMBER_SCAN)`

### Scan screen phases

`CheckingAvailability, ReadyToScan, Reading, ManualEntry, Verifying, Confirm,
Failed, Verified`

Manual entry is a **phase of the scan screen**, not a separate screen, reachable
from `ReadyToScan` and from `Failed`. The current `AccessKeyEntry` form (document
number + date of birth + expiry, needed to unlock BAC) collapses to a single
numeric field. NDEF is unauthenticated, so `AccessKeyDeriver` has no successor.

## API contract (placeholder)

Shaped like `ValidateDocumentResponse` so the existing `apiCall` / `AppResult` /
`ErrorMapper` plumbing needs no changes.

```
POST /members/verify
{ "memberNumber": "1234567" }

200 →
{
  "status": "VALID",            // or "INVALID"
  "reason": null,               // e.g. "MEMBERSHIP_EXPIRED" when INVALID
  "memberVerified": true,
  "referenceOnFile": true,
  "member": {
    "memberNumber": "1234567",
    "fullName": "Jane Doe",
    "dateOfBirth": "1985-04-12",
    "membershipStatus": "ACTIVE",
    "plan": "Gold"
  }
}
404 → member not resolvable; halt (cannot key /face/verify or /patients/…)
401 → session invalidated (existing AuthInterceptor path)
5XX → transient; retry without losing session or prior steps
```

`status: "INVALID"` is a valid 200, matching how `/documents/validate` already
treats rejection: a rejected member is a business outcome, not a transport error.

The `member` object populates the Confirm screen, replacing the DG1 identity
fields.

Face verification is unaffected in shape: `FaceVerifyRequest` sends only the
patient key and the image, and the server holds the reference photo
(`referenceOnFile`). Dropping DG2 costs nothing here.

## Validation

`MemberNumber.parse` trims surrounding whitespace, then requires `^\d{7,32}$`.

- "More than 6 characters" means **at least 7 digits**.
- The 32-digit upper bound stops a garbage NDEF payload from becoming an
  unbounded path segment in `patients/{memberNumber}/…`.
- The same function gates both the NDEF read and the manual keypad. The UI does
  not re-implement the rule; it only applies a digits-only input filter and a
  numeric keyboard for ergonomics.

## Error handling

`BusinessCode` gains:

- `MEMBER_INVALID` — replaces `DOCUMENT_INVALID`; carries the server `reason`
- `CARD_UNREADABLE` — new; covers all three NDEF failure shapes: no NDEF message
  on the tag, no well-known Text record, or a payload that fails `parse`. Its
  message routes the operator to manual entry rather than into a retry loop.

`PATIENT_NOT_FOUND` is reused for the 404 — same meaning, already wired into
`ErrorMapper` and enrollment.

`DOCUMENT_EXPIRED` is deleted along with the local expiry pre-check in
`VerifyDocumentUseCase`. Membership validity becomes entirely server-owned,
removing an authority split.

The member number is a patient identifier and stays out of logs.
`LoggingRedactionTest` gains a case for it.

## Dev fakes

| Now | Becomes |
| --- | --- |
| `FakeNfcReader` / `SwitchingNfcReader` | `FakeMemberCardReader` / `SwitchingMemberCardReader` |
| `NfcScenario { SUCCESS, READ_FAILED, TIMEOUT, NFC_DISABLED, NO_NFC_HARDWARE }` | `CardScenario { SUCCESS, UNREADABLE, TIMEOUT, NFC_DISABLED, NO_NFC_HARDWARE }` |
| `DocumentScenario` | `MemberScenario` (same four cases) |
| `FakeDocumentRepository` | `FakeMemberRepository` |
| `FakeData.readDocument` (`P1234567` / DOE JANE) | `FakeData.memberNumber` (`"1234567"`) + `memberDetails` |

Scenario enums are persisted by name (`dev_scenario_nfc`, `dev_scenario_document`),
so renaming them orphans any stored value. The plan must verify that
`DataStoreDevSettingsStore` falls back to the default on an unparseable name
rather than throwing. If it does not, that is a bug to fix regardless of this
work.

## Tests

Written before the code they cover.

- **`MemberNumberTest`** — boundary table: 6 digits reject, 7 accept, 32 accept,
  33 reject, leading zeros accept, surrounding whitespace trimmed, letters and
  spaces and `+` reject, empty reject.
- **`VerifyMemberUseCaseTest`** — VALID plus resolved sets
  `VerifiedIdentity(memberNumber, memberVerified = true)`; INVALID yields
  `MEMBER_INVALID` carrying `serverReason`; unresolved yields
  `PATIENT_NOT_FOUND`.
- **`MemberApiContractTest`** (replaces `DocumentApiContractTest`) — MockWebServer
  round-trip plus 404, 401, and 5xx.
- **`MemberScanViewModelTest`** — phase transitions, including
  `CARD_UNREADABLE` → manual entry → verify.
- **`FakeMemberCardReaderTest`, `SwitchingMemberCardReaderTest`** — adapted from
  the existing NFC reader fakes.
- **`LoggingRedactionTest`** — member number redacted.

`NdefMemberCardReader` is not JVM-unit-testable (Android framework types), the
same position `JmrtdNfcReader` holds today. It is device-gated, and the plan says
so rather than implying coverage.

## Spec amendments

In `specs/001-identity-verification-enrollment/`:

- **FR-011a reverses.** It currently states the app "MUST NOT require a separate
  operator-entered patient identifier" for patient resolution. Manual entry of
  the member number is now an explicit fallback.
- FR-007 through FR-011 re-scoped from identity document to member card.
- FR-032's journey renamed.
- User Story 2 retitled from "Verify a person's identity with their NFC document".
- Key Entity `Identity Document` → `Member Card`.
- `contracts/nfc-document-api.md` → `contracts/member-card-api.md`.
- `data-model.md` and `quickstart.md` updated.
- `docs/openapi.yaml`: remove `/documents/validate`, add `/members/verify`,
  rename the `documentNumber` path parameter.

## Build order

The rename lands second, as a mechanical green-to-green sweep, so no other step
is written against two conflicting names.

1. `MemberNumber` plus tests (pure, zero dependencies)
2. Rename sweep across 29 files; build stays green throughout
3. `openapi.yaml`, `MemberApi`, DTOs, contract test
4. `MemberRepository`, `VerifyMemberUseCase`, tests
5. `MemberCardReader`, `NdefMemberCardReader`, fakes, Dev Settings wiring
6. `ui/memberscan` plus ViewModel, tests, strings
7. `AppRoute` / `NavGraph` swap
8. Delete eMRTD files, the three dependencies, and dead strings
9. Spec document amendments
10. Verify: `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`, `lint`

## Out of scope

- Writing member cards. The app reads NDEF; card issuance belongs to whoever
  provisions the stock.
- The real back-office contract. `/members/verify` is a placeholder and will be
  reconciled when the back office publishes its shape.
- Any change to face verification, enrollment, or session logic beyond the key
  rename.
