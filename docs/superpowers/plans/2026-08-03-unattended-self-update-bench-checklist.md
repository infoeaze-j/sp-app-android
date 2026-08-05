# Unattended self-update — bench verification

**Date:** 2026-08-03
**Status:** Not yet run. Until both devices pass, the feature is **designed, implemented and
unverified** — do not ship the fleet on it.
**Design:** `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md`

Run this on **one real Sunmi V3 (API 33)** and **one real Sunmi V2s (API 30)** before any device
leaves the office. Nothing below can be substituted with an emulator: the whole question is what
Sunmi's modified Android actually does, and an AOSP emulator image answers a different question.

Everything shipped so far is JVM tests and reasoning about work-runtime and `PackageInstaller`
source. This checklist is the only evidence that counts.

## Prerequisites

- **Release APK signed with the permanent key**, not debug-signed. Verified 2026-08-05 on this
  machine — `assembleRelease` then `apksigner verify --print-certs` printed
  `69:DA:BA:2F:…:70:FC:ED:B4` (V2 signer only, correct for `minSdk 24`). CI has no
  `keystore.properties`, so a CI-built APK is debug-signed and must never leave the building: a
  device that receives one can never take a properly-signed update
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) without a manual uninstall and reinstall in the field.
- **Release `BASE_URL` is `https://bio.infoeaze.com/api/v1/`.** Verified 2026-08-05 from the
  generated release `BuildConfig`, alongside `versionCode 4` / `versionName "1.4"`.
- **`versionCode 5` published to that back office, with `apkUrl` on that same origin.**
  **Not yet done as of 2026-08-05:** `GET /app/releases/latest?versionCode=4` answers
  `200 {"latest":null}` — nothing is published at all, so there is currently no `apkUrl` whose
  origin can be checked. Publish first, then confirm the origin, because
  `CheckForUpdateUseCase` refuses any other host and the refusal reaches the operator as
  `TransientKind.UNKNOWN` — an opaque "try again" nobody can resolve. Origins compare
  scheme + host + port with an omitted port resolved to the scheme's default, so an explicit
  `:443` in `apkUrl` is fine; a different host or a bare `http://` is not.
- The whole fake stack OFF in Dev Settings if a debug build is used for a dry run. The `UPDATE`
  seam in particular: with it on, none of this touches the real back office.

## Office-pass procedure (per device)

1. `adb install -r -t app-release.apk`
2. **Tap the launcher icon once.** Unavoidable: a newly installed app is in the stopped state and
   receives no broadcasts at all, `BOOT_COMPLETED` included, until a human launches it. There is no
   permission, flag or API that avoids this.
3. Grant "install unknown apps" when prompted.
4. **Enable notifications by hand on API 33+.** The app declares `POST_NOTIFICATIONS` but never
   requests it at runtime, and `targetSdk 36` means the platform denies it by default from API 33 —
   so nothing will prompt. Settings → Apps → SP App → Notifications → Allow, or
   `adb shell pm grant com.mediplus.spapp android.permission.POST_NOTIFICATIONS`. The V2s (API 30)
   needs no grant and is unaffected. This manual step is a workaround for a known gap, not the
   intended design; see **Known gaps** below.
5. Settings → Apps → SP App → **turn OFF "Remove permissions if app isn't used"**. Android revokes
   permissions for unused apps from API 30, and the entire fleet is API 30+, so this applies to
   every unit. Design §8 proposed the app also check `isAutoRevokeWhitelisted` and request the
   exemption itself; **that half was never implemented**, so this toggle is the only defence a
   device has. Skipping it on one unit is how that unit silently stops updating.
6. **Perform one real self-update on the bench** (the checks below). This both proves the chain and
   promotes the app to its own installer of record, which is what makes later updates silent —
   `UPDATE_PACKAGES_WITHOUT_USER_ACTION` keys off being the installer of record, and a sideloaded
   app is not one; the shell is.

## The two decisive checks — run these first

One determines whether the other is urgent, so their order is not arbitrary.

