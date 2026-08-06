# Sentry crash reporting — hardening the wizard's output

**Date:** 2026-08-06
**Status:** Designed — not yet implemented
**Depends on:** the Sentry Android SDK 8.51.0 + `io.sentry.android.gradle` plugin, added by
`sentry-wizard` on 2026-08-06 and left unreviewed in the working tree
**Related:** `LoggingRedactionTest`, `FaceFrameDisposalTest`, `core/di/NetworkModule.kt`

## Problem

The app had no crash reporting of any kind. `DiagnosticsPoller` is *pull*-based — the back office
asks, the device answers — so a device that crashes on launch, ANRs on a slow tap, or dies inside a
PackageInstaller session reports nothing, ever. The app is not distributed through Play, so Play
Console vitals backfills nothing either. Across a fleet of hundreds of clinic devices that is total
blindness.

This compounds a deliberate design choice. `UiMessage` is non-revealing by construction and server
reasons are never rendered, so the operator on the phone to support genuinely *cannot* say what
failed. Something has to carry the diagnostic off the device, and until now nothing did.

Sentry (free-tier SaaS, project `infoeaze/android`) was introduced with `sentry-wizard`. Its raw
output transmits correctly — verified on-device 2026-08-06, event
`f08d42122d6542d880670fcf3af8f67d` accepted, `release: com.mediplus.spapp@1.5+5`, `dist: 5` — but
its defaults are wrong for this app in three ship-blocking ways and several smaller ones.

## What the wizard got wrong

Measured from the runtime SDK log on an emulator, not inferred from the source.

1. **A quota bomb.** The wizard's test snippet `throw`s inside an `OnGlobalLayoutListener` that is
   never unregistered, capturing an exception on *every layout pass*. Against a 5,000-errors/month
   free tier, one device exhausts the entire fleet's monthly budget in minutes. Deleted on discovery;
   deliberately not restored.

2. **Unmasked screenshots on every event.** `attach-screenshot: true` with
   `screenshot.mask-all-text: false` and `screenshot.mask-all-images: false`. The captured envelope
   confirms a `Screenshot` attachment was built and transmitted. A crash on the face-check screen
   would ship **the patient's face** to a third-party SaaS; on the member screens, their name and
   number. This voids the `TransientFrame` zeroing discipline outright — zeroing the buffer is
   pointless if a bitmap of the same frame is uploaded.

3. **Request URLs carrying member numbers.** Runtime reports
   `gradle-plugin-integrations: AppStartInstrumentation, ComposeInstrumentation,
   DatabaseInstrumentation, FileIOInstrumentation, LogcatInstrumentation, OkHttpInstrumentation,
   SourceContext`. The endpoints `members/{memberNumber}/services` and
   `members/{memberNumber}/enrollments` put the decoded card UID in the path, and OkHttp
   instrumentation records full URLs as breadcrumbs and spans.

   Note what makes this worse than it looks: `NetworkModule.kt:48` sets `HttpLoggingInterceptor` to
   `Level.NONE` in release precisely so URLs never reach logcat in the field. Sentry's
   instrumentation is bytecode-woven into OkHttp itself and **bypasses that policy entirely**.

4. **`LogcatInstrumentation` turns every `Log.*` call into a breadcrumb** — including ML Kit's,
   CameraX's and OkHttp's. `LoggingRedactionTest` pins *our* call sites; it cannot pin theirs.

5. **`environment: production` on a debug build** (the SDK default when unset). Dev churn and the
   fake stack's deliberate failures would be indistinguishable from field crashes, and would draw on
   the same 5,000/month budget.

6. **`traces.sample-rate: 1.0` plus user-interaction tracing** — 100% transaction sampling with a
   transaction per click, across hundreds of devices.

7. **`includeSourceContext: true`** uploads the full source to Sentry SaaS and requires the
   git-ignored `sentry.properties`, making it a *second* single-machine secret gating every shippable
   release APK alongside the keystore — the exact fragility `CLAUDE.md` already flags. Since
   `optimization { enable = false }` means release is not minified, stack traces already carry real
   file names and line numbers, so source context buys only inline snippets in the UI.

8. Minor: plugin declared as `id("io.sentry.android.gradle") version "6.17.0"` rather than
   `alias(libs.plugins.…)` like every other plugin here; mangled `</application>` indentation; the
   wizard's `catch (e: Exception)` would have been a 15th detekt violation.

Free-tier arithmetic worth internalising: 5,000 errors/month across ~300 devices is ~16 errors per
device per month. That budget only works with screenshots off, tracing off, and dev noise excluded.

## Decisions taken

Three were the user's call, made 2026-08-06:

- **Fail-closed allowlist**, not a denylist. Nothing reaches Sentry unless explicitly permitted, so a
  future SDK upgrade or a newly added endpoint leaks nothing until someone opts it in. Consistent
  with how a null freshness window is treated as immediately stale.
