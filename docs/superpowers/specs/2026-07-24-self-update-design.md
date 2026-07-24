# Self-Update Design

**Date:** 2026-07-24
**Status:** Approved

## Problem

FaceVerify reaches clinic devices by sideloading — no Play Store, no MDM. There is no way to ship
a fix to the field. The app must update itself: ask the backend whether a newer build exists,
download the APK, prove its integrity, install it, and come back up. A broken release must be
recoverable without a technician rebuilding anything: the previously installed APK is kept on the
device as a rollback backup.

## Decisions

| Question | Decision |
|---|---|
| Device posture | Sideloaded, unmanaged. The OS install confirmation (operator taps) is accepted. |
| Install mechanism | `PackageInstaller` session API. Preserves the future confirmation-free path: after the first self-update the app is its own installer-of-record, unlocking Android 12+ `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` + `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. |
| Rollback | Copy of the **currently installed** APK (`ApplicationInfo.sourceDir`) to `Downloads/FaceVerify/faceverify-backup-v{code}.apk` — shared storage that survives uninstall. Revert is manual: uninstall, then install the backup from the Files app (Android forbids in-place downgrades). |
| Retention | Exactly one backup — the previous version. Older backups are pruned only on a launch after a successful update; failure paths never delete anything. |
| Enforcement | The server decides. `currentVersionCode < minSupportedVersionCode` → forced (blocking screen); `< latestVersionCode` → optional (dismissible prompt); else silent. |
| Check timing | On app launch, concurrent with the UI, pre-sign-in (unauthenticated). Check failure fails **open** with a retry — the backend is required for login anyway, so blocking here adds nothing. |
| Endpoint | `GET /app/version`, app-invented placeholder on `BuildConfig.BASE_URL`, documented in `docs/openapi.yaml` to be reconciled when the server publishes its shape (the `/members/verify` precedent). |
| Integrity | SHA-256 (hex) and byte count from the check response, digest computed while streaming; mismatch is a hard failure and the file is deleted. Android additionally enforces same-signature on install. |
| Signing | Out of scope, handled separately. **Constraint:** an update APK only installs over a build signed with the same key; current builds use default debug signing, so the permanent release key must be standardized before the first field rollout. |

## Contract (placeholder)

```
GET /app/version            (security: [])
200 → {
  latestVersionCode: int,   // must bump monotonically per release
  latestVersionName: string,
  apkUrl: string,           // must stay same-origin as BASE_URL (bearer token rides on every request)
  sha256: string,           // 64 hex chars, digest of the APK bytes
  sizeBytes: long,
  minSupportedVersionCode: int
}
404 → endpoint not deployed yet; client treats as up-to-date (fail open)
```

Degenerate data never bricks a device: `CheckForUpdateUseCase` sanity-clamps (forced state only
when an installable newer APK exists; empty sha / non-positive size / bad URL → treated as a failed
check, fail open).

## Error taxonomy

No new `AppResult`/`AppError` variants. Following the `CARD_UNREADABLE` precedent, device-local
hard failures become `BusinessCode` entries:

- `UPDATE_CORRUPTED` — digest or size mismatch; temp deleted; retry restarts the download.
- `UPDATE_BACKUP_FAILED` — rollback backup could not be written; **install never proceeds without a backup**.
- `UPDATE_INSTALL_ABORTED` — operator canceled the OS dialog; retry re-commits the kept, verified APK.
- `UPDATE_INSTALL_FAILED` — any other installer failure (signature mismatch, storage, blocked source).

Transport failures reuse the existing `TransientFailure` / `Timeout` classification.

## Flow

Sealed `UpdatePhase`: `Idle / CheckFailed / UpdateAvailable(info, forced) / PermissionNeeded /
Downloading(bytes, total) / BackingUp / Installing / AwaitingInstallConfirmation /
Failed(message, retry = DOWNLOAD | INSTALL)`.

1. Launch: three concurrent IO jobs, none delaying first frame — prune stale backups, clean
   leftovers (`cacheDir/updates/*`, abandoned installer sessions), version check.
2. Check resolves: up-to-date → nothing; optional → `ActionDrawer` (Update now / Later); forced →
   full-screen overlay above the chrome, no dismiss; transport failure → dismissible notice + retry.
3. Update tapped: if `canRequestPackageInstalls()` is false → route to
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, re-check on resume. API < 29 additionally requests
   `WRITE_EXTERNAL_STORAGE` (denial → `UPDATE_BACKUP_FAILED`).
4. Download: stream to `cacheDir/updates/`, 64 KiB chunks feeding `MessageDigest`, progress
   throttled to ≥ 1 % deltas into the `StateFlow`; total from `sizeBytes`, not Content-Length.
5. Verify: digest (case-insensitive hex) and byte count; mismatch → delete temp → `UPDATE_CORRUPTED`.
6. Backup: installed APK → Downloads (replace same-name). Failure blocks the install; retry
   re-enters here, keeping the verified download.
7. Install: `PackageInstaller` session, write + fsync, commit with a `FLAG_MUTABLE`
   explicit-component broadcast `PendingIntent`; a non-exported receiver bridges statuses back over
   a `@Singleton` shared-flow bus; `STATUS_PENDING_USER_ACTION` launches the OS confirm.
8. Success **is** process death — no success phase renders. `ACTION_MY_PACKAGE_REPLACED` receiver
   try/catch-relaunches MainActivity (background-activity-launch rules make this best-effort; the
   fallback is the operator reopening the app). Cancel → `UPDATE_INSTALL_ABORTED` (retry
   re-installs, no re-download; degrade to re-download if the cache was evicted). Other failures →
   `UPDATE_INSTALL_FAILED`. Backups are untouched on every failure path.

## Backup rotation (pure, idempotent, crash-safe)

On every launch: parse `v(\d+)` from `faceverify-backup-v*.apk`; delete every backup with
`versionCode < current` **except the highest**; keep anything `>= current` and anything
unparseable. Running an old build (failed update) therefore never loses its own rollback chain;
the one-backup invariant restores itself on the first launch after a successful update.

## Architecture

Platform types stay contained (the `FaceCamera`/`MemberCardReader` rule): `ApkInstaller` and
`ApkBackupStore` interfaces in `core/update/` hide `PackageInstaller` and `MediaStore`;
`UpdateRepository` in `data/repository/` owns check + streaming download/verify over a new
`UpdateApi`; `CheckForUpdateUseCase` is pure gating; `UpdateViewModel` orchestrates;
`UpdateHost` renders in `NavGraph` above the `NavHost` (the Scaffold gains a `Box` wrapper so the
forced overlay covers the top bar). DI follows the build-type substitution pattern: release binds
real impls, debug binds `Switching*` wrappers driven by a new `UpdateScenario`
(`UP_TO_DATE / OPTIONAL_UPDATE / FORCED_UPDATE / CHECK_FAILS / DOWNLOAD_FAILS / HASH_MISMATCH /
INSTALL_FAILS`) so the whole flow demos on a bare emulator. `ApkBackupStore` stays real in both
build types. Current version comes from `BuildConfig.VERSION_CODE/NAME` injected as
`CurrentAppVersion`.

## Testing

JVM: gating matrix with boundaries, MockWebServer contract tests (including a tampered-body
hash-mismatch and truncation, asserting temp-file deletion), pure rotation-rule tests, ViewModel
phase sequences via Turbine (forced is never dismissible; backup failure proves the installer was
never invoked; abort-retry proves no second download). Debug stack tested in `testDebug/`.
Device-gated (manual checklist): real commit + OS dialog, signature-mismatch failure,
unknown-sources round-trip, MediaStore on 29+ vs legacy ≤ 28, backup surviving uninstall plus the
Files-app revert drill, relaunch after `MY_PACKAGE_REPLACED`, installer-of-record after the first
self-update.
