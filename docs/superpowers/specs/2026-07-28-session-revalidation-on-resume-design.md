# Session revalidation on resume — design

**Date:** 2026-07-28
**Status:** Implemented — 2026-07-28
**Endpoint:** `GET /auth/session` (`auth.session` in `docs/openapi.json`)
**Tracked as:** was open decision #2 in `CLAUDE.md`; closed on implementation

## Problem

A session can expire server-side while the app sits backgrounded — a tablet asleep at
reception, an operator who switched apps, a device left on the counter over lunch. Nothing
on the device notices, because nothing is being called.

The app finds out **passively and late**: the next protected request returns 401,
`AuthInterceptor` flips the session to `Invalidated`, and `NavGraph`'s guard pops the back
stack to sign-in. Everything is discarded correctly — `clearAll()` wipes the verification
state along with the session, exactly as FR-004a requires. The cost is *when* it happens.
The first protected call after a resume is usually the member-card verification, which
means the discovery lands after the operator has already asked the patient for their card
and tapped it — and if the poller happens to have refreshed the session first, it can land
later still, after consent and a face capture. Nothing is unsafe; the patient has simply
been walked through steps that were never going to count.

## Non-goal: this is not session persistence

Worth stating plainly, because it is the natural first reading of "revalidate the session".

The bearer token exists only in `InMemorySessionManager`'s `StateFlow`. `PrefsDataStore`
says so in its own KDoc — "No session token, document number, or any biometric data is ever
written here" — and that is a deliberate choice, not an oversight. When the process dies the
token dies with it, `sessionState` reads `None` on next launch, and the guard sends the
operator to sign-in. There is nothing left for `GET /auth/session` to revalidate.

Resuming a session across an app close would require persisting the token. That is a
materially different decision with its own threat model, and it is **not** what this design
proposes or enables.

## Requirement

When the app returns to the foreground with a session it believes is active, confirm that
belief with the back office *before* the operator involves a patient — and never end a
session on anything less than the server saying so.

## Current state

- `AuthApi.session()` exists and returns a `SessionResource`; it was realigned to the
  published spec on 2026-07-28 but **nothing calls it**. `AuthRepository` exposes no
  revalidation method.
- `AuthInterceptor` already converts the outcome we care about into the state change we
  want: on any response with `code == 401` for a request that **carried a token**, it calls
  `sessionManager.markSessionInvalidated()`. The 401-on-sign-in case is excluded by the
  `hadToken` check, so this cannot misfire on a bad password.
- `markSessionInvalidated()` → `invalidate(Invalidated)` clears session, verified identity
  and freshness window in one step. `NavGraph` pops to sign-in on any non-`Active` state,
  and `SignInViewModel` shows its "session ended" notice for `Expired` *and* `Invalidated`.
- `DiagnosticsPoller` is a `@Singleton DefaultLifecycleObserver` bound to
  `ProcessLifecycleOwner` from `SpApp.onCreate()`. It is the only such observer today.
- `markSessionExpired()` has exactly one caller, and it is the debug-only "force expire"
  action in Dev Settings. `SessionState.Expired` is therefore not reachable in a release
  build.

### The expiry is *sometimes* already caught on resume, by accident

`DiagnosticsPoller.onStart` relaunches its loop, and `pollWhileActive()` polls
**immediately** whenever the session is `Active` before waiting out its 15-minute interval.
So on most foregroundings a `GET /diagnostics/requests/pending` does go out carrying the
token, and if the session is dead its 401 reaches `AuthInterceptor` and the routing happens.

This is not a reason to skip the work. It is unreliable in three ways, and the third is the
one that matters:

1. It is incidental. Nothing documents it, no test asserts it, and telemetry is explicitly
   "best-effort throughout" — a future change that makes polling conditional, batched or
   removable silently takes auth correctness with it.
2. It does not run when the `DIAGNOSTICS` fake seam is on, which is the default in debug —
   so the behaviour a developer sees is not the behaviour the field sees.
3. **Diagnostics now requires `X-Device-Id`.** If device registration has not succeeded for
   this install, the poll can fail with a validation error rather than a 401 — in which case
   `AuthInterceptor` never fires and the expiry stays undetected exactly as before.

Basing a security-adjacent behaviour on a side effect of an optional telemetry loop is a
coupling nobody would choose deliberately. Make it explicit.

## Design

### A second process-lifecycle observer

A new `core/session/SessionRevalidator`, mirroring `DiagnosticsPoller`'s shape and bound
beside it in `SpApp.onCreate()`:

```
SpApp.onCreate
 ├─ diagnosticsPoller.bind()
 └─ sessionRevalidator.bind()      // ProcessLifecycleOwner observer
```

```kotlin
override fun onStart(owner: LifecycleOwner) {
    if (sessionManager.sessionState.value != SessionState.Active) return
    scope.launch { authRepository.revalidateSession() }
}
```

