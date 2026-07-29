# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SP App (`com.mediplus.spapp`) — a single-Activity Android app where a clinic **operator**
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
./gradlew testDebugUnitTest --tests "com.mediplus.spapp.ui.facecheck.FaceCheckViewModelTest"   # one test class
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
`Switching*` wrappers that pick fake-or-real per call from `DevSettingsStore`. Three things drive
that choice: a master toggle (`DevSettings.fakeEnabled`, default **on**), a per-seam toggle for each
`FakeSeam` (`AUTH`, `DEVICE`, `CARD`, `CAMERA`, `MEMBER`, `FACE`, `ENROLLMENT`, `UPDATE`,
`DIAGNOSTICS`, `DEVICE_STATE` — all default **on**), and the per-step scenario enums (`AuthScenario`,
`CardScenario`, `CameraScenario`, `FaceScenario`, `CurrencyScenario`, …). Every wrapper asks
`DevSettings.isFakeActive(seam)`, which ANDs the master toggle with that seam's own toggle, so the
master switch is a kill switch and the seam toggles let one step run real while the rest stay faked.
Debug builds install a second launcher icon, **"SP App Dev"** (`DevSettingsActivity`), for editing them.

Consequence: the whole journey runs on a bare emulator with no camera and no NFC, using default
settings. Tests for this stack live in `app/src/testDebug/` (not `test/`).

## Conventions worth following

- **No user-facing free text.** `UiMessage` holds only `@StringRes` IDs; `ErrorMapper` is the single
  `AppError → UiMessage` mapping. Server reasons are diagnostic-only and never rendered — this is
  what makes messages non-revealing by construction. All strings live in `res/values/strings.xml`.
- **Never log or persist identity/biometric data.** A captured frame is a `TransientFrame` that is
  zeroed in a `finally` the moment a decision returns or the flow aborts; `FaceFrameDisposalTest` and
  `LoggingRedactionTest` guard this.
- **Only endpoints the contract authenticates get a token.** `AuthInterceptor` attaches the bearer
  token to everything *except* requests marked `@Headers(NO_AUTH_HEADER_LINE)`, which it strips
  before proceeding. That is exactly the endpoints `docs/openapi.json` spells `security: []`:
  `POST /auth/login`, `GET /app/releases/latest` **and, since 2026-07-29,
  `GET /app/releases/{release}/binary`** — the server opened the binary up so a client that has
  missed a required release can update before it is able to sign in. `apkUrl` must still stay
  same-origin, now because the URL is named by the very response we are deciding whether to trust,
  not because a token rides along. On login this is load-bearing rather
  than tidiness: the interceptor reads *any* 401 on a request that carried a token as a session
  loss, so an authenticated sign-in gets 401'd by the back office, is reported to the operator as
  "session expired", and eats the attempt. Add the marker to any new unauthenticated endpoint
  rather than special-casing it in the interceptor.
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

The back-office contract is `docs/openapi.json` — the server's own published spec, and the single
source of truth. The per-endpoint docs under `specs/001-.../contracts/` predate it and now carry a
"superseded" banner: read them for the client-side reasoning, never for wire shapes.

Two of the spec's declared response types are generator artifacts, not contract: it types the
streamed APK download as `object`, and it types three plainly boolean fields (`canVerifyFace`,
`updateRequired`, `updateAvailable`) as `string`. `data/remote/WireCompat.kt` reads booleans
leniently, and the `devices.register` / `members.enrollments.store` responses are read as either a
bare id string or an object. All of it fails towards the safe answer rather than throwing.

### Open decisions from the 2026-07-28 spec realignment

~~1. **The APK download is authenticated, but the update check runs before sign-in.**~~
**Resolved 2026-07-29 by the server**, which took the "exempt the binary" option:
`GET /app/releases/{release}/binary` is now `security: []`, so a forced update before sign-in has
nothing to present and nothing to 401 on. The client marks it `@Headers(NO_AUTH_HEADER_LINE)`.
The same contract revision also **dropped `minSupportedVersionCode`** from the `latest` payload —
`ReleaseDto` defaults it to 0, which makes `CheckForUpdateUseCase`'s floor comparison inert and
leaves the server-computed `updateRequired` as the sole forcing signal. The field and the fallback
are kept deliberately, as the degradation path for a server that omits the verdicts.

One question remains open. It is a product or server call, not a coding one; nothing is blocked on
it, and the current behaviour is the conservative option.

2. **`MemberVerification.capabilities` is carried but not gated on.** `canVerifyFace`/`canEnroll`
   are parsed and available; the journey does not branch on them, because `canVerifyFace` is one of
   the fields the spec mis-types as `string` and a parsing quirk defaulting it to `false` would
   block every card. The server stays the enforcer. Revisit once a live response confirms the type.