- **Debug builds off by default**, behind a new Dev Settings toggle, rather than hard-off or
  environment-tagged. Keeps dev work off the quota while leaving delivery verifiable on-device
  without editing the manifest.
- **`includeSourceContext = false`.** Traces are already readable; not worth a second release-gating
  secret or putting health-app source on third-party SaaS.

And one architectural choice: **disable auto-init and configure in code.** `beforeSend` and
`beforeBreadcrumb` are only settable programmatically, and screenshots must be *never captured*
rather than discarded post-capture — an `EventProcessor` bolted onto auto-init can only drop the
bitmap after it exists. The cost is losing the SDK's ContentProvider-stage init, a window of tens of
milliseconds before `Application.onCreate`, during which a crash in Hilt's own startup would go
unreported. Accepted deliberately: the alternative is uploading patient faces.

## Architecture

A new `core/crash/` package, following the interface-plus-impl seam idiom already used by
`FaceCamera`/`CameraXFaceCamera`:

```
core/crash/
  CrashReporting.kt        interface { fun init() }          ← what SpApp calls
  SentryCrashReporting.kt  implements it; owns the options block
  SentryGate.kt            interface { fun isEnabled(): Boolean }
  EventScrubber.kt         pure functions over sentry-java types; no Android dependency

core/di/CrashModule.kt          (main)     CrashReporting → SentryCrashReporting
core/di/SentryGateModule.kt     (release)  SentryGate → AlwaysOnSentryGate
core/di/SentryGateModule.kt     (debug)    SentryGate → DevSettingsSentryGate
```

That debug/release module pair is a sixth entry in the existing split beside `RepositoryModule`,
`CameraModule`, `NfcModule`, `DiagnosticsModule` and `UpdateModule` — the same shape, no new pattern.

`SpApp.onCreate()` gains one line beside the existing `DiagnosticsPoller` and `SessionRevalidator`
binds. `environment` comes from a new `buildConfigField` per build type (`"development"` /
`"production"`), mirroring how `BASE_URL` is already handled. The DSN stays in the manifest: manifest
metadata is still read during a manual `SentryAndroid.init`, and a DSN is not a secret — it ships
inside every APK regardless.

### The gate is synchronous, the store is not

`beforeSend` is a synchronous callback; `DevSettingsStore` reads suspend. `DevSettingsSentryGate`
therefore holds a `@Volatile` snapshot updated by a collector on the store's flow, started at init,
**defaulting to `false` until the first emission**. A debug build that crashes before the store is
read sends nothing. Failure points one way.

## The scrub contract

```kotlin
fun scrubEvent(event: SentryEvent, hint: Hint): SentryEvent?
fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb?
fun templatePath(url: String): String
```

**Breadcrumbs — allowlist by category.** Kept: `navigation`, `app.lifecycle`, `ui.lifecycle`,
`network.event` (bandwidth and wifi-vs-cellular; useful for a flaky clinic connection, carries no
identity), and `http` after templating. Everything else is dropped — notably `logcat`, and `ui.click`
because a clicked element's text could be a member's name.

`navigation` is safe to keep: `AppRoute` is a path enum carrying nothing but its path, no destination
takes arguments, and the captured envelope shows `{"from": "/signin", "to": "/signin"}`.

**URL templating.** Any path segment that is *entirely* a run of 4+ digits becomes `{id}`; the query
string is dropped whole. The threshold is 4 here and 7 for messages (below) because a wholly-numeric
path segment is an identifier essentially by definition, whereas a message is prose that may contain
incidental small numbers.

```
https://bio.infoeaze.com/api/v1/members/634743753/services?x=1
  → https://bio.infoeaze.com/api/v1/members/{id}/services
```

Scheme, host and endpoint shape survive — enough to know which call failed — while the card UID does
not. A digit-run rule rather than a fixed list of the four templated endpoints, so an endpoint added
later is covered without anyone remembering to update the scrubber.

**Event level.** `request` dropped entirely (data, headers, cookies, query). `contexts` kept as-is,
since device/OS/app/locale are metadata by construction. `extras` allowlisted.
`hint.clearAttachments()` as a backstop even though screenshots are off at init.

`user` is reduced to `id` and nothing else. Note the same synchronicity problem as the gate:
`installId` lives in `PrefsDataStore`, so it cannot be read inside a synchronous `beforeSend`. It is
therefore written **once onto Sentry's global scope** by a coroutine started at init, after the store
read completes; `beforeSend` only ever *strips* the other user fields rather than populating the id
itself. Events raised before that read completes simply carry no user — the same fail-open-to-less-data
direction as everything else here. `installId` is the client-generated UUID already sent to the back
office: it identifies a *device* for fleet triage and never a patient.

**Message redaction.** The digit-run redaction also runs over exception and event messages, because a
parse failure could interpolate a raw member number even though `MemberNumber.toString()` guards the
typed path. Accepted cost: a legitimate 7+ digit number in a message — a byte count, an epoch
millis — is redacted too.

