# Feature Specification: Identity Verification & Service Enrollment

**Feature Branch**: `001-identity-verification-enrollment`

**Created**: 2026-07-20

**Status**: Draft

**Input**: User description: "Create an app that will hook into API calls for login and face verification and NFC scanning and adding of a service. There are a lot of business rules that need to be defined"

## Overview

The app lets an authorized person prove someone's identity and then enroll that person into a service. It does this by connecting to four back-office capabilities exposed as APIs: **login/authentication**, **face verification** (live selfie matched against a trusted reference), **NFC scanning** (reading an NFC-enabled identity document), and **service enrollment** ("adding a service"). The value the app delivers is a trustworthy, auditable path from "who is this person?" to "this verified person is now enrolled in the service" — with a clear, enforced set of business rules governing when each step is allowed, how failures are handled, and how sensitive data is treated.

Because this is an identity/biometric flow in a medical context, correctness of the rules (who may proceed, when a match counts, what happens on failure) matters as much as the happy path.

## Clarifications

### Session 2026-07-20

- Q: When a session expires between identity verification and enrollment, do prior verification results survive re-login? → A: No — verification is strictly session-bound; any session loss requires full re-verification (NFC + face) after re-login.
- Q: How does the app tell the back office which patient this is? → A: By the identity document's unique identifier (document number) read from NFC; the back office uses it to fetch the reference photo, eligible services, and duplicate checks.
- Q: Where does the face-verification attempt limit/lockout live, and does it survive re-login? → A: The back office owns the limit and lockout, keyed to the patient/document, so lockout persists across sessions and re-logins; the app enforces the returned rule client-side.
- Q: When biometric consent is withheld, is there an alternative verification path or only a clean stop? → A: Clean stop only — the app records "consent withheld", halts the journey, and does not enroll; no non-biometric alternative exists in this feature.
- Q: How long does the app retain a captured face image? → A: Discard immediately after the verification decision returns (or on failure/abort) — held only transiently in memory for the single submission, never written to disk; audit stores outcome/metadata only.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Authenticated access to the verification workspace (Priority: P1)

The operator opens the app and signs in with their credentials. The app authenticates against the back office, establishes a session, and only then unlocks the verification and enrollment capabilities. If the session is missing, expired, or invalid, the app blocks all verification/enrollment actions and returns the operator to sign-in.

**Why this priority**: Nothing else is safe or meaningful without an authenticated, authorized session. This is the foundational slice and the security boundary for every other capability.

**Independent Test**: Sign in with valid credentials and confirm the workspace unlocks; sign in with invalid credentials and confirm access is refused with a clear message; let a session expire and confirm a protected action forces re-authentication. Delivers value as a standalone, testable sign-in gate.

**Acceptance Scenarios**:

1. **Given** a registered operator with valid credentials, **When** they sign in, **Then** a session is established and the verification workspace becomes available.
2. **Given** invalid or unrecognized credentials, **When** the operator attempts to sign in, **Then** access is refused with a clear, non-revealing error and no session is created.
3. **Given** an expired or invalidated session, **When** the operator attempts any verification or enrollment action, **Then** the action is blocked and the operator is prompted to sign in again.
4. **Given** no network connectivity, **When** the operator attempts to sign in, **Then** the app explains the connectivity problem and does not silently fail or appear signed in.

---

### User Story 2 - Verify a person's identity with their NFC document (Priority: P1)

With an active session, the operator scans the subject's NFC-enabled identity document. The app reads the document's identity data (and its reference photo where available), confirms the document is readable and valid, and surfaces the identity details for the operator to confirm before proceeding.

**Why this priority**: The document establishes the trusted reference identity that face verification is measured against, and confirms the person presented a genuine, valid credential. It is a precondition for a trustworthy match.

**Independent Test**: Scan a valid NFC document and confirm the identity fields are read and displayed; present an unreadable/expired/unsupported document and confirm the app reports why and does not treat it as verified.

**Acceptance Scenarios**:

