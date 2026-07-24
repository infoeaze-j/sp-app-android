# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

FaceVerify (`com.mediplus.faceverify`) — a single-Activity Android app where a clinic **operator**
walks a **patient** through a gated verification journey: sign in → tap member card (NFC) → consent
→ live face check → add a service. Every authoritative decision is made by the back office; the app
never decides identity locally.

The governing document is `.specify/memory/constitution.md` (four principles: code quality, test-first,
UX consistency, performance). It explicitly supersedes this file where they conflict.

## Commands

`JAVA_HOME` is not set on this machine — Gradle needs the Android Studio JBR:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

```bash
./gradlew assembleDebug                  # build
./gradlew lintDebug                      # Android Lint — the in-build static-analysis gate (abortOnError=true)
./gradlew testDebugUnitTest              # JVM unit suite (~200 tests)
./gradlew testDebugUnitTest --tests "com.mediplus.faceverify.ui.facecheck.FaceCheckViewModelTest"   # one test class
./gradlew testDebugUnitTest --tests "*.FaceCheckViewModelTest.consent withheld halts"               # one test
./gradlew createDebugUnitTestCoverageReport                # coverage → app/build/reports/coverage/
./gradlew connectedDebugAndroidTest      # instrumented (camera/NFC/nav) — needs a device or emulator
```

**detekt is not a Gradle task here** — the plugin is deliberately unwired (Gradle 9.4 compat risk).
CI downloads the 1.23.7 CLI and runs it over `app/src/main/java` with `config/detekt/detekt.yml`
(`maxIssues: 0`, `warningsAsErrors`). Run it the same way locally before claiming detekt is clean;
`./gradlew detekt` will just fail with "task not found". Note the config enforces the constitution
numerically: functions ≤ 50 lines, line length ≤ 120, `ReturnCount` ≤ 4, no bare `TODO`/`FIXME`.

Toolchain gotchas (AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.3.10): AGP 9 has **built-in Kotlin** — do not
add `org.jetbrains.kotlin.android`, and put `jvmTarget` inside `android { kotlin { compilerOptions } }`.
KSP uses the unified version scheme. Hilt must stay ≥ 2.60. `compileSdk 37` (minor 37.1) while
`targetSdk` stays 36.

## Architecture

Strict one-way layering; each layer has a seam the layer above cannot see through.

```
ui/<feature>/  XRoute + XScreen (Compose) + XViewModel (@HiltViewModel, StateFlow<XUiState>)
domain/usecase/  business rules, pure-ish, `operator fun invoke`
data/repository/ AppResult mapping, feeds SessionManager
data/remote/     Retrofit APIs + wire DTOs (never leave this package)
core/            session, result, network, camera, nfc, time, di, ui/theme
```

**`AppResult<T>` is the universal outcome type.** Four variants — `Success`, `BusinessRejection`,
`TransientFailure`, `Timeout` — so no path can silently report success and `Timeout` can never be
mistaken for one. Everything crossing the network goes through `core/network/ApiCall.kt::apiCall`,
which runs off-main and classifies transport failures. Repositories interpret HTTP codes per endpoint.

**`SessionManager` (in-memory singleton) owns all session-bound state**, including `VerifiedIdentity`
and the back-office-supplied freshness window. Any session loss calls `clearAll()`, which wipes
verification state too — the patient must be fully re-verified after re-login. Nothing biometric
is ever stored. A null freshness window is treated as *immediately stale* (fail-safe), not as
"no expiry".

**The journey is gated, not merely navigated.** `JourneyGate.furthestReachable(...)` is pure logic
over primitive facts, producing a `JourneyStep`; `AppRoute` maps each destination to its required
step. `NavGraph` additionally runs a global guard: any `sessionState != Active` pops the whole back
stack to sign-in.

**`NavGraph` owns the app's only chrome** — the `Scaffold` + `TopAppBar` carrying log out. Its
`innerPadding` is what gives every screen its window insets, so screens must **not** apply their own
`windowInsetsPadding`. Log out is never disabled; it must work mid-capture and mid-request.

**Device hardware is contained behind interfaces so no `androidx.camera` or `android.nfc` type
reaches a ViewModel**: `FaceCamera` (+ `FaceCameraFactory`, resolved by the *screen* via a Hilt
`@EntryPoint`, because the ViewModel never drives the camera) and `MemberCardReader`. Note the
asymmetry — NFC uses a switching *decorator* (its methods suspend, so it can re-read the dev store
per call); the camera uses a *factory* (`createPreviewView`/`bind` are synchronous, so the choice is
made once per screen entry).

## Debug vs release: the fake stack