### Options at init

| Setting | Value | Why |
|---|---|---|
| `isAttachScreenshot` | `false` | patient faces, member names |
| `isAttachViewHierarchy` | `false` | low value for Compose, non-zero leak |
| `isEnableUserInteractionBreadcrumbs` | `false` | click text can carry names |
| `isEnableUserInteractionTracing` | `false` | pure quota burn |
| `tracesSampleRate` | `0.0` | the free tier's value is errors, not spans |
| `isAnrEnabled` | `true` | keep — it is why we wanted this |
| `isEnableUncaughtExceptionHandler` | `true` | keep |
| `isSendDefaultPii` | `false` | explicit, not merely defaulted |
| `isEnableAutoSessionTracking` | `true` | release health; no PII, no error quota |
| `isSendModules` | `false` | dependency inventory, unnecessary |
| `environment` | `BuildConfig.SENTRY_ENVIRONMENT` | dev vs field separation |
| `release` / `dist` | SDK auto-derived | verified correct as `com.mediplus.spapp@1.5+5` / `5` |

In Gradle: `includeSourceContext = false`, the plugin moved to `alias(libs.plugins.sentry)` via the
version catalog, and the instrumentation feature set narrowed to OkHttp only —

```kotlin
tracingInstrumentation {
    features.set(setOf(InstrumentationFeature.OKHTTP))
}
```

— which stops Logcat, file-IO, database and Compose instrumentation from weaving in at all, rather
than relying on the scrubber to discard what they produce.

Worth recording because it looks like a contradiction and someone will otherwise "fix" it: keeping
OkHttp instrumentation while `tracesSampleRate = 0.0` is deliberate and does work. The integration
adds HTTP **breadcrumbs** through its event listener independently of trace sampling, so we keep the
"which call failed" signal while emitting no spans and consuming no performance quota.

## Error handling

Crash reporting must never be the thing that crashes the app. `init()` is best-effort in the same
spirit as `DiagnosticsPoller`: a malformed DSN or failed init is swallowed and the journey proceeds.
`IllegalArgumentException`/`IllegalStateException` are caught specifically rather than `Throwable`,
so this adds no new `TooGenericExceptionCaught` to the detekt tally.

The scrubber never throws. A malformed URL that breaks templating returns `null` and the breadcrumb
is **dropped**, not passed through; `runCatching { … }.getOrNull()` achieves that without tripping
detekt. Every failure path points the same way: nothing sent.

## Testing

Written first, per constitution Principle II.

`EventScrubberTest` — `app/src/test/`, pure JVM, no Android:

- a member number in an `http` breadcrumb URL comes out as `members/{id}/services`
- query strings are dropped
- `logcat` and `ui.click` breadcrumbs are dropped; `navigation` and `network.event` survive
- **an unrecognised category is dropped** — the test that makes the fail-closed posture real rather
  than aspirational, and what will catch a future SDK upgrade introducing a new breadcrumb type
- a 7+ digit run in an exception message is redacted
- `request` is dropped, `user` is reduced to `installId`, attachments are cleared
- a malformed URL drops the breadcrumb instead of leaking it
- a golden-payload case: a `SentryEvent` shaped like the envelope actually captured on-device on
  2026-08-06 — same breadcrumb set, same contexts — asserting nothing sensitive survives

`SentryGateTest` — `app/src/testDebug/`, where the dev-stack tests live: the gate reads `false`
before the store's first emission and `true` only after the toggle flips. The default-false
assertion is the important one.

## Acceptance

- The full JVM suite passes (438 tests before this work).
- The detekt CLI reports no new rows against the documented 14-row baseline, measured over a
  detached worktree at `HEAD` for comparison.
- `lintDebug` clean.
- **On-device, fake seams OFF so real HTTP occurs**, with `io.sentry.debug=true` and one temporary
  controlled capture: the envelope shows `members/{id}/…` templated, no `Screenshot` attachment, and
  `environment: development` with the Dev Settings toggle on. This closes the one gap in the
  2026-08-06 test, where the fake stack meant the OkHttp URL exposure was confirmed by configuration
  rather than by observation.
- `CLAUDE.md` updated: conventions (the allowlist and where it lives) and current state.

## Out of scope

- Self-hosting Sentry. Free-tier SaaS is the decided starting point; the scrubber is what makes that
  acceptable, and it does not change if the backend moves later.
- Client-side rate limiting beyond `tracesSampleRate = 0.0`. Sentry's server-side spike protection is
  the backstop; the wizard's per-layout capture was the actual risk and it is gone.
- Reporting handled `AppResult` failures as Sentry events. Those are expected outcomes, not defects,
  and would swamp the quota. `TransientKind` telemetry belongs in the diagnostics feature if wanted.
- Re-enabling performance tracing. Deferred until the error signal is proven useful.