1. **Given** an active session, **When** the operator scans a supported, valid NFC identity document, **Then** the app reads the identity data, confirms document validity, and presents the details for confirmation.
2. **Given** an NFC document that is expired, unsupported, or fails its authenticity/integrity check, **When** it is scanned, **Then** the app rejects it with a specific reason and does not mark the identity as document-verified.
3. **Given** an NFC read that is interrupted (card moved away, timeout), **When** the read fails, **Then** the app reports the interruption and lets the operator retry without losing the session or prior progress.
4. **Given** NFC is unavailable or disabled on the device, **When** the operator reaches this step, **Then** the app clearly explains the limitation and how to proceed.

---

### User Story 3 - Confirm the person with a live face check (Priority: P1)

The operator captures a live selfie of the subject. The app submits it for face verification against the trusted reference (the NFC document photo and/or the back-office reference on file) and returns a match/no-match decision with a liveness check to defeat spoofing. Only a passing result advances the identity to "verified."

**Why this priority**: This is the core "is the presenter the real owner?" check and the app's namesake. Combined with the document, it produces a verified identity.

**Independent Test**: Capture a matching live face and confirm a pass; capture a non-matching face and confirm a fail; present a static photo/spoof and confirm liveness rejects it. Each is independently observable via the returned decision.

**Acceptance Scenarios**:

1. **Given** a document-verified subject, **When** a live selfie is captured and it matches the reference at or above the required confidence with liveness passing, **Then** the identity is marked verified.
2. **Given** a live selfie that does not match the reference or falls below the required confidence, **When** it is submitted, **Then** the attempt is recorded as failed and the identity is not marked verified.
3. **Given** repeated failed attempts up to the allowed limit, **When** the limit is reached, **Then** further attempts are blocked per the retry/lockout rule and the operator is told what to do next.
4. **Given** a spoof attempt (photo, screen, or replay), **When** liveness runs, **Then** the attempt is rejected regardless of visual similarity.
5. **Given** poor capture conditions (low light, no face, multiple faces), **When** the operator attempts capture, **Then** the app gives actionable guidance rather than submitting an unusable image.

---

### User Story 4 - Add a service for the verified person (Priority: P2)

Once the subject's identity is verified, the operator selects and adds a service for that person. The app submits the enrollment to the back office, receives confirmation, and shows the result. Enrollment is only permitted for a currently verified identity and is prevented for duplicates or ineligible cases.

**Why this priority**: This is the business outcome the verification exists to enable, but it depends on Stories 1–3 being in place first, so it follows them.

**Independent Test**: With a verified identity, add an eligible service and confirm success; attempt to add a service for an unverified identity and confirm it is blocked; attempt to add a service the person already has and confirm the duplicate is prevented.

**Acceptance Scenarios**:

1. **Given** a subject whose identity is currently verified, **When** the operator adds an eligible service, **Then** the enrollment is submitted, confirmed by the back office, and the outcome is shown.
2. **Given** a subject whose identity is not verified (or whose verification has expired), **When** the operator attempts to add a service, **Then** the action is blocked with an explanation.
3. **Given** a service the person is already enrolled in, **When** the operator attempts to add it again, **Then** the app prevents the duplicate and explains why.
4. **Given** the back office rejects the enrollment (ineligible, conflict, or business-rule failure), **When** the result returns, **Then** the app surfaces the specific reason and does not report success.
5. **Given** a submission that times out or loses connectivity mid-request, **When** the outcome is uncertain, **Then** the app does not show success and provides a safe way to re-check or retry without creating a duplicate enrollment.

---

### Edge Cases

