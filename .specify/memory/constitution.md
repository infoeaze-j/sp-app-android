<!--
Sync Impact Report
==================
Version change: (template/unversioned) → 1.0.0
Bump rationale: Initial ratification. First concrete constitution replacing the
  unfilled template; establishes the four founding principles and supporting
  sections. Semantic version starts at 1.0.0 (MAJOR baseline).

Modified principles:
  - [PRINCIPLE_1_NAME] → I. Code Quality & Maintainability
  - [PRINCIPLE_2_NAME] → II. Testing Standards (NON-NEGOTIABLE)
  - [PRINCIPLE_3_NAME] → III. User Experience Consistency
  - [PRINCIPLE_4_NAME] → IV. Performance Requirements
  - [PRINCIPLE_5_NAME] → (removed; consolidated into four requested principles)

Added sections:
  - Quality Standards & Constraints (was [SECTION_2_NAME])
  - Development Workflow & Quality Gates (was [SECTION_3_NAME])

Removed sections:
  - Fifth placeholder principle slot (user requested exactly four focus areas)

Templates requiring updates:
  - ✅ .specify/templates/plan-template.md (Constitution Check gate is
       constitution-driven; no hardcoded principle names — no edit needed)
  - ✅ .specify/templates/spec-template.md (no principle-specific references)
  - ✅ .specify/templates/tasks-template.md (task categories already cover
       testing/quality/performance; no edit needed)
  - ✅ .claude/skills/speckit-*/SKILL.md (generic guidance; no stale
       agent-specific references)

Follow-up TODOs: none. RATIFICATION_DATE set to project inception (2026-07-20).
-->

# FaceVerify Constitution

## Core Principles

### I. Code Quality & Maintainability

Code MUST be readable, consistent, and safe to change before it is considered done.

- All Kotlin/Android code MUST follow the official Kotlin coding conventions and
  Android Studio's default formatting; formatting is enforced automatically, not by
  reviewer opinion.
- Public classes, functions, and modules MUST have a single, clear responsibility.
  Files exceeding ~400 lines or functions exceeding ~50 lines MUST be justified in
  review or refactored.
- No `TODO`/`FIXME` without an associated tracked issue reference. Dead code,
  commented-out blocks, and unused resources MUST NOT be merged.
- Warnings MUST be treated as defects: the build MUST compile clean, and lint
  (Android Lint + `ktlint`/`detekt` where configured) MUST pass with no new issues.
- Every non-trivial change MUST be reviewed by at least one other person; the author
  MUST NOT approve their own merge.

**Rationale**: FaceVerify handles identity verification in a medical context
(`com.mediplus.faceverify`). Ambiguous or sprawling code is where correctness and
privacy bugs hide; enforcing clarity mechanically keeps the security-sensitive core
auditable.

### II. Testing Standards (NON-NEGOTIABLE)

Behavior MUST be protected by automated tests, and tests MUST be written to fail
before the code that makes them pass exists.

- Test-first is mandatory: for every new feature or bug fix, write a failing test,
  confirm it fails for the right reason, then implement until it passes
  (Red-Green-Refactor).
- Layered coverage is required: fast unit tests (`app/src/test`) for logic and
  view-models; instrumented/integration tests (`app/src/androidTest`) for
  camera/permission flows, on-device inference, and screen navigation.
- Every bug fix MUST add a regression test that reproduces the original failure.
- Line coverage on changed code MUST be ≥ 80%; verification, liveness, and
  permission-handling logic MUST have explicit tests for both success and denial
  paths.
- The full test suite MUST pass on CI before merge. Flaky tests MUST be fixed or
  quarantined with a tracked issue — never ignored silently.

**Rationale**: A false accept or a crashed verification flow has real-world
consequences here. Tests are the contract that the verification pipeline still
behaves as specified after every change.

### III. User Experience Consistency

The app MUST feel like one coherent product, and verification flows MUST behave
predictably for every user.

- UI MUST use a shared design system: centralized theme, typography, spacing, and
  color tokens (Material 3 / Compose theme or equivalent). Ad-hoc hardcoded colors,
  dimensions, or strings MUST NOT be introduced.
- All user-facing text MUST come from string resources to support localization and
  consistent tone; no hardcoded display strings.
