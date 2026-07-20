# Implementation Plan: Identity Verification & Service Enrollment

**Branch**: `001-identity-verification-enrollment` | **Date**: 2026-07-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-identity-verification-enrollment/spec.md`

## Summary

Deliver an Android app that lets an authenticated staff **operator** verify a **patient's** identity and enroll them into a per-visit service by orchestrating four back-office APIs — login, NFC document validation, face verification, and service enrollment — through a single, enforced sequential journey (sign in → scan NFC → live face check → add service). The back office owns all business thresholds (match confidence, retry/lockout, eligibility, duplicate detection); the app enforces the returned rules client-side, handles every failure/timeout state visibly, and treats biometric data as transient-in-memory-only (never persisted).

Technical approach: a single-module Kotlin/Android app built with Jetpack Compose + Material 3, MVVM + unidirectional data flow, Hilt for DI, Coroutines/Flow for async, Retrofit/OkHttp/kotlinx.serialization for the API layer, CameraX for live capture, ML Kit Face Detection for on-device capture-quality guidance only (the match/liveness decision stays server-side), and Android NFC + JMRTD for on-device eMRTD chip reading. Verification state is session-bound and held in memory; captured face frames are discarded immediately after the decision returns.

## Technical Context

**Language/Version**: Kotlin 2.x (K2), JVM target 11; Gradle Kotlin DSL with version catalog

**Primary Dependencies**:
- UI: Jetpack Compose (BOM) + Material 3, Navigation Compose, Activity Compose
- DI: Hilt
- Async: Kotlin Coroutines + Flow
- Networking: Retrofit + OkHttp (logging interceptor gated to debug + redacting), kotlinx.serialization
- Camera: CameraX (core, camera2, lifecycle, view)
- On-device face framing: ML Kit Face Detection (capture-quality guidance only)
- On-device MRZ read (access key): ML Kit Text Recognition (optional path to derive the NFC access key)
- NFC eMRTD: Android `NfcAdapter` + JMRTD + SCUBA (secure messaging / datagroup parsing)
- Local storage: Jetpack DataStore (non-sensitive prefs); session token held in memory (optionally EncryptedSharedPreferences if persistence is later required)

**Storage**: No biometric persistence (FR-017). In-memory verification state only; DataStore for non-sensitive UI/config preferences; audit outcomes/metadata are recorded to the back office, not stored locally as raw biometrics.

**Testing**: JUnit4 + MockK (unit), Turbine (Flow), MockWebServer (API contract tests), Compose UI Test + Espresso (instrumented: camera/permission/navigation), androidTest for on-device NFC/camera flows. LeakCanary in debug.

**Target Platform**: Android, `minSdk 24`, `targetSdk 36`, `compileSdk 36`. Requires camera + NFC hardware; degrades gracefully with a clear message when either is unavailable/disabled.

**Project Type**: Mobile app — single Gradle module (`app`), internal package layering by responsibility (`data` / `domain` / `ui` / `core`).

**Performance Goals** (measured on the designated mid-range reference device — Google Pixel 6a, or an equivalent ~2022 mid-range device; the actual device is recorded with the measurements): Cold start → interactive < 2s; UI thread never blocked (all inference/I/O off-main); no > 16ms frame during an active verification flow; on-device capture-quality evaluation < 500ms per frame budget; per-attempt end-to-end (capture → decision) budget documented and measured (network-bound; on-device portion is the controlled part).

**Constraints**: Network required for all four capabilities (no offline path). Biometric frames transient-in-memory-only, never written to disk. Sensitive identity/biometric data MUST NOT appear in logs, diagnostics, or user-facing errors. Session-bound verification: any session loss discards verified state and forces full re-verification.

**Scale/Scope**: Single operator processing one patient/visit at a time; 4 primary flows (auth, NFC, face, enroll) across ~5–7 screens; integrates with 4 back-office API groups.

**Open configuration values (back-office-owned, non-blocking)**: match-confidence threshold %, face-attempt retry/lockout counts, sign-in lockout policy, verification-freshness window duration. Resolved architecturally in Phase 0: the app never hardcodes these — it enforces server-returned/remotely-configured rules. See [research.md](./research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against FaceVerify Constitution v1.0.0 (Principles I–IV).

| Principle | Gate | Plan compliance |
|-----------|------|-----------------|
| **I. Code Quality & Maintainability** | Kotlin conventions auto-formatted; single-responsibility units; no untracked TODO/FIXME; clean build; Lint + `detekt`/`ktlint` pass; peer review | ✅ Package layering enforces single responsibility; `detekt` + `ktlint` + Android Lint added as gates (see Phase 1); ≤400-line file / ≤50-line function guidance applied. |
| **II. Testing Standards (NON-NEGOTIABLE)** | Test-first (Red-Green-Refactor); layered unit + instrumented; regression test per bug; ≥80% coverage on changed code with explicit success/denial paths; CI-green | ✅ TDD mandated per task; MockWebServer contract tests for all 4 APIs; explicit denial-path tests (invalid creds, unreadable doc, no-match, lockout, consent-withheld, duplicate). Verification/liveness/permission logic gets success+failure tests. |
| **III. User Experience Consistency** | Shared Material 3 design tokens; string resources only; every state (loading/success/empty/error/permission-denied) designed; 48dp targets, content descriptions, dynamic font, TalkBack; consistent camera/permission/error wording | ✅ Single Compose Material 3 theme (tokens for color/type/spacing); all user text in `strings.xml`; every screen models loading/success/error/permission-denied; shared error-mapping component keeps wording consistent and non-revealing (FR-029). |
| **IV. Performance Requirements** | Off-main-thread inference/IO; <16ms frames; <2s cold start; bounded memory (recycle frames/bitmaps, no leaked Context/camera); LeakCanary clean; before/after measurement for perf-sensitive changes | ✅ Coroutines + `Dispatchers.IO`/`Default`; CameraX lifecycle-bound with prompt `ImageProxy.close()`; ML Kit off-main; LeakCanary in debug on verification screens; per-attempt latency budget measured. |

**Result**: PASS — no violations. Complexity Tracking left empty. Single-module structure is deliberately chosen over multi-module to avoid unjustified complexity (Principle I / simplicity); revisit only if build times or ownership boundaries demand it.

Re-check after Phase 1 design (data-model, contracts, quickstart): **PASS — still compliant.** The contracts keep biometrics server-decided and transient (II/privacy), the client-interface split preserves single responsibility (I), the state model enumerates every UX state (III), and the async repository contracts keep work off-main (IV). No new violations introduced.

## Project Structure

### Documentation (this feature)

```text
specs/001-identity-verification-enrollment/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output — decisions & rationale
├── data-model.md        # Phase 1 output — entities, state, validation
├── quickstart.md        # Phase 1 output — build/run/validate guide
├── contracts/           # Phase 1 output — API + client interface contracts
│   ├── auth-api.md
│   ├── nfc-document-api.md
│   ├── face-verification-api.md
│   ├── enrollment-api.md
│   └── client-interfaces.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

