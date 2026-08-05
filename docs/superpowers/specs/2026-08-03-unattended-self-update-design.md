# Unattended self-update — design

**Date:** 2026-08-03
**Status:** Designed — not yet implemented
**Fleet:** Sunmi V2s on Android 11 (API 30) and Sunmi V3 on Android 13 (API 33), confirmed 2026-08-03
**Builds on:** `docs/superpowers/specs/2026-07-24-self-update-design.md` (the foreground flow)
**Endpoints:** `GET /app/releases/latest`, `GET /app/releases/{release}/binary` (both `security: []`)

## Problem

A fleet of Sunmi V2s and V3 units passes through the office once. That single pass is the only
practical opportunity to load the app. Everything after it — every fix, every contract change the
back office makes — has to arrive over the air, on devices nobody in the business will physically
touch again.

The self-update mechanism shipped on 2026-07-24 cannot do that. It is driven entirely from the UI:
`UpdateViewModel.init` runs the check, `viewModelScope` owns download, backup and install, and
`UpdateHost` renders it. **If nobody opens the app, no update ever happens.** A device that is
switched on each morning and only used for the occasional patient journey may go weeks without the
update path executing, and a device that is powered on but idle will never update at all.

Two further facts, discovered while auditing the path for this design, would have stopped a rollout
regardless of any code change. They are recorded here because they gate everything below:

1. **No release signing key exists.** There is no `keystore.properties` and no `.jks` in the tree.
   `app/build.gradle.kts:70` falls back to debug signing when that file is absent, so a release APK
   built today is signed with the build machine's `~/.android/debug.keystore`. Any device that
   leaves the office with such a build can never receive a properly-signed update
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); the only repair is a manual uninstall and reinstall, per
   device, in the field.
2. **The release back-office URL is a private LAN address** — `https://10.21.2.82:8080/api/v1/`
   (`app/build.gradle.kts:44`). A device on a clinic network or mobile data cannot reach it.
   `CheckForUpdateUseCase` additionally enforces that `apkUrl` is same-origin with `BASE_URL`, so
   the public host must also serve the APK bytes.

Neither is a coding problem, and neither is in scope for this design. Both must be resolved before
the first device leaves the building.

## Requirement

After the app has been launched once by a human on a device, published updates must install without
anyone opening it again — silently where the platform permits, and with a single operator tap where
it does not. A device that is rebooted and left alone must still end up on the current build.

## Non-goal: this does not remove the first manual launch

Android places a newly installed app in the *stopped state*. A stopped app receives no broadcasts at
all — not `BOOT_COMPLETED`, not anything — and cannot be started by any implicit mechanism until a
human launches it once. There is no permission, flag or API that avoids this; it is the platform's
anti-malware rule.

The office pass must therefore include "install, then tap the icon once". After that first launch
the app is eligible for background wakeups permanently, including across reboots. Force-stopping the
app from Settings returns it to the stopped state; swiping it off the recents list does not.

## Current state

- `UpdateRepository`, `ApkTransfer`, `CheckForUpdateUseCase` and `MediaStoreApkBackupStore` are all
  already free of UI and lifecycle assumptions. They are headless-safe as written.
- `UpdateViewModel` owns all orchestration and is the only caller of those pieces. It is already
  carrying a `TooManyFunctions` detekt issue on `main`.
- `PackageInstallerApkInstaller` deliberately does not request a confirmation-free install; its KDoc
  (lines 25–28) documents the path and states it is not enabled.
- `UpdateStatusReceiver` already handles `STATUS_PENDING_USER_ACTION` by launching the system
  confirmation activity, and `InstallStatusEvent.isTerminal` already treats that status as
  non-terminal.
- `DiagnosticsPoller` and `SessionRevalidator` establish the `@Singleton` +
  `ProcessLifecycleOwner` observer pattern, bound from `SpApp.onCreate()`.
- WorkManager is not a dependency.

## Design

### 1. `UpdateCoordinator` — one implementation, two callers

Extract the orchestration out of `UpdateViewModel` into a `@Singleton UpdateCoordinator` in
`core/update`, owning:

- a `Mutex`, so exactly one update attempt runs at a time regardless of who triggered it;
- `val phase: StateFlow<UpdatePhase>`, the single source of truth the UI renders;
- `suspend fun runUpdate(presence: Presence)`;
- the operator intents `UpdateViewModel` exposes today (accept, retry, dismiss, returned-from-settings).

`UpdateViewModel` becomes a thin adapter over it. The worker calls the same `runUpdate`.

**Why a coordinator rather than "the worker owns the work and the UI observes `WorkInfo`."** The
latter is the more conventional WorkManager shape, but `WorkInfo` progress is an untyped `Data`
bundle. Routing state through it would break the sealed-`…Phase` convention the codebase enforces
everywhere, push a platform type into the UI layer, and cost the JVM-testability of the whole flow.
A singleton with a `StateFlow` keeps one code path, one set of explicit states, and MockK-level
tests.