- Every state of a flow — loading, success, empty, error, and permission-denied —
  MUST be explicitly designed and handled. The user MUST always know what happened
  and what to do next.
- Interactive elements MUST meet accessibility baselines: minimum 48dp touch
  targets, content descriptions for non-text controls, and support for dynamic font
  scaling and TalkBack.
- Camera, permission, and error messaging MUST be consistent in wording and
  behavior across every screen that performs verification.

**Rationale**: Users trust a verification app partly through its polish and
predictability. Inconsistent or dead-end states erode confidence exactly where
confidence matters most.

### IV. Performance Requirements

The app MUST be responsive on the minimum supported hardware (minSdk 24), not just
on flagship devices.

- The UI thread MUST remain unblocked: face detection, model inference, and I/O MUST
  run off the main thread. No frame-time budget over 16ms (60fps target) caused by
  app code during an active verification flow.
- Cold start to interactive MUST be under 2 seconds on a mid-range reference device;
  a single verification attempt (capture → result) MUST complete within a stated,
  measured budget documented in the feature's plan.
- Memory MUST be bounded: camera frames and bitmaps MUST be recycled/closed
  promptly; no leaked `Activity`/`Context`, camera, or executor references. Leak
  detection (e.g., LeakCanary in debug) MUST report clean for verification screens.
- Performance-sensitive changes MUST include a before/after measurement (frame
  timing, latency, or memory), not just a claim.
- APK size and resource additions MUST be justified; large assets/models MUST be
  reviewed for on-device impact.

**Rationale**: Verification happens in real time with a live camera. Jank, slow
starts, or memory pressure directly break the core interaction and disproportionately
hurt the low-end devices FaceVerify commits to supporting.

## Quality Standards & Constraints

- **Platform**: Android, Kotlin-first. `minSdk 24`, `targetSdk 36`. New code MUST NOT
  reduce the supported range without an amendment.
- **Tooling gates**: build, Android Lint, static analysis (`detekt`/`ktlint` where
  configured), and the full test suite are required status checks. A red gate blocks
  merge.
- **Dependencies**: new third-party libraries MUST be justified (need, size, license,
  maintenance) in review. Prefer AndroidX/first-party solutions over unmaintained
  dependencies.
- **Security & privacy**: because this app processes biometric/identity data,
  sensitive data MUST NOT be logged; permissions MUST be requested with rationale and
  degrade gracefully on denial. Any change touching data capture or storage MUST be
  called out explicitly in the PR description.

## Development Workflow & Quality Gates

- **Branch → PR → review**: all work lands via pull request. At least one reviewer
  MUST approve; the reviewer MUST confirm the four principles are satisfied.
- **Definition of done** for any change: tests written first and passing, gates
  green, UX states handled, and performance impact considered (measured when
  performance-sensitive).
- **Constitution as gate**: the `/speckit-plan` Constitution Check MUST evaluate the
  feature against Principles I–IV. Violations MUST be resolved or recorded as an
  explicit, justified exception in the plan's Complexity Tracking before
  implementation begins.
- **Exceptions**: any deviation from a MUST rule requires written justification in the
  PR and reviewer sign-off. Repeated exceptions in the same area are a signal to
  amend this constitution rather than keep bypassing it.

## Governance

This constitution supersedes ad-hoc practices and default team conventions. When a
guideline elsewhere conflicts with this document, this document wins.

- **Amendments**: proposed via PR that edits this file, states the rationale, and
  updates the version and Sync Impact Report. Amendments require reviewer approval
  before merge.
- **Versioning policy** (semantic):
  - **MAJOR**: backward-incompatible governance changes, or removal/redefinition of a
    principle.
  - **MINOR**: a new principle or section, or materially expanded mandatory guidance.
  - **PATCH**: clarifications, wording, and non-semantic refinements.
- **Compliance review**: every PR and plan MUST verify compliance with Principles
  I–IV. Complexity or deviations MUST be justified, not assumed. Persistent
  non-compliance MUST trigger either remediation or a proposed amendment.
- **Runtime guidance**: agent- and contributor-facing guidance (e.g., project
  `CLAUDE.md`, README, and Spec Kit skill files) MUST stay consistent with this
  constitution; conflicts are resolved in favor of this document.

**Version**: 1.0.0 | **Ratified**: 2026-07-20 | **Last Amended**: 2026-07-20
