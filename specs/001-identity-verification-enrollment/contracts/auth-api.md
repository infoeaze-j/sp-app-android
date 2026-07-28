# Contract: Authentication & Session API

> **Superseded — do not implement from this file.** The back office has since published its real
> contract as `docs/openapi.json`, and the app is aligned to that. Paths, field names and status
> codes below are the provisional shapes this feature was designed against and are now wrong in
> places (login now answers 201 with a `SessionResource`, and the freshness window arrives as `policy.verificationTtlSeconds`). What still holds is the *client-side* reasoning — which outcomes are
> business rejections, which are transient, and what must never be reported as success.

**Feature**: 001-identity-verification-enrollment | **Consumer**: FaceVerify Android app | **Direction**: app → back office

Provisional contract for operator sign-in and session lifecycle (FR-001–FR-006). Field names/paths
are provisional and to be reconciled with the real back office; the **client interface**
(`client-interfaces.md → AuthRepository`) is stable regardless of wire shape.

## POST /auth/login

Authenticate an operator and establish a session.

**Request**
```json
{
  "identifier": "string (operator username/email)",
  "secret": "string (password/credential)"
}
```

**Success 200**
```json
{
  "token": "opaque-session-token",
  "expiresAt": "2026-07-20T12:34:56Z",
  "operator": { "operatorId": "string", "displayName": "string", "permissions": ["string"] },
  "config": { "verificationWindowSeconds": 900 }
}
```

`config.verificationWindowSeconds` is the back-office-owned verification-freshness window
(FR-026); the app enforces this returned value and MUST NOT hardcode it. If absent, the app
treats verification as immediately stale (fail-safe) and requires re-verification.

**Failure**
| HTTP | Meaning | App behavior |
|------|---------|--------------|
| 401 | Invalid credentials | Non-revealing error; no session created (FR-005). MUST NOT indicate whether identifier or secret was wrong. |
| 423 / 429 | Account locked / throttled | Show lockout/cooldown message; block further attempts (FR-006). Server owns lockout. |
| 5xx / timeout | Transient/unknown | `TransientFailure`/`Timeout`; explain connectivity, do NOT appear signed in (FR-005, edge case: no connectivity). |

**Rules**: Response `token` is transient (memory), redacted from all logs (FR-029). Lockout policy is
server-owned; the app enforces the returned state.

## POST /auth/logout

Invalidate the current session. Idempotent. Clears client verification state (FR-004a).

## GET /auth/session (or validation via any protected call)

Any protected endpoint returning **401** signals `Expired`/`Invalidated`; the app transitions to
`NotSignedIn`, discards all verification state (FR-004a), and routes to sign-in (FR-004).

**Auth header**: All protected requests attach the session token (e.g., `Authorization: Bearer <token>`)
(FR-002). Requests without a valid session are blocked client-side before dispatch (FR-003).

## Contract test expectations (MockWebServer)

- Valid creds → `Session.Active`, workspace unlocked. (AS-1)
- 401 login → no session, non-revealing message. (AS-2, FR-005)
- 401 on a protected call mid-flow → forced re-auth + verification state cleared. (AS-3, FR-004a)
- Network failure on login → connectivity message, not signed-in. (AS-4)
- Token never appears in logged output. (FR-029)