This also resolves the standing `TooManyFunctions` issue on `UpdateViewModel` rather than adding
to it.

### 2. `UpdateWorker`

A `@HiltWorker CoroutineWorker` whose entire body is `coordinator.runUpdate(Presence.Headless)`
mapped to a `Result`.

- Periodic, 6-hour interval, constrained to `NetworkType.CONNECTED`, exponential backoff.
- `Result` mapping is deliberate: a `TransientFailure` or `Timeout` from the check or the download
  returns `Result.retry()` so WorkManager's backoff handles a flaky clinic connection; everything
  else — up to date, a business rejection, a corrupted APK, or a commit awaiting confirmation —
  returns `Result.success()`, because the next periodic run is the right retry cadence and hammering
  a server that gave a definite answer helps nobody.
- Enqueued as unique periodic work with `ExistingPeriodicWorkPolicy.KEEP` from `SpApp.onCreate()`,
  beside the existing `bind()` calls. `KEEP` is what makes re-enqueuing on every process start
  *harmless*, not what makes it curative: an existing enqueued `WorkSpec` is left untouched, so the
  call only does anything where there is no row to keep — a first-ever launch, or a database wiped
  by clear-data. Recovery of a dropped schedule is WorkManager's own job, via the `ForceStopRunnable`
  its constructor dispatches — but it does *not* happen whether or not we ask. Removing the
  `androidx.startup` initializer (below) makes `WorkManagerImpl` construct lazily on the first
  `WorkManager.getInstance(...)`, and `UpdateScheduler.schedule()` holds the only such call in the
  app. Asking is therefore what triggers the recovery. Verified against work-runtime 2.10.0.
- New dependencies: `androidx.work:work-runtime-ktx` and `androidx.hilt:hilt-work`, plus
  `HiltWorkerFactory`, `SpApp : Configuration.Provider`, and removal of WorkManager's
  `androidx.startup` auto-initializer from the merged manifest.

**Reboot.** A reboot destroys every JobScheduler job this app had: work-runtime builds each `JobInfo`
with `setPersisted(false)` deliberately, because it rebuilds them on `BOOT_COMPLETED` instead. What
survives is the `WorkSpec` row in WorkManager's own database, so a process must start and construct
`WorkManagerImpl` for `ForceStopRunnable` to notice the missing job and re-create it.

work-runtime already ships a receiver for exactly this — `RescheduleReceiver` — so the one this
design adds, with `RECEIVE_BOOT_COMPLETED`, is a **second** path rather than the only one. Its
advantage is narrow but real: it is statically enabled in our manifest, whereas `RescheduleReceiver`
is declared `enabled="false"` and switched on by a runtime `setComponentEnabledSetting` write that
`UnfinishedWorkListener` makes only after WorkManager has been constructed in-process and observed
unfinished work. Ours does not depend on that write having landed. Its `schedule()` body is what
constructs `WorkManagerImpl` and so triggers the rebuild — the `KEEP` enqueue itself no-ops.

None of it reaches a freshly installed app: Android holds one in the stopped state, where it
receives no broadcasts at all, until a human launches it once.

### 3. Presence, and the branch that actually matters

The install branch must **not** be a `Build.VERSION.SDK_INT` test. Sunmi ships modified Android; a
V3 reporting API 33 may still have a `PackageInstaller` that refuses a confirmation-free commit. A
version check would assume silence, never receive a terminal status, and suspend forever. The
platform's own answer is the only trustworthy signal.

So: **always request silent, and let the system decide.**

