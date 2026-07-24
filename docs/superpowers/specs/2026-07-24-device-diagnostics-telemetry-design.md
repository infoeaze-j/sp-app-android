# Device Diagnostics Telemetry Design

**Date:** 2026-07-24
**Status:** Draft — awaiting review

## Problem

FaceVerify runs on unmanaged, sideloaded clinic devices. When a device misbehaves in the field
(battery dying mid-session, flapping between Wi-Fi and mobile data, low storage blocking an update)
there is no way for the back office to see the device's state. Support is blind.

We want the back office to be able to **pull** a snapshot of a device's current state on demand,
without the app ever volunteering data on its own and without collecting anything that identifies
the patient, the operator, or the handset. The device answers a question the back office asked; it
never decides to phone home by itself.

## Decisions

| Question | Decision |
|---|---|
| Model | **Poll-then-report.** The app polls "do you want diagnostics from me right now?"; only if the answer is yes does it collect and send a snapshot. The device never pushes unsolicited data. |
| Poll trigger | On each **login** (session becomes `Active`) and then on a fixed **interval** while the app is foregrounded. Foreground-only. |
| Scheduler | A `ProcessLifecycleOwner`-aware coroutine loop (approach A). No `WorkManager`/`AlarmManager` — diagnostics only make sense while an operator is actively using the app, and the loop dies cleanly on session loss, consistent with `SessionManager.clearAll()` wiping all session-bound state. |
| Auth | The poll is **authenticated** — it rides the bearer token like every other call, so it fires on each login + interval, **not** on a cold pre-login launch (there is no token before login). *Assumption to confirm with the back office.* |
| Poll response | `{ requestId }` when diagnostics are wanted; `204 No Content` (or empty body) when not. |
| Report payload | The **full** permission-free snapshot, tagged with the `requestId`. The server picks nothing; it always gets everything non-identifying. |
| Dedup | The app remembers the **last-handled `requestId`** in memory. A `requestId` equal to the last handled one is ignored, so each request is answered exactly once per process. A fresh process may re-report the same `requestId` once — harmless if the server treats reports as idempotent on `requestId`. |
| Failure posture | **Best-effort / fire-and-forget.** Every poll and report is off-main; any `TransientFailure`/`Timeout`/non-2xx is swallowed. Diagnostics never block, delay, or fail the patient journey, and never surface a `UiMessage`. |
| Permissions | **Nothing requiring a runtime permission grant.** `ACCESS_NETWORK_STATE` is install-time (already normal). No hardware IDs, no location, no cellular generation. Non-revealing by construction, consistent with the constitution. |
| Endpoints | `GET /diagnostics/poll` and `POST /diagnostics`, app-invented placeholders on `BuildConfig.BASE_URL`, documented in `docs/openapi.yaml` to be reconciled when the server publishes its shape (the `/members/verify` precedent). Both same-origin with `BASE_URL`. |

## The snapshot (`DeviceStateSnapshot`)

Every field below is readable with **no runtime permission**. Collection runs off-main on
`@IoDispatcher`.

| Group | Fields | Source |
|---|---|---|
| Battery | levelPercent, isCharging, plug (ac/usb/wireless/none), health, temperatureDeciC, voltageMv, powerSaveMode | `BatteryManager`, sticky `ACTION_BATTERY_CHANGED`, `PowerManager` |
| Network | transport (wifi/cellular/ethernet/vpn/none), isMetered, isValidated | `ConnectivityManager` + `NetworkCapabilities` (`ACCESS_NETWORK_STATE`, install-time) |
| Storage | internalFreeBytes, internalTotalBytes | `StatFs` / `StorageManager` |
| Memory | availMemBytes, totalMemBytes, lowMemory | `ActivityManager.MemoryInfo` |
| Display | widthPx, heightPx, densityDpi, refreshRateHz, rotation | `DisplayMetrics`, `Display` |
| Device | manufacturer, model, brand, device, sdkInt, release | `Build.*` |
| App | versionName, versionCode | `PackageManager` (reuse existing `CurrentAppVersion`) |
| Environment | locale, timeZoneId, airplaneMode | `Locale`, `TimeZone`, `Settings.Global` |
| Thermal | thermalHeadroom (API 29+, nullable), thermalStatus (API 29+) | `PowerManager` |
| Uptime | uptimeMillis, elapsedRealtimeMillis | `SystemClock` |

**Deliberately excluded** (permission-gated or identifying): IMEI, serial, MAC, `ANDROID_ID`,
precise/coarse location, cellular network generation (`getDataNetworkType` now needs
`READ_PHONE_STATE`), per-app data-usage history (`PACKAGE_USAGE_STATS`), and any biometric or
patient/operator data. The snapshot is low-entropy device *state*, not a device *fingerprint*.

## Architecture

Follows the existing seam pattern (`core/nfc`, `core/camera`) and one-way layering.