## Current state to be aware of

- As of 2026-07-29 detekt is **still red on `main`** — **13** weighted issues, measured against a
  clean `HEAD` worktree, all predating recent work (`LongParameterList` ×4, `LongMethod` ×2,
  `TooManyFunctions` on `UpdateViewModel`, `TooGenericExceptionCaught` ×2, `MaxLineLength` ×2,
  `VerifyFaceUseCase` return count). Check the baseline before assuming your change caused a
  failure; the easiest way is `git worktree add --detach <tmp> HEAD` and run the CLI over that.
- **Self-update ships in-app** (design: `docs/superpowers/specs/2026-07-24-self-update-design.md`):
  launch-time `GET /app/releases/latest?versionCode=N` (unauthenticated, always 200, fail-open),
  SHA-256-verified streaming download, rollback backup of the installed APK to `Downloads/SpApp/` (revert = manual
  uninstall + install the backup), PackageInstaller session install. **Signing landmine:** the Gradle
  wiring is now in place — `app/build.gradle.kts` signs the `release` build type from a git-ignored
  `keystore.properties` (project root: `storeFile`/`storePassword`/`keyAlias`/`keyPassword`) when it
  exists, and falls back to debug signing when it doesn't, so local/CI builds still assemble. **What
  remains is manual:** create the permanent `.jks` (`keytool -genkeypair`, use `-validity 10000` — the
  cert can never rotate for an installed app), write `keystore.properties`, and back the keystore up
  off-machine. Do this before the first field rollout — updates only install over a same-key build, so
  any device that got an earlier debug-signed build needs a one-time manual reinstall to cross over.
  `apkUrl` must stay same-origin with `BASE_URL` — `CheckForUpdateUseCase` *enforces* that (a build
  on any other host is refused, https or not, so the response naming the URL cannot also choose the
  host it comes from); every release must bump `versionCode`.
- **The APK download resumes** (2026-07-29). An interrupted transfer keeps what it wrote and the
  next attempt sends `Range: bytes=N-`, re-digesting the prefix off disk before appending. It is
  opportunistic and self-verifying: `206` appends, `200` means the server ignored the range so the
  prefix is truncated and the transfer restarts, `416` retries from zero inside the same call. None
  of it is trusted — the SHA-256 over the finished file is what decides, and a failed digest deletes
  the file so a bad prefix can never loop. `Accept-Encoding` is pinned to `identity` on the download
  because OkHttp's transparent gzip would desynchronise every offset. The transfer mechanics live in
  `data/repository/ApkTransfer.kt`, apart from the repository's response-classification job.
  Consequently `clearDownloads()` is now `pruneObsoleteDownloads()` and deletes only builds at or
  below the running one; discarding partials for *other* pending builds happens in
  `downloadAndVerify`, the only place that knows which build is being fetched. A dropped download
  reports `TransientKind.DOWNLOAD_INTERRUPTED` ("Download interrupted"), **not** `AppResult.Timeout`
  — nothing about a dropped download is ambiguous, so the old "Outcome not confirmed / re-check
  before retrying" was actively misleading. The message deliberately does not promise resuming,
  since that only happens when the server answers `206` and a `UiMessage` has no format arguments.
- **Device registration** (`POST /devices/register`): a client-generated `installId` UUID is minted
  and persisted once by `PrefsDataStore`, `SignInViewModel` registers best-effort right after a
  successful sign-in, and `DeviceIdInterceptor` attaches the returned id as `X-Device-Id` on every
  later call. Diagnostics requires it; everywhere else it is audit trail. Nothing here reads a
  hardware identifier, and a failure never blocks the journey.
- **Device diagnostics telemetry** (design: `docs/superpowers/specs/2026-07-24-device-diagnostics-telemetry-design.md`):
  poll-then-report. `DiagnosticsPoller` (a `ProcessLifecycleOwner` observer) polls
  `GET /diagnostics/requests/pending` on login + every 15 min while foregrounded; on a fresh
  `request.id` it collects a **permission-free** `DeviceStateSnapshot`
  (battery/network/storage/memory/display/build/app/locale/thermal/uptime — no hardware IDs, no
  location) and POSTs it to `/diagnostics/requests/{id}/report`, deduping on the last-handled id.
  Best-effort throughout (all failures swallowed; no `UiMessage`, no screen).
