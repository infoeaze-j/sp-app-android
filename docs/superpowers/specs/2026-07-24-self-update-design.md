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

---

## Revision — 2026-07-29: resumable download + a distinct interrupted message

Two contract changes landed in `docs/openapi.json` and are folded in here.
`GET /app/releases/{release}/binary` became **`security: []`** — the server took the "exempt the
binary" option, so a forced update before sign-in no longer has a token it cannot supply. It is now
marked `@Headers(NO_AUTH_HEADER_LINE)` like the check. `minSupportedVersionCode` was **removed**
from the `latest` payload; `ReleaseDto` defaults it to 0, which makes the client's floor comparison
inert and leaves the server-computed `updateRequired` as the sole forcing signal. Both the field and
the fallback are kept as the degradation path for a server that omits the verdicts.

The same-origin rule on `apkUrl` survives with a new justification: no bearer token rides along any
more, but the URL is named by the very response we are deciding whether to trust, and same-origin
means the client never has to judge an arbitrary host.

**Resume.** An interrupted transfer keeps its partial; the next attempt sends `Range: bytes=N-` and
re-digests the prefix off local disk before appending. It is opportunistic and self-verifying:

| Response | Action |
|---|---|
| `206` | Append at the offset. A `Content-Range` that starts elsewhere is not honoured. |
| `200` | Server ignored the range — truncate the prefix and stream the whole file. |
| `416` | Prefix is stale; retry from zero inside the same call, no second operator tap. |

The SHA-256 + size check over the finished file is what makes every one of those decisions safe: a
prefix belonging to different bytes can only fail verification, and that path deletes the file, so a
bad prefix cannot loop. `Accept-Encoding` is pinned to `identity`, because OkHttp's transparent gzip
drops `Content-Length` and would desynchronise every offset.

Resume survives a process restart, so `clearDownloads()` became `pruneObsoleteDownloads()`: it
deletes only builds at or below the running one. Discarding partials for other *pending* builds
moved into `downloadAndVerify`, the only place that knows which build is being fetched — the
launch-time prune runs concurrently with the check and cannot know yet. Byte-moving lives in
`data/repository/ApkTransfer.kt`, leaving `UpdateRepositoryImpl` to classify responses.

**The message.** A dropped download now maps to `TransientKind.DOWNLOAD_INTERRUPTED` →
"Download interrupted", instead of `AppResult.Timeout` → "Outcome not confirmed / re-check before
retrying". `Timeout` means the outcome is unknown; a dropped download has no such ambiguity —
nothing was installed. The copy deliberately does not promise resuming, because that only happens
when the server answers `206` and a `UiMessage` carries no format arguments to say which.

Testing: `UpdateApiContractTest` grew resume cases (206 append, 200 fallback, 416 retry, wrong
`Content-Range`, oversized prefix, corrupt prefix, header assertions) and inverted the two that
asserted the old contract — the binary now goes out unauthenticated, and an interrupted download
*keeps* its partial. `FakeUpdateRepository` tracks a resume offset so the resumed-progress UI is
exercisable on a bare emulator. Still device-gated: a real range-capable back office (the published
spec declares only 200 for the binary, so the 200-fallback path may be the only one live traffic
ever takes) and a real drop/restore/restart cycle under a forced update.