```
core/diagnostics/
  DeviceDiagnostics.kt          interface: suspend fun snapshot(): DeviceStateSnapshot
  DeviceStateSnapshot.kt        the permission-free data class (+ nested groups)
  AndroidDeviceDiagnostics.kt   real impl (in main); collection split into small private
                                builders — buildBattery()/buildNetwork()/buildDisplay()/… —
                                each under detekt's 50-line function cap
  DiagnosticsPoller.kt          ProcessLifecycleOwner-aware loop; polls on Active + every
                                INTERVAL (default 15 min, named constant) while foregrounded;
                                stops on session loss

data/remote/
  DiagnosticsApi.kt             GET /diagnostics/poll, POST /diagnostics
  DiagnosticsDtos.kt            wire DTOs (never leave this package)

data/repository/
  DiagnosticsRepository.kt      apiCall → AppResult; snapshot → DTO; holds last-handled
                                requestId in memory

domain/usecase/
  PollAndReportDiagnosticsUseCase.kt   poll → if fresh requestId → snapshot → report → record
```

**Debug vs release binding** (mirrors `CameraModule`/`NfcModule`/`RepositoryModule`/`UpdateModule`):

- `release/…/DiagnosticsModule` → binds real `AndroidDeviceDiagnostics` + real repository.
- `debug/…/DiagnosticsModule` → binds `SwitchingDeviceDiagnostics` and a switching repository driven
  by `DevSettingsStore`. A new `DiagnosticsScenario` enum (e.g. `OFF`, `REQUESTED_ONCE`,
  `POLL_FAILS`) lets the whole poll→report loop run on a bare emulator with a canned snapshot and a
  forced `requestId`. Master `DevSettings.fakeEnabled` still gates it.

## Data flow

1. Session becomes `Active` → `DiagnosticsPoller` starts (or resumes on foreground).
2. Poller calls `PollAndReportDiagnosticsUseCase` immediately, then every `INTERVAL`.
3. Use case → `DiagnosticsRepository.poll()` → `GET /diagnostics/poll`.
   - `204`/empty → `AppResult.Success(null)` → nothing to do.
   - `200 { requestId }` → `AppResult.Success(requestId)`.
   - transport failure → `TransientFailure`/`Timeout` → swallowed, retried next interval.
4. If `requestId != lastHandled`: `DeviceDiagnostics.snapshot()` (off-main) → `repository.report(requestId, snapshot)` → `POST /diagnostics`.
5. On report `Success`, record `requestId` as last-handled. On failure, leave it unrecorded so the next poll retries.
6. Session lost / app backgrounded → poller stops; `clearAll()` semantics unaffected (nothing biometric or identifying is held).

## Contract (placeholder)

```
GET /diagnostics/poll         (security: [bearer])
200 → { requestId: string }   // back office wants a snapshot
204 → (empty)                 // nothing requested; client does nothing

POST /diagnostics             (security: [bearer])
body → {
  requestId: string,          // echoes the poll's requestId for correlation/idempotency
  snapshot: { …permission-free fields grouped as above… }
}
200/202 → accepted
```

To be reconciled when the server publishes real shapes. Both URLs stay same-origin with
`BuildConfig.BASE_URL`; the bearer token rides on every request.

## Error handling

No new `AppResult` or `AppError` variants, and **no `UiMessage`** — diagnostics are invisible to the
operator by design. Transport failures reuse the existing `TransientFailure`/`Timeout` classification
from `apiCall`; a non-2xx poll/report is logged diagnostically (never with snapshot contents beyond
non-identifying state) and dropped. There is no user-facing surface and no phase enum, because there
is no screen.

## Testing (test-first, per constitution)

- `PollAndReportDiagnosticsUseCaseTest`: requested→reports; not-requested (204)→no report;
  duplicate `requestId`→no second report; poll `TransientFailure`→no report, no crash; report
  failure→`requestId` not recorded (retried next interval).
- `DiagnosticsRepositoryTest` (MockWebServer): 200 poll maps to requestId; 204 maps to null; report
  maps snapshot→DTO body correctly; HTTP-code → `AppResult` mapping.
- `AndroidDeviceDiagnosticsTest` (Robolectric where needed): each builder maps platform values to
  snapshot fields; nullable thermal fields on pre-29.
- `DiagnosticsPollerTest` (`MainDispatcherRule` + Turbine): polls immediately on Active; repeats on
  interval; stops on session loss / background; overlapping ticks don't double-fire.
- Debug fake-stack tests in `app/src/testDebug/`: `DiagnosticsScenario` drives forced `requestId` and
  canned snapshot end-to-end.
- ≥ 80% coverage on changed code; explicit success **and** not-requested/failure paths.

## Out of scope / YAGNI

- No field-selection in the poll response (full snapshot only — the "Request ID, full snapshot"
  contract).
- No background/closed-app polling (foreground-only).
- No persistence of the last-handled `requestId` across process death (in-memory; server idempotency
  covers the rare re-report).
- No new UI, no dedicated diagnostics screen for the operator.
- No gating of the self-update download on these signals (that stays inside the update flow).

## Open questions for review

1. **Auth assumption:** is the poll authenticated (fires on login + interval), or does the back
   office expect an unauthenticated device-level poll at cold launch? The design assumes
   authenticated.
2. **Interval:** default 15 min while foregrounded — acceptable, or a different cadence?
3. **Snapshot field list:** anything to add or drop from the table above?