A separate class rather than a few lines inside `DiagnosticsPoller`: the poller's contract
is that every failure is swallowed and nothing it does can affect the journey, which is the
opposite of what this needs to be able to do. Folding an auth decision into it would make
that KDoc a lie.

**`onStart`, not a per-Activity `ON_RESUME`.** `ProcessLifecycleOwner` fires `onStart` once
per foregrounding of the *process*, which is the event we actually mean; in a single-Activity
app the two coincide, and `onStart` is what `DiagnosticsPoller` already uses. Consistency is
worth more here than matching the phrase.

**The `Active` guard is the whole of the "no token, no call" rule.** On the sign-in screen
the state is `None` and nothing goes out.

**Ordering against the poller is a non-issue.** Both observers start on the same event. If
revalidation lands first and flips the state, the poller's `collectLatest` cancels its inner
loop. If the poller lands first with a dead token, its 401 flips the state through the same
interceptor. The outcome converges either way; neither needs to know about the other.

### The repository seam, and fail-open in the type

```kotlin
/** What one revalidation learned. Only [Ended] is allowed to end a session. */
enum class SessionCheck { Valid, Ended, Unknown }

suspend fun revalidateSession(): SessionCheck
```

- `Valid` — a 2xx. The session stands.
- `Ended` — an explicit 401. `AuthInterceptor` has *already* invalidated the session by the
  time the repository sees the response; the value is returned so callers and tests can
  assert on it, not so anything has to act on it.
- `Unknown` — everything else: 5xx, an unexpected status, `IOException`, `SocketTimeout`.
  **The session is left completely alone.**

Modelling the third case as its own value rather than folding it into a nullable or a
`Boolean` is the point of the enum. Fail-open stops being a rule someone has to remember and
becomes a case the compiler makes you name. Without it, a flaky clinic connection turns into
a forced re-login on every resume — which would be worse than the problem being solved, and
would be a self-inflicted denial of service on a device whose whole job is walking patients
through a gated journey.

Note the division of labour: **the repository never calls a `SessionManager` mutator.** It
classifies, and the interceptor — which already owns "a 401 on a tokened request ends the
session" for every other endpoint in the app — does the acting. One rule, one place, no
second code path that could drift from it.

### What the operator sees

Nothing new. The existing chain runs unchanged: `Invalidated` → `NavGraph` pops to sign-in →
`SignInViewModel` raises its "session ended" notice. No new screen, no new string, no new
`UiMessage`. The entire user-visible change is that this happens on resume instead of three
patient-facing steps later.

## Testing

Follows the existing pattern: JVM unit tests, MockK + JUnit4 + MockWebServer.

- **`AuthApiContractTest`** (already wires a real `AuthInterceptor` against MockWebServer —
  the only place the interceptor's behaviour is genuinely exercised):
  - a 200 leaves `sessionState` `Active`;
  - a 401 leaves it `Invalidated` — this is the one assertion that proves the routing works
    end to end, and a variant of it already exists for `api.session()`.
- **`AuthRepositoryTest`** (MockK over `AuthApi`), the fail-open matrix:
  - 200 → `Valid`; 401 → `Ended`;
  - 500 → `Unknown`; `IOException` → `Unknown`; `SocketTimeoutException` → `Unknown`;
  - and in every `Unknown` case, an assertion that `sessionState` is **still** `Active`.
    That assertion is the feature. It is the one that fails if someone later "simplifies"
    the enum away.
- **New `SessionRevalidatorTest`:**
  - `onStart` with an `Active` session calls `revalidateSession()` exactly once;
  - `onStart` with `None`/`Expired`/`Invalidated` does not call it at all;
  - two foregroundings produce two calls (no accidental once-per-process latch).

## Out of scope

- **Persisting the token / resuming across process death.** See the non-goal above.
- **Throttling repeated foregroundings.** One `GET /auth/session` per foreground is bounded
  by how fast a human can switch apps, and it is far cheaper than the poll that already goes
  out on the same event. Adding a minimum-interval guard would introduce state to hold and a
  window in which a known-dead session is treated as live — a bad trade for a saved request.
- **Acting on `SessionResource`'s contents.** The response carries a fresh `policy`,
  `provider` and `serverTime`; re-seeding the freshness window or the provider name from a
  resume is a separate change with its own reasoning, and mixing it in here would make a
  read-only check quietly mutate session state.
- **Reaching `SessionState.Expired`.** An expiry detected this way arrives as `Invalidated`,
  because the interceptor gets there first and the two are indistinguishable to the operator.
  Splitting them would mean either a second mutator call racing the first, or moving the 401
  rule out of the interceptor. Not worth it for a distinction nobody sees.
- **Revalidating on any trigger other than foregrounding** — no timer, no pre-flight check
  before individual journey steps.