- [ ] **1. V3, headless silent install.** Publish `5`, lock the screen, leave the device untouched.
      The app ends up on `5` with nobody having touched it.

      This is the single question the whole design turns on: is `USER_ACTION_NOT_REQUIRED` honoured
      on Sunmi's modified Android 13. It decides whether the V3 half of the fleet is truly
      unattended or whether the fleet has *no* silent half at all. Note the install branch is
      capability-driven, never `SDK_INT`-driven, precisely because a V3 reporting API 33 may still
      refuse — so a refusal here is a supported outcome, not a bug, and simply routes the V3 down
      the same notification path the V2s uses. What makes a refusal serious is the notification gap
      in **Known gaps**.

      To force a run rather than wait for the 6-hour period:

      ```
      adb shell dumpsys jobscheduler | grep -i spapp     # find the job id
      adb shell cmd jobscheduler run -f com.mediplus.spapp <id>
      ```

      `-f` bypasses the network constraint. Do this with the app **backgrounded and off the
      screen** — `ForegroundTracker` is asked for the *fact* of whether anyone is watching, and
      `ProcessLifecycleOwner` reports the foreground with a grace delay, so give it a few seconds
      after backgrounding before forcing the run.

      If you wait for the natural period instead, expect it to stretch past 6 h on an idle locked
      device: the work is a JobScheduler job with a network constraint only, so Doze defers it to a
      maintenance window. A late run is not a failure; a run that never happens is.