- **Session revalidation on resume** (design: `docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md`):
  `SessionRevalidator` (a second `ProcessLifecycleOwner` observer, bound beside `DiagnosticsPoller` in
  `SpApp.onCreate()`) calls `GET /auth/session` on every foregrounding, but only when
  `sessionState == Active`. `AuthRepository.revalidateSession()` returns `SessionCheck.Valid`/`Ended`/`Unknown`
  and **never mutates session state** — a 401 is acted on by `AuthInterceptor`, which stays the single
  owner of that rule, and everything else (5xx, unexpected status, `IOException`, timeout) is `Unknown`
  and leaves the session untouched. Fail-open is deliberate: a flaky clinic connection must never force
  a re-login. This is **not** session persistence — the token still lives only in `InMemorySessionManager`,
  so process death still ends the session. No new screen, string or `UiMessage`; the operator just reaches
  the existing "session ended" notice on resume instead of three patient-facing steps later.
- **The member card is read by UID, not NDEF — verified on-device 2026-07-29** (Sunmi V3, real card,
  whole fake stack off). The clinic's card stock is MIFARE Classic 1K carrying **no NDEF message**:
  `techList` is `NfcA, MifareClassic` only, sectors 0–4 are factory-blank under the default key, and
  sectors 5–15 are locked with proprietary keys belonging to some other system. So `Ndef.get(tag)`
  was always null and every tap failed as `CARD_UNREADABLE`. `NdefMemberCardReader` was **deleted**;
  `UidMemberCardReader` + `CardUid` now decode the tag UID as an unsigned big-endian decimal
  (`25 D5 6B C9` → `634743753`), which already satisfies the server's `^[0-9]{7,32}$`. The reader
  sets `FLAG_READER_SKIP_NDEF_CHECK` deliberately: on this stock the platform NDEF check costs
  ~400ms and drops the tag (`Check NDEF Failed - status = 3`, then "Tag lost, restarting polling
  loop"). Note a UID is clonable and is not an authenticator — the card only *selects* a member, and
  the back office plus the live face check remain what actually verify identity.
- Device-gated and still unverified: non-happy-path camera scenarios, a comma-decimal locale
  (`en-ZA`) pass over the amount keypad, the instrumented tests, LeakCanary clean-run, the
  performance numbers in `docs/PERFORMANCE_AND_LEAKS.md`, and the real `AndroidDeviceDiagnostics`
  reader against real hardware sensors (battery/thermal/network transitions).
- **`SessionRevalidator.bind()` is verified on-device as of 2026-07-29** (emulator, whole fake stack
  off, real back office). Both halves of the guard hold: signed out, a background→foreground cycle
  makes *no* HTTP call at all; signed in, three consecutive cycles each fired exactly one
  `GET /auth/session` → 200. Re-running it is manual — there is no Robolectric here (same precedent
  as `DiagnosticsPoller.bind()`), and the default fake-`AUTH` settings can't tell a firing
  revalidator from a silent one, so the check needs the `AUTH` seam turned OFF in Dev Settings.
- On login, `DiagnosticsPoller`'s first poll races `POST /devices/register` and loses:
  `GET /diagnostics/requests/pending` returns **403** because `X-Device-Id` isn't set yet. Harmless
  today — diagnostics is best-effort and every later poll (which is device-registered) returns 200 —
  but it means the login-time poll is effectively always wasted on a device's first-ever sign-in.
- Driving the emulator headlessly: `adb exec-out screencap -p > file.png` **corrupts the PNG** under
  PowerShell — use `adb shell screencap -p /sdcard/x.png` then `adb pull`. Git Bash mangles
  `/sdcard/...` paths, so run adb from PowerShell.
- Installing by hand needs `adb install -r -t`: AGP marks `intermediates/apk/debug/app-debug.apk`
  **testOnly**, so without `-t` it fails `INSTALL_FAILED_TEST_ONLY` (Android Studio passes the flag
  for you). `adb` is not on PATH — it lives at `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.
  Both launcher icons match `category.LAUNCHER`, so `monkey -p …` may open **Dev Settings** instead
  of the app; start the journey explicitly with `am start -n com.mediplus.spapp/.MainActivity`.
  That form creates a task whose base intent carries no `MAIN`/`LAUNCHER`, which Android then can't
  match against a launcher tap — before `MainActivity` was made `singleTask` (2026-07-29) every
  later tap on the icon stacked a *new* `MainActivity` on the same task, and since the process (and
  the in-memory session) survived, the operator was silently returned to sign-in mid-journey with a
  live session. To reproduce field launch behaviour exactly, add `-a android.intent.action.MAIN
  -c android.intent.category.LAUNCHER`.
  A `Failure calling service package: Broken pipe (32)` install error means the emulator died
  mid-install, not that anything is wrong with the APK.