`PackageInstallerApkInstaller.sessionParams()` gains, guarded purely for API availability:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
}
```

with `<uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />` —
a normal permission, auto-granted, inert below API 31.

The system then answers either with a terminal status (it installed; nobody was involved) or with
`STATUS_PENDING_USER_ACTION` plus a confirmation `Intent`. The only remaining question is whether a
human is present, which a new `@Singleton ForegroundTracker` answers — another
`ProcessLifecycleOwner` observer, following `DiagnosticsPoller` exactly.

With the fleet now known, both answers occur in the field by design, not as an edge case:

| Device | API | Result of the commit |
| --- | --- | --- |
| Sunmi V3 | 33 | Silent. Installs unattended. |
| Sunmi V2s | 30 | `STATUS_PENDING_USER_ACTION` — always. One operator tap per update. |

The notification path is therefore **the primary path for the V2s half of the fleet**, not a
fallback, and must be built to that standard: high-priority, re-posted by the next worker run if
dismissed, and tapping it must land directly on the system confirmation. The capability-driven design
is what lets one code path serve both halves — but it should not be read as "silent, with a rare
exception".

`UpdateStatusReceiver` branches on that:

- **Foreground** → `startActivity(confirm)`, unchanged from today.
- **Headless** → post a notification whose `PendingIntent` wraps `confirm`, then publish a terminal
  event so the suspended `install()` returns `InstallOutcome.AwaitingConfirmation`.

`InstallOutcome` gains `AwaitingConfirmation`; `UpdatePhase` gains `ConfirmationPending`.

**This closes a latent hang.** On API 29+ a background activity launch is normally blocked and
logged rather than thrown, so the `SecurityException` catch at `UpdateStatusReceiver.kt:51` would
not fire, `publishLaunchFailure` would not run, and `install()` would suspend indefinitely. The bug
is unreachable today because installs only occur with the app open. It becomes a guaranteed hang
the moment a worker drives the flow.

### 4. Keeping the pending session alive

A session waiting on a notification tap must survive. `abandonStaleSessions()` currently abandons
every session belonging to this app at launch, which would orphan the notification's intent.

**Revised during implementation (2026-08-03).** This section originally proposed that the
coordinator record the pending session id and the installer skip it. That does not work: launch
housekeeping runs once per process and always *before* any install, so within a process the id is
always null, and across a process restart — which every reboot is — the in-memory id is gone. The
parameter would never fire in any reachable scenario, and the cross-process case is the one that
matters, because on the V2s the notification is the only way an update completes.

The platform already holds the fact. A session awaiting confirmation is **committed**, and
`PackageInstaller.SessionInfo.isCommitted` (API 29+; the fleet floor is 30) reports it. The sweep
skips committed sessions. No new state, no signature change, and it survives process death. The
trade is that the logic now lives in a platform class and is device-verified rather than
unit-tested.

If the operator opens the app rather than tapping the notification, the foreground flow re-checks
and re-offers from `ConfirmationPending`, raising the system dialog directly.

**Sparing every committed session at launch is only safe because `install()` also caps how many can
ever exist, on API 29+.** Left unchecked, a headless worker that finds the previous confirmation still
un-tapped would create and commit a fresh session on every retry cycle — `RetryTarget.DOWNLOAD` always
leads back through `install()` — while `UpdateNotifications` posts under a single notification id, so
each new commit silently replaces the intent behind the last one. The sessions behind those superseded
notifications become unreachable but, after the fix above, un-abandoned: they accumulate against the
per-UID session cap until `PackageInstaller.createSession` throws. `install()` therefore abandons any
already-committed session immediately before creating a new one — the abandon delivers a terminal
status to `UpdateStatusReceiver`, which clears that session's notification only if it is still the one
visible, never a newer one — keeping exactly one committed session alive at a time on API 29+: the
newest, whose notification is the only one still live. Below API 29 `isCommitted` cannot be read, so
this abandon is a no-op and the accumulation stays open; `minSdk` is 24, so that range is live code,
just below the fleet's actual floor of 30, and is left undefended deliberately rather than adding a
fallback for a range no device in the fleet runs.

### 5. Reuse an APK that is already downloaded and verified

`resumableBytes()` (`ApkTransfer.kt:68`) deletes any file at or past the declared size, so a
download that completed but has not been installed is discarded and fetched again in full. That path
was rare before. It becomes the *common* case here, because "downloaded, waiting for a confirmation
tap" is exactly the state the headless flow parks in.

Fix: before opening a transfer, if the cached file's byte count and SHA-256 both match what the back
office published, return `Success` without making a request. The digest remains the sole authority
on whether those bytes may be installed, so this is a pure saving with no new trust.

### 6. The backup is best-effort, never a gate

`UpdateViewModel.backupAndInstall` currently refuses to install when `backupCurrentApk` fails — "no
backup, no install, ever". Headless, on a device with a full storage volume, that stops every future
update permanently with nobody present to notice.

**Decision (2026-08-03): the gate is removed entirely.** The coordinator attempts the backup,
records the failure, and installs regardless. Rationale: rollback is already a manual procedure
(uninstall, then install the backup by hand), so its practical value on a field device that nobody
will touch is limited — while a device silently stranded on a stale build is a real and unrecoverable
outcome.

This has a consequence that must be followed through, or the change is half-done. `UpdateHost` asks
for `WRITE_EXTERNAL_STORAGE` before accepting an update whenever `needsLegacyWritePermission()` is
true, and denying it lands in `onLegacyWriteDenied()`, which fails the whole attempt with
`UPDATE_BACKUP_FAILED` — a second, independent block that removing the gate in `backupAndInstall`
would not touch. Under the new rule that denial must instead **skip the backup and proceed to
install**, exactly as a failed `backupCurrentApk` now does. `BusinessCode.UPDATE_BACKUP_FAILED`
consequently loses its last blocking caller and is retained only as a diagnostic code.

With the fleet confirmed at API 30 and above, `needsLegacyWritePermission()` (`SDK_INT < Q`) is
always false in the field, so this path is unreachable on real devices. It is still corrected,
because leaving a second blocking route in place while documenting that the gate was removed is the
kind of discrepancy that costs an afternoon the next time somebody reads this code — but it is
correctness work, not a field risk.

### 7. Notifications

Requires a notification channel. The runtime-permission situation falls out conveniently: the V2s
(API 30) — the devices that *depend* on the notification, because they can never install silently —
do not need a runtime grant for it. The V3s (API 33) do need `POST_NOTIFICATIONS`, but only reach
the notification path at all if Sunmi's modified OS refuses the silent commit. Grant it at the office
regardless; it is the cheap insurance against exactly the case this design cannot test in advance.

If it is denied on a V3, the headless path degrades to "installs the next time somebody opens the
app" — no worse than today's behaviour.

### 8. Permission auto-reset

Android 11+ revokes runtime permissions for unused apps, which could strip `REQUEST_INSTALL_PACKAGES`
from exactly the idle devices this design targets. **The whole fleet is API 30 or above, so this
applies to every device**, not just the newer half. A worker running every six hours should count as
use, but the app should also check `isAutoRevokeWhitelisted` and request the exemption once, at the
office.

### 9. Considered and deferred: device-owner provisioning

Enrolling each unit as device owner (`adb shell dpm set-device-owner …` on a device with no
configured accounts, which is feasible during an office pass that already touches every device over
adb) would give silent installs on the V2s too, auto-grant the install and notification permissions,
and make the app immune to permission auto-reset and to being uninstalled. On the stated goal —
certainty that every device can update itself — it is strictly stronger than this design.

It is **not** proposed for this rollout, for three reasons: device owner cannot be removed without a
factory reset, so a mistake is expensive and per-device; it requires a `DeviceAdminReceiver` and a
provisioning step layered onto a rollout that already has two unresolved blockers; and the V2s
failure mode without it is "an operator taps once", which is a delay, not a stranded device.

It should be revisited if the V2s tap turns out to be unreliable in practice — the notification going
unnoticed for weeks would change the calculation, and this is the escape hatch.

## Unchanged

`UpdateRepository`, `ApkTransfer`, `CheckForUpdateUseCase`, `MediaStoreApkBackupStore`,
`InstallEventBus`, and the whole `AppResult` / `ErrorMapper` treatment. The containment seams the
project already enforces are what make this a contained change rather than a rewrite.

## Testing

- `UpdateCoordinatorTest` — inherits the existing `UpdateViewModelTest` cases, plus mutual exclusion
  under concurrent triggers and both `Presence` values.
- `UpdateViewModelTest` — reduced to adapter behaviour over a fake coordinator.
- `UpdateWorkerTest` — `TestListenableWorkerBuilder`, asserting the worker's `Result` mapping
  (retry on transient, success on every definite answer).
- Backup-is-not-a-gate — explicit tests that a failed `backupCurrentApk` **and** a denied legacy
  write permission both still reach `Installing`. These are denial-path tests in the constitution's
  sense, and they are the ones that would have caught the half-done version of section 6.
- Installer — session-params construction and the presence branch; the platform call stays behind
  the seam.
- `ApkTransfer` — the already-complete-and-verified short circuit.
- **Bench test on a real V3, and on a real V2s.** Publish `versionCode 5`, leave the device locked
  and untouched, and confirm the update lands. This is the only evidence that counts, and it must
  happen before the fleet ships.

## Rollout consequence worth planning for

`UPDATE_PACKAGES_WITHOUT_USER_ACTION` keys off being the *installer of record*. A sideloaded app is
not — the shell is. The first time the app installs itself, it becomes its own installer of record.

Worst case is therefore "the first update needs one tap; every update afterwards is silent". This
argues for ending the office pass with **one real self-update performed on the bench**: ship
`versionCode 4`, publish `5`, and let the device pull it. That both promotes the device to
silent-capable and proves the entire chain — signing, URL, download, digest, install, relaunch — on
that specific unit before it leaves.

## Open items

- **Whether `USER_ACTION_NOT_REQUIRED` is honoured on Sunmi's modified Android 13** can only be
  settled on a real V3. The design fails safe either way — a refusal simply routes the V3 down the
  same notification path the V2s already uses — but it decides whether half the fleet or none of it
  updates unattended, so it is the single most valuable thing the bench test establishes.
- **`minSdk` is 24 while the fleet floor is now API 30.** Raising it would drop core-library
  desugaring and delete the legacy storage path outright. Deliberately **not** part of this work:
  changing the minimum SDK is a build-wide change with its own regression surface, and doing it
  during a rollout that is already blocked on a signing key and a hosting decision trades a real risk
  for a tidiness gain. Worth doing immediately afterwards.