- [ ] **2. Post-reboot job survival, on a device nobody opens.** Reboot, do **not** open the app,
      then:

      ```
      adb shell dumpsys jobscheduler | grep -i spapp
      ```

      The periodic job must be back. This closes two open questions at once:

      - whether `BootCompletedReceiver`'s `android:exported="false"` still receives the broadcast
        (it should — the system delivers a protected broadcast to a manifest receiver regardless,
        and work-runtime's own `RescheduleReceiver` is declared the same way);
      - whether Sunmi's build gates `BOOT_COMPLETED` behind an OEM auto-start whitelist. This is
        not hypothetical; many Chinese OEM builds do it. It would block work-runtime's
        `RescheduleReceiver` as well as ours, and since work-runtime builds every `JobInfo` with
        `setPersisted(false)` there is **no JobScheduler fallback underneath**.

      If this fails the feature does not work on an idle device at all, and the fix is an OEM
      auto-start whitelist entry added during the office pass — which is a per-device manual step
      that must then be added to this procedure.

## Remaining checks

- [ ] **V3, foregrounded.** Open the app with `5` published. The offer appears; accepting
      downloads, backs up and installs.
- [ ] **V2s, headless notification.** Same setup as check 1. Expect `STATUS_PENDING_USER_ACTION`
      every time — the platform there can never commit without user action. A high-priority
      notification appears; tapping it lands directly on the system confirmation; confirming
      installs `5`. This is the V2s **primary** path, not a fallback.
- [ ] **V2s, foregrounded — a notification appears too, and that is correct.** With the app open,
      `UpdateStatusReceiver` posts the confirmation notification *and* launches the system dialog.
      The notification is a deliberate safety net: `ProcessLifecycleOwner` reports the foreground
      with a grace delay, so the dialog launch can be silently dropped as a background-activity
      launch just after the app actually left. Record it so nobody reports the duplicate as a bug.
- [ ] **Notification dismissed.** Swipe it away without acting. The next worker run re-posts it.
      `setOnlyAlertOnce(true)` means a re-post while it is still visible is silent, but a re-post
      after a dismissal alerts again — a dismissal is not a fix.
- [ ] **Notification survives a restart (V2s).** With a confirmation notification outstanding,
      force-stop and relaunch the app — or reboot — then tap the notification. It must still open
      the system confirmation and install. This is the check that replaces a unit test that cannot
      exist: launch housekeeping sweeps this app's install sessions and must skip the committed
      one, and that skip is a `PackageInstaller.SessionInfo.isCommitted` read, i.e. platform
      behaviour. Verify with `adb shell dumpsys package installer` before and after — **not**
      `pm list staged-sessions`, which lists staged sessions only and this app never stages one.
- [ ] **Only one committed session accumulates.** Leave a confirmation un-tapped across several
      worker runs, then check `dumpsys package installer` again: exactly one committed session
      should be alive — the newest, whose notification is the only live one. `install()` abandons
      any already-committed session immediately before creating a new one, so they cannot pile up
      against the per-UID session cap.
- [ ] **Reboot, then update.** Reboot the device, do not open the app, publish `6`. It still
      updates. (Check 2 above proves the job came back; this proves the whole journey still runs
      from it.)
- [ ] **Force-stop.** Force-stop from Settings, then reboot. The app does **NOT** update — the
      stopped state blocks every broadcast, which is the platform's rule and expected. Tapping the
      icon once restores it. Record this so nobody reports it as a bug. Note swiping the app off
      the recents list does *not* do this; only Settings force-stop does.
- [ ] **Airplane mode.** Enable it, force a worker run. The attempt retries rather than failing
      permanently, and no notification appears.
- [ ] **Interrupted download.** Kill connectivity mid-transfer, restore it. The next attempt resumes
      (`Range: bytes=N-`) rather than restarting, and an already-complete verified file is not
      re-fetched at all — which is the normal state whenever a confirmation is outstanding.
- [ ] **Backup failure.** Fill the Downloads volume, then update. The install proceeds anyway: the
      backup is best-effort and no longer gates an install, because a stranded field device is
      unrecoverable while a missing backup is an inconvenience. Nothing about the failure reaches
      the operator — `BusinessCode.UPDATE_BACKUP_FAILED` is diagnostic-only now.
- [ ] **Both notification channels visible and separately mutable.** Settings → Apps → SP App →
      Notifications shows **"Update confirmations"** and **"Update problems"**. Mute "Update
      problems" and confirm "Update confirmations" still alerts. That separation is the whole point
      of the split: on the V2s the confirmation notification is the only way an update ever
      completes, and an operator silencing a repeating problem notice must not take the install
      path down with it.
- [ ] **Lost install permission.** Revoke "install unknown apps"
      (`adb shell appops set com.mediplus.spapp REQUEST_INSTALL_PACKAGES deny`), then force a
      worker run with the app backgrounded. The "Update needs permission" notice appears on its own
      channel; tapping it opens the unknown-app-sources screen. Re-grant, force another run, and
      the notice clears. With the app in the **foreground** the notice is neither posted nor
      cleared — the in-app surface is the better place to act on it. **Expect this to produce
      nothing at all on the V3** until the `POST_NOTIFICATIONS` gap is closed.
- [ ] **`POST_NOTIFICATIONS` state on both units.** Record whether it is granted out of the box on
      each device before the manual step above, and confirm the V3 behaviour predicted in **Known
      gaps**. This is what tells us how urgent the follow-up is.
- [ ] **`adb logcat`** during each run, filtered to `PackageInstaller|WorkManager|SpApp`, kept with
      the result.

## Known gaps to confirm rather than debug

- **`POST_NOTIFICATIONS` is declared but never requested.** The only two runtime-permission
  launchers in the app are CAMERA (`FaceCheckScreen.kt`) and WRITE_EXTERNAL_STORAGE
  (`UpdateHost.kt`). With `targetSdk 36` the platform denies `POST_NOTIFICATIONS` by default from
  API 33, so `UpdateNotifications.post()` returns early and **nothing is delivered on the Sunmi V3**
  — neither the lost-install-permission notice nor the pending-confirmation notification.

  The consequence is the part that matters. The V3 is the half that is supposed to install
  silently, so the confirmation notification is its *fallback*. If check 1 shows Sunmi's Android 13
  does not honour `USER_ACTION_NOT_REQUIRED`, the V3 has **no working path at all** until the
  follow-up lands — it would degrade to "installs the next time somebody opens the app", which is
  exactly what this feature exists to remove. Requesting the grant is tracked as separate work; the
  office-pass step above is the interim cover, and a fleet procedure that depends on a manual
  per-device toggle is fragile by construction.

- **`isAutoRevokeWhitelisted` was never implemented.** Design §8 proposed the app request the
  auto-revoke exemption once. Only the notification half of §8 shipped. The office-pass toggle is
  the compensating control.

- **A benign build warning that reads like a failure.** `processDebugUnitTestManifest` emits
  `WorkManagerInitializer was tagged to remove other declarations but no other declaration
  present`. It is cosmetic — the unit-test manifest has no WorkManager node to remove — but it
  reads exactly like evidence that the targeted `tools:node="remove"` on the `WorkManagerInitializer`
  meta-data failed, which is the one failure mode of that change that produces no other signal.
  Do not chase it. What *would* signal a real failure is `HiltWorkerFactory` never being used, i.e.
  `UpdateWorker` failing to construct at runtime.

## Recording

Write the outcome — especially whether the V3 installed silently, and whether the reboot job came
back — into `docs/superpowers/specs/2026-08-03-unattended-self-update-design.md` under **Open
items**, and update the "Current state to be aware of" section in `CLAUDE.md`. Tick the boxes above
in place, with the device and date beside each.