- **Session expiry mid-flow**: A session expires between identity verification and service enrollment — the app must block the enrollment, force re-authentication, and discard any prior verification results: verification is session-bound, so the patient MUST be fully re-verified (NFC + face) after re-login.
- **Partial verification**: Document read succeeds but face check fails (or vice versa) — the identity must not be treated as verified, and the operator must see exactly which requirement is outstanding.
- **Verification freshness**: Identity was verified some time ago — the app must enforce a maximum age (the verification-freshness window) after which the person must be re-verified before a service can be added.
- **Document/face mismatch**: NFC identity data and the person implied by the face check refer to different people — the flow must halt and record the discrepancy.
- **Interrupted operations**: App backgrounded, device locked, incoming call, or camera/NFC hardware becomes unavailable mid-step — state must be recoverable without corrupting progress or double-submitting.
- **Connectivity loss**: Any API call fails or hangs — every operation must have a defined behavior (fail visibly, retry safely, avoid duplicate side effects); no operation may silently appear to succeed.
- **Back-office error responses**: The back office returns validation, conflict, rate-limit, or server errors — each class must map to a clear, non-technical, non-sensitive message and a safe next step.
- **Consent withheld**: The subject declines biometric processing — the app must not capture or submit a face image. There is no non-biometric alternative in this feature: the app records "consent withheld", halts the journey cleanly, and does not enroll.
- **Repeated failures / abuse**: Excessive failed face attempts or repeated document rejections — the app must enforce the retry/lockout rule to deter spoofing and abuse.

## Requirements *(mandatory)*

### Functional Requirements

#### Authentication & Session

- **FR-001**: The app MUST authenticate the operator against the back office before any verification or enrollment capability is available.
- **FR-002**: The app MUST maintain an authenticated session and MUST attach valid session credentials to every verification and enrollment request.
- **FR-003**: The app MUST block all verification and enrollment actions when no valid session exists, and MUST route the operator to re-authenticate.
- **FR-004**: The app MUST detect an expired or invalidated session (including mid-flow) and MUST require re-authentication before continuing.
- **FR-004a**: Verification results MUST be strictly session-bound. On any session loss, the app MUST discard prior verification results (document- and face-verified state); after re-authentication the patient MUST be fully re-verified (NFC + face) before enrollment is permitted.
- **FR-005**: The app MUST reject invalid credentials with a clear message that does not reveal whether the identifier or the secret was wrong, and MUST NOT create a session on failure.
- **FR-006**: The app MUST define and enforce a limit on consecutive failed sign-in attempts, after which further attempts are throttled or temporarily blocked. *(Assumption: back office is the source of truth for lockout; see Assumptions.)*

#### NFC Document Scanning

- **FR-007**: The app MUST read identity data from a supported NFC-enabled identity document and present the read identity details to the operator for confirmation before advancing.
- **FR-008**: The app MUST confirm the scanned document is valid — not expired and passing its integrity/authenticity check — and MUST reject documents that fail, with a specific reason.
- **FR-009**: The app MUST handle interrupted or timed-out NFC reads gracefully, allowing retry without loss of session or prior verified steps.
- **FR-010**: The app MUST detect when NFC is unsupported or disabled on the device and MUST inform the operator with a clear explanation.
- **FR-011**: Where the document provides a reference photo, the app MUST make it available as a reference for face verification.
- **FR-011a**: The app MUST use the identity document's unique identifier (document number) read from NFC as the key that identifies the patient to the back office — for retrieving any reference photo/record on file, listing the patient's eligible services, and performing duplicate-enrollment checks. The app MUST NOT require a separate operator-entered patient identifier for this resolution.

#### Face Verification

- **FR-012**: The app MUST capture a live face image of the subject and submit it for verification against the trusted reference.
- **FR-013**: The app MUST treat identity as face-verified only when the match confidence meets or exceeds the required threshold AND a liveness check passes.
- **FR-014**: The app MUST reject spoof attempts (printed photo, screen replay, recorded video) via liveness, regardless of visual similarity.
- **FR-015**: The app MUST enforce a defined maximum number of face-verification attempts and apply a defined lockout/cooldown when the limit is reached. The back office is the source of truth for the limit and lockout state, keyed to the patient/document, so the lockout MUST persist across sessions and re-logins (a lockout MUST NOT be resettable by signing out and back in). The app MUST enforce the back-office-returned rule client-side and block further attempts while locked out.
- **FR-016**: The app MUST guide the operator through unusable-capture conditions (no face, multiple faces, poor lighting) instead of submitting an unusable image.
- **FR-017**: The app MUST record the outcome (pass/fail and reason) of every verification attempt for audit purposes. The captured face image MUST be discarded immediately after the verification decision returns (or on failure/abort) — held only transiently in memory for the single submission and NEVER written to disk. The audit record MUST store outcome/metadata only, never the raw biometric image.