Single-module Android app. New code lives under the existing `app/` module, layered by responsibility inside `com.mediplus.faceverify`.

```text
app/src/main/java/com/mediplus/faceverify/
├── FaceVerifyApp.kt                 # Application (@HiltAndroidApp)
├── MainActivity.kt                  # single-Activity host (Compose)
├── core/
│   ├── di/                          # Hilt modules (network, dispatchers, storage)
│   ├── session/                     # SessionManager (in-memory, session-bound state)
│   ├── nfc/                         # NfcReader (JMRTD/SCUBA), access-key derivation
│   ├── camera/                      # CameraX controller, ML Kit framing analyzer
│   ├── result/                      # Result/AppError types, error → message mapping
│   └── ui/                          # theme (Material 3 tokens), shared composables
├── data/
│   ├── remote/                      # Retrofit APIs, DTOs, interceptors (auth, redaction)
│   ├── local/                       # DataStore (non-sensitive prefs)
│   └── repository/                  # Repository impls (auth, document, face, enrollment)
├── domain/
│   ├── model/                       # domain entities (Session, VerifiedIdentity, ...)
│   └── usecase/                     # journey rules & step-gating use cases
└── ui/
    ├── signin/                      # screen + ViewModel + state
    ├── nfcscan/
    ├── facecheck/
    ├── addservice/
    └── navigation/                  # sequential-journey nav graph & guards

app/src/test/java/com/mediplus/faceverify/          # unit: viewmodels, usecases, mappers, repos (MockWebServer)
app/src/androidTest/java/com/mediplus/faceverify/   # instrumented: camera/permission/NFC/navigation, Compose UI
app/src/main/res/values/strings.xml                 # all user-facing text (localizable)
```

**Structure Decision**: Single-module, package-by-layer-then-feature. The scaffold already ships one `app` module; the feature is cohesive enough that separate Gradle modules would add build/ownership overhead without payoff (Constitution Principle I favors justified simplicity). `core/` holds cross-cutting device/integration concerns (session, NFC, camera, error mapping, theme); `data`/`domain`/`ui` implement the standard MVVM + UDF split so verification logic is unit-testable without a device.

## Complexity Tracking

> No Constitution violations to justify. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
