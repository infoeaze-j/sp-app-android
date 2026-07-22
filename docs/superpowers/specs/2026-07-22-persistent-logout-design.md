# Persistent log out control — design

**Date:** 2026-07-22
**Status:** Approved

## Problem

The operator has no way to log out. `AuthRepository.signOut()` exists and is fully
implemented, but nothing in the UI ever calls it — once signed in, the only exit is
session expiry or killing the app. On a shared clinic device that is unacceptable: an
operator handing the device to a colleague must be able to end their own session
immediately, from wherever they happen to be in the journey.

## Requirement

A log out control that is visible and usable at all times, on every screen except
sign-in.

## Current state

- No app chrome exists. `MainActivity` renders `NavGraph()` directly inside a `Surface`;
  there is no `Scaffold` and no `TopAppBar` anywhere in the app.
- Every screen is a bare full-screen `Column`. Exactly one of them —
  `MemberScanScreen.ConfirmContent` — applies its own
  `windowInsetsPadding(WindowInsets.safeDrawing)` plus an extra `top = spacing.xl`,
  precisely because nothing sits above it under edge-to-edge.
- Four routes: `SignIn`, `MemberScan`, `FaceCheck` (full-screen camera preview),
  `AddService`.
- `NavGraph` already runs a session guard: any `sessionState != Active` pops the entire
  back stack to `SignIn`.

## Design

### Chrome placement

App chrome is introduced at exactly one place: inside `NavGraph`, wrapping the `NavHost`.

```
NavGraph
 └─ Scaffold
     ├─ topBar = { if (route != SignIn) FaceVerifyTopBar(onLogOutClick) }
     └─ NavHost(Modifier.padding(innerPadding))
```

`NavGraph` already observes session state and owns route knowledge, so it is the natural
owner. Visibility is driven off `navController.currentBackStackEntryAsState()` — a single
condition, `route != AppRoute.SignIn.path`.

The bar shows a static `"FaceVerify"` title and one `IconButton` action:
`Icons.AutoMirrored.Filled.Logout`, with a `contentDescription` of "Log out" so TalkBack
announces it. The action is never disabled — not while a network call is in flight, not
during face capture. "Usable at all times" is the requirement.

Passing the `Scaffold`'s inner padding into the `NavHost` gives every screen its insets
uniformly, including `SignIn` where the top bar is absent. Consequently
`MemberScanScreen.ConfirmContent` drops its hand-rolled inset handling, which would
otherwise double up. No other screen changes.

### Log out behaviour

Tapping the action always opens an `AlertDialog`:

> **Log out?**
> You'll be returned to sign in. Any in-progress patient verification will be discarded.

The wording holds whether or not a patient is currently verified. This keeps the dialog
free of session-state input and means the control's behaviour never changes silently
depending on state the operator cannot see.

Confirming calls `AppViewModel.logOut()`, which calls `AuthRepository.signOut()`. That is
the entire chain, because existing code already handles the rest:

- `signOut()` attempts server-side invalidation inside `runCatching` and **always** calls
  `sessionManager.clearAll()`. Logging out therefore works offline — a real requirement
  on a clinic device with patchy coverage.
- `clearAll()` discards the session, the verified identity, and the verification window,
  then sets `SessionState.None`.
- The existing `NavGraph` guard observes the non-`Active` state and pops the back stack
  to `SignIn`.

No navigation code and no error path are written for this feature. `signOut()` returns
`AppResult.Success` unconditionally by design; the ViewModel launches it in
`viewModelScope` and ignores the result. The dialog dismisses on confirm and the nav
guard does the rest.

`AppViewModel` gains an `AuthRepository` constructor dependency (it currently takes only
`SessionManager`) and a single `logOut()` method.

## Testing

Follows the existing pattern: JVM unit tests over ViewModels using mockk, JUnit4, and
`MainDispatcherRule`.

- New `AppViewModelTest`: `logOut()` delegates to `AuthRepository.signOut()`, and the
  exposed session state leaves `Active` afterwards when backed by a fake.
- `AuthRepositoryTest` already covers "signOut clears local state even when the server
  call fails". That coverage is verified, not duplicated.

The bar's route-visibility rule and the confirmation dialog are not unit-testable without
adding `compose-ui-test-junit4`, which this project does not currently depend on. Adding
a Compose test toolchain for one screen is out of proportion to this change, so those two
behaviours are verified manually.

## Out of scope

- Showing the operator's name in the bar (`Session.operator.displayName` is available and
  nullable; explicitly declined in favour of a static title).
- Any other app-bar content, navigation affordance, or overflow menu.
- Changing session expiry, the nav guard, or `signOut()` semantics.