There are no product flavors. The **`debug` and `release` source sets each define their own
`RepositoryModule`, `CameraModule`, and `NfcModule`** — release binds the real impls, debug binds
`Switching*` wrappers that pick fake-or-real per call from `DevSettingsStore`. A single master
toggle (`DevSettings.fakeEnabled`, default **on**) plus per-step scenario enums (`AuthScenario`,
`CardScenario`, `CameraScenario`, `FaceScenario`, `CurrencyScenario`, …) drive it. Debug builds
install a second launcher icon, **"FaceVerify Dev"** (`DevSettingsActivity`), for editing them.

Consequence: the whole journey runs on a bare emulator with no camera and no NFC, using default
settings. Tests for this stack live in `app/src/testDebug/` (not `test/`).

## Conventions worth following

- **No user-facing free text.** `UiMessage` holds only `@StringRes` IDs; `ErrorMapper` is the single
  `AppError → UiMessage` mapping. Server reasons are diagnostic-only and never rendered — this is
  what makes messages non-revealing by construction. All strings live in `res/values/strings.xml`.
- **Never log or persist identity/biometric data.** A captured frame is a `TransientFrame` that is
  zeroed in a `finally` the moment a decision returns or the flow aborts; `FaceFrameDisposalTest` and
  `LoggingRedactionTest` guard this.
- **Dispatchers are injected**, never referenced directly — `@IoDispatcher`, `@DefaultDispatcher`,
  `@MainDispatcher` from `DispatchersModule`. Unit tests use `MainDispatcherRule`.
- **Every flow state is explicit.** ViewModels expose a sealed `…Phase` interface covering loading,
  success, empty, error, permission-denied, and terminal halts. Add a variant rather than overloading
  an existing one.
- **Design tokens only** — `LocalSpacing` (incl. `minTouchTarget = 48.dp`), theme typography/colors.
  No hardcoded dp or colors.
- Test-first is non-negotiable per the constitution, with ≥ 80% coverage on changed code and explicit
  success *and* denial-path tests. Stack: JUnit4 + MockK + Turbine + MockWebServer.

## Spec-driven workflow

Feature work runs through Spec Kit (`.claude/skills/speckit-*`, templates in `.specify/`). Artifacts
for the shipped feature live in `specs/001-identity-verification-enrollment/` — `spec.md`, `plan.md`,
`tasks.md`, `contracts/`, `quickstart.md` (the end-to-end validation script). Requirement IDs
(`FR-0xx`) are referenced directly in KDoc throughout the code; keep that linkage when editing.

Smaller changes use the Superpowers brainstorm→design→plan flow, with artifacts in
`docs/superpowers/specs/` and `docs/superpowers/plans/` dated by day.

The back-office contract is `docs/openapi.yaml`. `POST /members/verify` is a **placeholder invented
app-side** — reconcile it when the server publishes its real shape.

## Current state to be aware of

- The **backend has not shipped the amount/currency half**. Until the services response includes a
  `currencies` array, it parses to `emptyList()` and every operator is blocked at load with
  `AddServicePhase.Unavailable(UnavailableReason.NO_CURRENCY)`. Correct fail-safe — but this build must not reach a
  real device before the server change.
- As of 2026-07-24 detekt is **still red on `main`** (48 weighted issues, all predating recent work:
  `Color.kt` magic numbers, `NfcModels` naming, line length, `VerifyFaceUseCase` return count). Check
  the baseline before assuming your change caused a failure.
- **Self-update ships in-app** (design: `docs/superpowers/specs/2026-07-24-self-update-design.md`):
  launch-time check of the placeholder `GET /app/version` (fail-open), SHA-256-verified streaming
  download, rollback backup of the installed APK to `Downloads/FaceVerify/` (revert = manual
  uninstall + install the backup), PackageInstaller session install. **Signing landmine:** release
  builds still use default debug signing, and updates only install over a same-key build — the
  permanent release keystore must exist before the first field rollout, or every fleet device needs
  a manual reinstall. `apkUrl` must stay same-origin with `BASE_URL` (the bearer token rides on
  every request); every release must bump `versionCode`.
- Device-gated and still unverified: `NdefMemberCardReader` against real card stock, non-happy-path
  camera scenarios, a comma-decimal locale (`en-ZA`) pass over the amount keypad, the instrumented
  tests, LeakCanary clean-run, and the performance numbers in `docs/PERFORMANCE_AND_LEAKS.md`.
- Driving the emulator headlessly: `adb exec-out screencap -p > file.png` **corrupts the PNG** under
  PowerShell — use `adb shell screencap -p /sdcard/x.png` then `adb pull`. Git Bash mangles
  `/sdcard/...` paths, so run adb from PowerShell.