#### Service Enrollment ("Adding a Service")

- **FR-018**: The app MUST allow adding a service only for a subject whose identity is currently verified within the allowed verification-freshness window (composite precondition per FR-024; window per FR-026).
- **FR-019**: The app MUST prevent duplicate enrollment in a service the subject already holds and MUST explain the prevention.
- **FR-020**: The app MUST submit the enrollment to the back office and MUST report success only when the back office confirms it.
- **FR-021**: The app MUST surface back-office rejection reasons (ineligibility, conflict, business-rule failure) in clear, non-technical language and MUST NOT report success on rejection.
- **FR-022**: The app MUST handle uncertain outcomes (timeout, connectivity loss mid-submission) without showing success and without creating duplicate enrollments, providing a safe re-check or retry.
- **FR-023**: The app MUST let the operator select, for the current transaction, the service (the reason/purpose of the patient's visit) from the services the back office reports as available/eligible for that patient — analogous to booking a visit and choosing why the patient is there.
- **FR-023a**: The selected service MUST apply to the current transaction/visit (a per-visit selection), not a standing subscription; each new visit selects its own service.

#### Cross-Cutting Business Rules

- **FR-024**: The app MUST enforce the identity-verification precondition (document-verified AND face-verified, referring to the same person) before enrollment is permitted (gates FR-018; freshness per FR-026), and MUST show the operator which requirements remain outstanding.
- **FR-025**: The app MUST halt and record a discrepancy when the NFC identity and the face-verification subject do not correspond to the same person.
- **FR-026**: The app MUST define a verification-freshness window and MUST require re-verification when identity was last verified longer ago than that window. The verification-freshness window duration is a back-office-owned configuration value; the app MUST obtain it from the back office (delivered in the login/session response) or remote config rather than hardcoding it, and MUST enforce the returned value.
- **FR-027**: Every back-office interaction MUST have a defined behavior for success, business rejection, transient failure, and timeout — no operation may silently appear to succeed.
- **FR-028**: The app MUST obtain the subject's consent before capturing or transmitting biometric (face) data, and MUST NOT capture or submit that data if consent is withheld. When consent is withheld, the app MUST record "consent withheld", halt the journey cleanly, and MUST NOT enroll; this feature provides no non-biometric alternative verification or enrollment path.
- **FR-029**: The app MUST NOT log or expose sensitive identity or biometric data in error messages, diagnostics, or persisted logs; user-facing errors MUST be actionable but non-revealing.
- **FR-030**: The app MUST maintain an audit trail of key actions (sign-in, document scan result, face-verification result, enrollment result) sufficient to reconstruct what happened, storing outcome/metadata only and NEVER persisting raw biometric artifacts (see FR-017).
- **FR-031**: The app operates in **assisted mode**: a signed-in staff **operator** verifies and enrolls a distinct **patient (subject)**. The operator is the authenticated account; consent for biometric processing MUST be obtained from the patient, not the operator.
- **FR-032**: The app MUST enforce a **single sequential enrollment journey** in this order — sign in → scan NFC document → live face check → add service — where each step gates the next and the operator processes one patient/visit at a time. A later step MUST NOT be reachable until its prerequisite step has succeeded.

### Key Entities *(include if feature involves data)*

- **Operator**: The authenticated staff user of the app who verifies and enrolls patients (assisted mode). Key attributes: identity/credentials, session state, permissions.
- **Session**: The proof of authenticated access attached to protected requests. Attributes: validity, expiry, association to an operator.
- **Subject (Patient)**: The person whose identity is being verified and for whom a visit/service is added. Identified to the back office by the document's unique identifier (document number). Attributes: document identifier (patient key), identity details (from the document), verification state, verification timestamp/window, consent status.
- **Identity Document**: The NFC-enabled credential presented by the subject. Attributes: unique document identifier (used as the patient lookup key), readable identity fields, validity/expiry, authenticity result, optional reference photo.
- **Verification Attempt**: A single face-verification event. Attributes: outcome (pass/fail), reason, confidence result, liveness result, timestamp — used for audit and retry/lockout rules.
- **Verified Identity**: The composite result that the subject is confirmed (document-verified AND face-verified for the same person, within the window). Precondition for enrollment.
- **Service**: The reason/purpose of the patient's current visit (e.g., the visit type selected when booking), chosen per transaction from a back-office catalog. Attributes: identifier, description, eligibility for this patient, whether already selected for this visit.
- **Enrollment (Visit/Transaction)**: The record that a verified patient has had a service (visit reason) added for the current transaction. Attributes: patient, selected service, confirmation status, timestamp. Each visit is its own record.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator can complete the full path — sign in, verify a subject's identity (document + face), and add a service — in under 5 minutes for a cooperative subject with a valid document.
- **SC-002**: 100% of enrollment attempts on an unverified or stale-verified identity are blocked (zero services added without a current verified identity).
- **SC-003**: 100% of operations that fail or time out are reported to the operator as unresolved (never shown as false success), and duplicate enrollments from retries are prevented in 100% of retry cases.
- **SC-004**: Liveness/match accuracy (spoof rejection ≥99%; genuine first/second-attempt pass ≥95%) is a back-office-owned metric measured against the server's liveness/match service, and is out of scope for client-side verification in this feature. Client-side, the app MUST correctly surface every liveness/match decision the back office returns (spoof-rejected, no-match, low-confidence) with the correct UX state — verified for 100% of enumerated decision cases.
- **SC-005**: 0 instances of sensitive identity or biometric data appearing in user-facing messages, logs, or diagnostics across the test suite.
- **SC-006**: Every failure state (bad credentials, unreadable/expired document, no-match, back-office rejection, connectivity loss) presents a clear reason and a defined next step, verified for 100% of enumerated failure cases.
- **SC-007**: Duplicate-service prevention succeeds in 100% of attempts to add a service the subject already holds.

## Assumptions

- The back office already exposes APIs for authentication, face verification, NFC/document validation, and service enrollment; this feature integrates with ("hooks into") them rather than defining the server-side implementation.
- The back office is the source of truth for match thresholds, eligibility, duplicate detection, and account lockout; the app enforces the resulting rules and presents outcomes. Specific numeric thresholds (confidence %, retry counts, verification-freshness window duration) are configuration/business decisions to be finalized during clarification and planning.
- The subject presents a supported NFC-enabled identity document; the trusted reference for face matching is the document photo and/or a back-office reference on file.
- Network connectivity is required for all four capabilities; there is no offline verification path in this feature.
- The app runs on a mobile device with a camera and NFC hardware; when hardware is unavailable, the affected capability degrades gracefully with a clear message.
- Operating in a medical/identity context, biometric consent and data-minimization/retention practices apply and are treated as mandatory rather than optional.

## Resolved Clarifications

- **CLARIFY-1 (actor/mode) — RESOLVED**: Assisted mode. A staff **operator** signs in and verifies/enrolls a distinct **patient**; consent is obtained from the patient. (FR-031.)
- **CLARIFY-2 (service scope) — RESOLVED**: "Adding a service" means selecting the service for the current **transaction/visit** — analogous to booking a visit and choosing why the patient is there — from a back-office catalog, per visit rather than as a standing subscription. (FR-023, FR-023a.)
- **CLARIFY-3 (flow linkage) — RESOLVED**: A single enforced sequential journey: sign in → NFC → face → add service, each step gating the next. (FR-032.)

Remaining details for `/speckit-clarify` / planning (non-blocking): specific numeric thresholds (match confidence %, retry/lockout counts, verification-freshness window duration) — to be set as configuration/business values.
