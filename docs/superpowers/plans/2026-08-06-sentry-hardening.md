# Sentry Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `sentry-wizard`'s defaults with a fail-closed configuration that cannot transmit patient identity or biometric data, and that does not exhaust a 5,000-errors/month free tier from a single device.

**Architecture:** Auto-init is disabled; `SentryAndroid.init` is called from `SpApp.onCreate()` with a typed options block. All event filtering happens in a pure-Kotlin rules object (`ScrubRules`) wrapped by a thin Sentry-typed adapter (`SentryScrubber`) registered as `beforeSend`/`beforeBreadcrumb`. Debug builds report nothing unless a new Dev Settings toggle is on, read through a `SentryGate` seam bound differently in the debug and release source sets.

**Tech Stack:** Kotlin 2.3.10, AGP 9.2.1 / Gradle 9.4.1, Hilt, `io.sentry.android.gradle` 6.17.0 (auto-installs Sentry Android SDK 8.51.0), JUnit4 + MockK + Turbine, DataStore Preferences.

**Spec:** `docs/superpowers/specs/2026-08-06-sentry-hardening-design.md` (commit `62fb43d`)

## Global Constraints

- **No user-facing free text.** Nothing in this work adds a `UiMessage` or a string in `res/values/strings.xml`. Dev Settings labels are debug-only UI and are exempt (that source set ships no release code).
- **Never log or persist identity/biometric data.** This is the entire point of the work; every ambiguous case fails toward sending less.
- **detekt analyses `app/src/main/java` only** — `app/src/debug/java` is never scanned. So `core/crash/` must obey: functions ≤ 50 lines, line length ≤ 120, `ReturnCount` ≤ 4, and **`MagicNumber` is active** (only `-1, 0, 1, 2, 100` are exempt), so the digit thresholds `4` and `7` must be named constants. The Dev Settings UI changes carry no detekt risk.
- **The baseline detekt tally is 14 weighted issues.** Adding a row is a failure; a matching total is not proof of success — read the rows.
- **Dispatchers are injected**, never referenced directly: `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher` from `DispatchersModule`, using the `@param:` annotation-use-site form as in `DiagnosticsPoller`.
- **Test-first**, ≥ 80% coverage on changed code, explicit success *and* denial-path tests.
- `JAVA_HOME` must be set before any Gradle command: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
- The plugin **auto-installs** the Sentry SDK as an `implementation` dependency, so no `libs.versions.toml` library entry is needed and the `io.sentry.*` types are on the unit-test compile classpath.

---

### Task 1: Configuration — version catalog, plugin options, manifest, environment field

Removes every wizard default that leaks or burns quota, and moves the plugin onto the version catalog. Config-only, but independently verifiable: a debug unit test pins the new `BuildConfig` field, and the build proves the plugin still resolves.

**Files:**
- Modify: `gradle/libs.versions.toml` (`[versions]` and `[plugins]`)
- Modify: `app/build.gradle.kts:7-11` (plugins block), `:62-79` (buildTypes), `:204-213` (the wizard's `sentry {}` block)
- Modify: `app/src/main/AndroidManifest.xml:71-89` (the wizard's meta-data block)
- Test: `app/src/testDebug/java/com/mediplus/spapp/core/crash/SentryEnvironmentTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `BuildConfig.SENTRY_ENVIRONMENT: String` — `"development"` in debug, `"production"` in release. Task 4 reads it.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/com/mediplus/spapp/core/crash/SentryEnvironmentTest.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import com.mediplus.spapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The environment tag separates dev churn from field crashes. The wizard left it unset, which the
 * SDK defaults to "production" — so an emulator run was indistinguishable from a clinic device.
 */
class SentryEnvironmentTest {

    @Test
    fun `debug builds report as development`() {
        assertEquals("development", BuildConfig.SENTRY_ENVIRONMENT)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryEnvironmentTest"
```

Expected: compilation failure — `Unresolved reference: SENTRY_ENVIRONMENT`.

- [ ] **Step 3: Add the version catalog entries**

In `gradle/libs.versions.toml`, add to `[versions]` (keep the list's existing alphabetical position):

```toml
sentry = "6.17.0"
```

and to `[plugins]`:

```toml
sentry = { id = "io.sentry.android.gradle", version.ref = "sentry" }
```

- [ ] **Step 4: Switch the plugin declaration to the catalog**

In `app/build.gradle.kts`, replace the wizard's line:

```kotlin
    id("io.sentry.android.gradle") version "6.17.0"
```

with, inside the existing `plugins { }` block alongside the other aliases:

```kotlin
    alias(libs.plugins.sentry)
```

- [ ] **Step 5: Add the environment field to both build types**

In `app/build.gradle.kts`, inside `buildTypes`, add one line to each block beside the existing `BASE_URL` field:

```kotlin
        debug {
            // Local Docker back office on the LAN (plain HTTP; see network_security_config.xml).
            buildConfigField("String", "BASE_URL", "\"http://10.21.2.82:8080/api/v1/\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"development\"")
            enableUnitTestCoverage = true
        }
```

```kotlin
            buildConfigField("String","BASE_URL","\"https://bio.infoeaze.com/api/v1/\"")
            buildConfigField("String", "SENTRY_ENVIRONMENT", "\"production\"")
```

- [ ] **Step 6: Replace the wizard's `sentry {}` block**

In `app/build.gradle.kts`, replace the whole trailing block the wizard appended:

```kotlin
sentry {
    org.set("infoeaze")
    projectName.set("android")

    // this will upload your source code to Sentry to show it as part of the stack traces
    // disable if you don't want to expose your sources
    includeSourceContext.set(true)
}
```

with:

```kotlin
sentry {
    org.set("infoeaze")
    projectName.set("android")

    // No source upload: release is unminified (`optimization { enable = false }`), so stack traces
    // already carry real file names and line numbers. Uploading would put health-app source on a
    // third-party SaaS and make sentry.properties a second single-machine secret gating every
    // shippable APK, alongside the keystore.
    includeSourceContext.set(false)

    // OkHttp only. This stops Logcat, file-IO, database and Compose instrumentation weaving in at
    // all, rather than relying on the scrubber to discard what they produce — Logcat instrumentation
    // in particular would forward every third-party Log.* call, which LoggingRedactionTest cannot
    // govern. OkHttp is kept deliberately even though tracesSampleRate is 0.0: its event listener
    // emits HTTP breadcrumbs independently of trace sampling, so we keep "which call failed" while
    // emitting no spans. SentryScrubber templates the URLs.
    tracingInstrumentation {
        features.set(setOf(InstrumentationFeature.OKHTTP))
    }
}
```

Add the import at the top of `app/build.gradle.kts`, beside the existing imports:

```kotlin
import io.sentry.android.gradle.extensions.InstrumentationFeature
```

- [ ] **Step 7: Strip the wizard's manifest meta-data**

In `app/src/main/AndroidManifest.xml`, replace the entire wizard-inserted block (from `<!-- Required: set your sentry.io project identifier (DSN) -->` through the `traces.sample-rate` meta-data, and restore the mangled `</application>` indentation) with just these two entries, kept inside `<application>`:

```xml
        <!--
          The DSN is not a secret — it ships inside every APK. Everything else is configured in code
          in SentryCrashReporting, because beforeSend/beforeBreadcrumb are only settable there and
          screenshots must be never-captured rather than discarded after the fact.
        -->
        <meta-data android:name="io.sentry.dsn" android:value="https://8e93f62ceab7e1e874f1673235179d62@o4511863682629632.ingest.de.sentry.io/4511863688790096" />
        <meta-data android:name="io.sentry.auto-init" android:value="false" />
    </application>
```

- [ ] **Step 8: Run the test to confirm it passes**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryEnvironmentTest"
```

Expected: PASS. If the `InstrumentationFeature` import does not resolve, run `.\gradlew :app:dependencies --configuration classpath` and correct the FQN from the plugin's own jar; the enum is the plugin's public extension type and the block will not compile without it.

- [ ] **Step 9: Confirm the build still assembles**

```powershell
.\gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. `transformDebugClassesWithAsm` should still run (OkHttp instrumentation), and no `sentryUploadSourceBundle*` task should appear.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/testDebug/java/com/mediplus/spapp/core/crash/SentryEnvironmentTest.kt
git commit -m "fix: strip the Sentry wizard's leaking defaults from the build config

Screenshots, view hierarchy, user-interaction tracing and 100% trace
sampling are all gone; source upload is off; the plugin moves onto the
version catalog; auto-init is disabled so the options block in Task 4
can own every remaining decision. Adds SENTRY_ENVIRONMENT per build type
so dev churn stops arriving tagged as production.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `ScrubRules` — the pure fail-closed filter

The heart of the work, and pure Kotlin with no Android and no Sentry dependency, so it is exhaustively testable in the JVM suite.

**Deliberate refinement over the spec:** the spec described one `EventScrubber.kt` operating on Sentry types. Splitting the decision rules (pure, `String`/`Set`-only) from the Sentry-typed adapter (Task 3) means the rules get tested with zero API risk and the adapter stays small enough to read in one screen.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/crash/ScrubRules.kt`
- Test: `app/src/test/java/com/mediplus/spapp/core/crash/ScrubRulesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, all on `object ScrubRules`:
  - `fun isAllowedCategory(category: String?): Boolean`
  - `fun templateUrl(url: String): String`
  - `fun redactDigitRuns(text: String?): String?`
  - `val allowedHttpDataKeys: Set<String>`
  - `const val ID_PLACEHOLDER = "{id}"`, `const val REDACTED = "{redacted}"`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/mediplus/spapp/core/crash/ScrubRulesTest.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed contract. Anything not explicitly permitted must be refused, so that a future SDK
 * upgrade or a newly added endpoint cannot leak by default. Companion to [LoggingRedactionTest],
 * which pins the same guarantee for logcat.
 */
class ScrubRulesTest {

    private val memberNumber = "634743753"
    private val baseUrl = "https://bio.infoeaze.com/api/v1"

    @Test
    fun `a member number in a path segment is templated away`() {
        val scrubbed = ScrubRules.templateUrl("$baseUrl/members/$memberNumber/services")

        assertEquals("$baseUrl/members/{id}/services", scrubbed)
        assertFalse(scrubbed.contains(memberNumber))
    }

    @Test
    fun `the enrollments endpoint is templated too`() {
        assertEquals(
            "$baseUrl/members/{id}/enrollments",
            ScrubRules.templateUrl("$baseUrl/members/$memberNumber/enrollments"),
        )
    }

    @Test
    fun `the query string is dropped whole`() {
        assertEquals(
            "$baseUrl/app/releases/latest",
            ScrubRules.templateUrl("$baseUrl/app/releases/latest?versionCode=5"),
        )
    }

    @Test
    fun `the endpoint shape and host survive so the failing call is still identifiable`() {
        val scrubbed = ScrubRules.templateUrl("$baseUrl/members/$memberNumber/services")

        assertTrue(scrubbed.startsWith("https://bio.infoeaze.com"))
        assertTrue(scrubbed.endsWith("/services"))
    }

    @Test
    fun `short numeric segments and version segments are left alone`() {
        assertEquals("$baseUrl/auth/session", ScrubRules.templateUrl("$baseUrl/auth/session"))
        assertEquals(
            "https://host/api/v1/diagnostics/requests/pending",
            ScrubRules.templateUrl("https://host/api/v1/diagnostics/requests/pending"),
        )
    }

    @Test
    fun `a segment that merely contains digits is not templated`() {
        assertEquals("https://host/v1/abc1234def", ScrubRules.templateUrl("https://host/v1/abc1234def"))
    }

    @Test
    fun `allowed categories pass`() {
        listOf("navigation", "app.lifecycle", "ui.lifecycle", "network.event", "http").forEach {
            assertTrue("expected $it to be allowed", ScrubRules.isAllowedCategory(it))
        }
    }

    @Test
    fun `logcat and user interaction categories are refused`() {
        assertFalse(ScrubRules.isAllowedCategory("logcat"))
        assertFalse(ScrubRules.isAllowedCategory("ui.click"))
    }

    @Test
    fun `an unrecognised category is refused - this is the fail-closed property`() {
        assertFalse(ScrubRules.isAllowedCategory("some.future.sdk.category"))
        assertFalse(ScrubRules.isAllowedCategory(""))
        assertFalse(ScrubRules.isAllowedCategory(null))
    }

    @Test
    fun `a long digit run in a message is redacted`() {
        assertEquals(
            "failed to parse member {redacted}",
            ScrubRules.redactDigitRuns("failed to parse member $memberNumber"),
        )
    }

    @Test
    fun `short numbers in messages survive`() {
        assertEquals("HTTP 401 after 3 retries", ScrubRules.redactDigitRuns("HTTP 401 after 3 retries"))
    }

    @Test
    fun `null text stays null`() {
        assertNull(ScrubRules.redactDigitRuns(null))
    }

    @Test
    fun `only url method and status are permitted http breadcrumb data`() {
        assertEquals(setOf("url", "method", "status_code"), ScrubRules.allowedHttpDataKeys)
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.ScrubRulesTest"
```

Expected: compilation failure — `Unresolved reference: ScrubRules`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/mediplus/spapp/core/crash/ScrubRules.kt`:

```kotlin
package com.mediplus.spapp.core.crash

/**
 * The fail-closed filter deciding what may leave the device in a crash report.
 *
 * Pure by design — no Android, no Sentry types — so every rule is exhaustively unit-testable and the
 * Sentry-shaped adapter in [SentryScrubber] stays thin. Nothing here is a denylist: an unrecognised
 * breadcrumb category is refused, so a future SDK upgrade that introduces a new one leaks nothing
 * until someone opts it in. Same fail-safe direction as a null freshness window counting as stale.
 */
object ScrubRules {

    const val ID_PLACEHOLDER = "{id}"
    const val REDACTED = "{redacted}"

    /**
     * A wholly-numeric path segment is an identifier essentially by definition, so the bar is low.
     * `MemberNumber` is `^[0-9]{7,32}$`, so 4 leaves margin without touching `/v1/`.
     */
    private const val MIN_ID_DIGITS_IN_PATH = 4

    /**
     * A message is prose that may contain incidental small numbers (status codes, retry counts), so
     * the bar is the shortest real member number. Accepted cost: a legitimate 7+ digit byte count or
     * epoch millis in a message is redacted too.
     */
    private const val MIN_ID_DIGITS_IN_TEXT = 7

    private val NUMERIC_SEGMENT = Regex("^\\d{$MIN_ID_DIGITS_IN_PATH,}$")
    private val LONG_DIGIT_RUN = Regex("\\d{$MIN_ID_DIGITS_IN_TEXT,}")

    /** Breadcrumb categories permitted to leave the device. Everything else is dropped. */
    private val ALLOWED_CATEGORIES = setOf(
        // Static route names only: AppRoute carries nothing but its path and no destination takes
        // arguments, so these can never contain an identifier.
        "navigation",
        "app.lifecycle",
        "ui.lifecycle",
        // Bandwidth and wifi-vs-cellular. Genuinely useful for a flaky clinic connection; no identity.
        "network.event",
        // Kept only because templateUrl strips the member number from the path.
        "http",
    )

    /** The only breadcrumb data keys an http crumb may carry out. */
    val allowedHttpDataKeys: Set<String> = setOf("url", "method", "status_code")

    fun isAllowedCategory(category: String?): Boolean = category in ALLOWED_CATEGORIES

    /**
     * Replaces every wholly-numeric path segment with [ID_PLACEHOLDER] and drops the query string,
     * so `members/634743753/services` becomes `members/{id}/services`. Scheme, host and endpoint
     * shape survive — enough to know which call failed.
     */
    fun templateUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        return path.split('/')
            .joinToString("/") { segment -> if (NUMERIC_SEGMENT.matches(segment)) ID_PLACEHOLDER else segment }
    }

    /** Replaces long digit runs anywhere in free text, guarding against interpolated identifiers. */
    fun redactDigitRuns(text: String?): String? = text?.replace(LONG_DIGIT_RUN, REDACTED)
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.ScrubRulesTest"
```

Expected: 13 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/crash/ScrubRules.kt app/src/test/java/com/mediplus/spapp/core/crash/ScrubRulesTest.kt
git commit -m "feat: add the fail-closed scrub rules for crash reports

Allowlists breadcrumb categories, templates numeric path segments out of
request URLs, drops query strings, and redacts long digit runs from
message text. Pure Kotlin so the rules are exhaustively testable; the
Sentry-typed adapter follows separately.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `SentryScrubber` — the Sentry-typed adapter

Wraps `ScrubRules` in the two callbacks Sentry actually invokes. Tested against real `SentryEvent`/`Breadcrumb`/`Hint` instances, which are pure-JVM types from the `sentry` core artifact and available on the unit-test classpath.

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/crash/SentryScrubber.kt`
- Test: `app/src/test/java/com/mediplus/spapp/core/crash/SentryScrubberTest.kt`

**Interfaces:**
- Consumes: `ScrubRules.isAllowedCategory`, `ScrubRules.templateUrl`, `ScrubRules.redactDigitRuns`, `ScrubRules.allowedHttpDataKeys` from Task 2.
- Produces: `class SentryScrubber : SentryOptions.BeforeSendCallback, SentryOptions.BeforeBreadcrumbCallback`, with `execute(event: SentryEvent, hint: Hint): SentryEvent?` and `execute(breadcrumb: Breadcrumb, hint: Hint): Breadcrumb?`. Task 4 registers a single instance as both callbacks.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/mediplus/spapp/core/crash/SentryScrubberTest.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter that Sentry actually calls. Denial paths matter more than success paths here: every
 * assertion below is about something NOT reaching the wire.
 */
class SentryScrubberTest {

    private val scrubber = SentryScrubber()
    private val memberNumber = "634743753"

    private fun httpCrumb(url: String): Breadcrumb = Breadcrumb().apply {
        category = "http"
        setData("url", url)
        setData("method", "GET")
        setData("status_code", 500)
    }

    @Test
    fun `an http breadcrumb keeps its shape and loses the member number`() {
        val result = scrubber.execute(
            httpCrumb("https://bio.infoeaze.com/api/v1/members/$memberNumber/services"),
            Hint(),
        )

        assertNotNull(result)
        assertEquals(
            "https://bio.infoeaze.com/api/v1/members/{id}/services",
            result!!.data["url"],
        )
        assertEquals("GET", result.data["method"])
        assertFalse(result.data.values.any { it.toString().contains(memberNumber) })
    }

    @Test
    fun `unexpected http breadcrumb data is dropped`() {
        val crumb = httpCrumb("https://host/api/v1/auth/session").apply {
            setData("http.query", "token=abc")
            setData("request_body", memberNumber)
        }

        val result = scrubber.execute(crumb, Hint())

        assertNotNull(result)
        assertEquals(setOf("url", "method", "status_code"), result!!.data.keys)
    }

    @Test
    fun `a logcat breadcrumb is dropped`() {
        val crumb = Breadcrumb().apply {
            category = "logcat"
            message = "member $memberNumber verified"
        }

        assertNull(scrubber.execute(crumb, Hint()))
    }

    @Test
    fun `a user interaction breadcrumb is dropped`() {
        assertNull(scrubber.execute(Breadcrumb().apply { category = "ui.click" }, Hint()))
    }

    @Test
    fun `a navigation breadcrumb survives`() {
        val crumb = Breadcrumb().apply {
            category = "navigation"
            setData("from", "/signin")
            setData("to", "/memberscan")
        }

        assertNotNull(scrubber.execute(crumb, Hint()))
    }

    @Test
    fun `an unrecognised breadcrumb category is dropped`() {
        assertNull(scrubber.execute(Breadcrumb().apply { category = "some.future.category" }, Hint()))
    }

    @Test
    fun `a breadcrumb with no category is dropped`() {
        assertNull(scrubber.execute(Breadcrumb(), Hint()))
    }

    @Test
    fun `a url that is not a string is removed rather than passed through`() {
        // Sentry's data map is Map<String, Any>, so nothing guarantees the url is a String. An
        // unrecognised value must not survive: it would be serialised verbatim into the envelope.
        val crumb = Breadcrumb().apply {
            category = "http"
            setData("url", Any())
            setData("method", "GET")
        }

        val result = scrubber.execute(crumb, Hint())!!

        assertFalse(result.data.containsKey("url"))
        assertEquals("GET", result.data["method"])
    }

    @Test
    fun `request data is dropped from the event`() {
        val event = SentryEvent().apply {
            request = Request().apply {
                url = "https://host/api/v1/members/$memberNumber/services"
                queryString = "x=1"
                cookies = "session=abc"
            }
        }

        assertNull(scrubber.execute(event, Hint()).request)
    }

    @Test
    fun `user is reduced to id only`() {
        val event = SentryEvent().apply {
            user = User().apply {
                id = "install-uuid"
                username = "sam"
                email = "sam@clinic.example"
                ipAddress = "10.0.0.5"
            }
        }

        val result = scrubber.execute(event, Hint())!!

        assertEquals("install-uuid", result.user!!.id)
        assertNull(result.user!!.username)
        assertNull(result.user!!.email)
        assertNull(result.user!!.ipAddress)
    }

    @Test
    fun `a member number interpolated into an exception message is redacted`() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalArgumentException"
                    value = "cannot parse member $memberNumber"
                },
            )
        }

        val result = scrubber.execute(event, Hint())!!

        assertEquals("cannot parse member {redacted}", result.exceptions!!.first().value)
    }

    @Test
    fun `a member number in the event message is redacted`() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "lookup failed for $memberNumber" }
        }

        val result = scrubber.execute(event, Hint())!!

        assertEquals("lookup failed for {redacted}", result.message!!.formatted)
    }

    @Test
    fun `attachments are cleared as a backstop`() {
        val hint = Hint()
        hint.addAttachment(io.sentry.Attachment(ByteArray(4), "screenshot.png"))

        scrubber.execute(SentryEvent(), hint)

        assertTrue(hint.attachments.isEmpty())
    }

    @Test
    fun `the golden payload from the 2026-08-06 on-device capture leaks nothing`() {
        // Mirrors the breadcrumb set actually observed in the captured envelope, plus the member
        // number the fake stack prevented us from observing at the time.
        val crumbs = listOf(
            Breadcrumb().apply { category = "navigation"; setData("from", "/signin"); setData("to", "/signin") },
            Breadcrumb().apply { category = "network.event"; setData("network_type", "wifi") },
            Breadcrumb().apply { category = "logcat"; message = "uid $memberNumber" },
            httpCrumb("https://bio.infoeaze.com/api/v1/members/$memberNumber/enrollments"),
        )

        val kept = crumbs.mapNotNull { scrubber.execute(it, Hint()) }

        assertEquals(3, kept.size)
        assertFalse(kept.toString().contains(memberNumber))
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryScrubberTest"
```

Expected: compilation failure — `Unresolved reference: SentryScrubber`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/mediplus/spapp/core/crash/SentryScrubber.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.User

/**
 * Applies [ScrubRules] to everything Sentry is about to transmit. Registered as both
 * `beforeSend` and `beforeBreadcrumb` in [SentryCrashReporting].
 *
 * Every path fails towards sending less: a breadcrumb whose scrubbing throws is dropped rather than
 * forwarded, which is why the bodies are wrapped in `runCatching`. Returning null from either
 * callback discards the item.
 */
class SentryScrubber :
    SentryOptions.BeforeSendCallback,
    SentryOptions.BeforeBreadcrumbCallback {

    override fun execute(breadcrumb: Breadcrumb, hint: Hint): Breadcrumb? = runCatching {
        if (!ScrubRules.isAllowedCategory(breadcrumb.category)) {
            null
        } else {
            breadcrumb.also { if (it.category == HTTP_CATEGORY) scrubHttpData(it) }
        }
    }.getOrNull()

    override fun execute(event: SentryEvent, hint: Hint): SentryEvent {
        // A failure here must not lose the crash itself, so the event is always returned — but every
        // field that could carry identity is cleared before the first thing that might throw.
        runCatching {
            hint.clearAttachments()
            event.request = null
            event.user = event.user?.let { original -> User().apply { id = original.id } }
            event.message = event.message?.also { it.formatted = ScrubRules.redactDigitRuns(it.formatted) }
            event.exceptions = event.exceptions?.onEach { it.value = ScrubRules.redactDigitRuns(it.value) }
        }
        return event
    }

    /**
     * Keep only the allowlisted keys, and template the identifier out of the URL.
     *
     * A url that is not a String is *removed*, not left in place: the data map is `Map<String, Any>`,
     * so nothing guarantees the type, and an unrecognised value would be serialised into the envelope
     * exactly as it arrived. Fail towards sending less.
     */
    private fun scrubHttpData(breadcrumb: Breadcrumb) {
        val url = breadcrumb.data[URL_KEY] as? String
        breadcrumb.data.keys.retainAll(ScrubRules.allowedHttpDataKeys)
        if (url == null) {
            breadcrumb.data.remove(URL_KEY)
        } else {
            breadcrumb.setData(URL_KEY, ScrubRules.templateUrl(url))
        }
    }

    private companion object {
        const val HTTP_CATEGORY = "http"
        const val URL_KEY = "url"
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryScrubberTest"
```

Expected: 14 tests, all PASS.

Two API details to correct here if the compiler disagrees, both mechanical. If `Breadcrumb.getData()` returns an immutable map, `retainAll` will throw at runtime and the `unexpected http breadcrumb data is dropped` test will fail — in that case rebuild the crumb instead: create a `Breadcrumb()`, copy `type`, `category`, `level` and `message`, then `setData` only the allowlisted keys. If `hint.clearAttachments()` is absent in 8.51.0, use `hint.attachments.clear()`. Do not "fix" either by weakening an assertion.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/crash/SentryScrubber.kt app/src/test/java/com/mediplus/spapp/core/crash/SentryScrubberTest.kt
git commit -m "feat: apply the scrub rules to outgoing Sentry events

Drops non-allowlisted breadcrumbs, templates member numbers out of http
breadcrumb URLs, keeps only url/method/status_code, nulls the request,
reduces user to an id, and redacts digit runs from message and exception
text. Includes a golden-payload case built from the envelope captured
on-device on 2026-08-06.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `SentryGate` — debug off by default, behind a Dev Settings toggle

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/crash/SentryGate.kt`
- Create: `app/src/release/java/com/mediplus/spapp/core/di/SentryGateModule.kt`
- Create: `app/src/debug/java/com/mediplus/spapp/core/di/SentryGateModule.kt`
- Create: `app/src/debug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGate.kt`
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/DevSettings.kt` (add field + pref key + mapping)
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/DevSettingsStore.kt` (add setter to interface + impl)
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/ui/DevSettingsViewModel.kt` (add `setSentryEnabled`)
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/ui/DevSettingsScreen.kt` (add toggle row + param)
- Modify: `app/src/debug/java/com/mediplus/spapp/dev/ui/DevSettingsActivity.kt` (wire the param)
- Test: `app/src/testDebug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGateTest.kt`

**Interfaces:**
- Consumes: `DevSettingsStore.settings: Flow<DevSettings>` and the new `DevSettings.sentryEnabled: Boolean`.
- Produces: `interface SentryGate { fun isEnabled(): Boolean }`; `class AlwaysOnSentryGate` (release); `class DevSettingsSentryGate` with `fun start()` (debug). Task 5 calls `isEnabled()` from `beforeSend`.

- [ ] **Step 1: Write the failing test**

Create `app/src/testDebug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGateTest.kt`:

```kotlin
package com.mediplus.spapp.dev.crash

import com.mediplus.spapp.core.crash.SentryGate
import com.mediplus.spapp.dev.DevSettings
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * beforeSend is synchronous but DevSettingsStore reads suspend, so the gate holds a snapshot fed by
 * a collector. The snapshot must start closed: a debug build that crashes before the first emission
 * sends nothing.
 */
class DevSettingsSentryGateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val emissions = MutableSharedFlow<DevSettings>(replay = 0, extraBufferCapacity = 4)
    private val store: DevSettingsStore = mockk(relaxed = true) {
        every { settings } returns emissions
    }

    @Test
    fun `the gate is closed before the first emission`() {
        val gate: SentryGate = DevSettingsSentryGate(store, UnconfinedTestDispatcher())

        assertFalse(gate.isEnabled())
    }

    @Test
    fun `the gate opens only once the toggle is on`() = runTest {
        val gate = DevSettingsSentryGate(store, UnconfinedTestDispatcher())
        gate.start()

        emissions.emit(DevSettings(sentryEnabled = false))
        assertFalse(gate.isEnabled())

        emissions.emit(DevSettings(sentryEnabled = true))
        assertTrue(gate.isEnabled())
    }

    @Test
    fun `the gate closes again when the toggle is turned off`() = runTest {
        val gate = DevSettingsSentryGate(store, UnconfinedTestDispatcher())
        gate.start()

        emissions.emit(DevSettings(sentryEnabled = true))
        emissions.emit(DevSettings(sentryEnabled = false))

        assertFalse(gate.isEnabled())
    }

    @Test
    fun `a store that never emits leaves the gate closed`() = runTest {
        val silent: DevSettingsStore = mockk(relaxed = true) {
            every { settings } returns MutableSharedFlow()
        }
        val gate = DevSettingsSentryGate(silent, UnconfinedTestDispatcher())
        gate.start()

        assertFalse(gate.isEnabled())
    }
}
```

The test deliberately does not use Turbine: the thing under test is a *synchronous snapshot*, not a
flow, so the assertions read the snapshot directly after each emission.

- [ ] **Step 2: Run it to confirm it fails**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.dev.crash.DevSettingsSentryGateTest"
```

Expected: compilation failure — `Unresolved reference: DevSettingsSentryGate` and `No value passed for parameter 'sentryEnabled'`.

- [ ] **Step 3: Add the seam interface**

Create `app/src/main/java/com/mediplus/spapp/core/crash/SentryGate.kt`:

```kotlin
package com.mediplus.spapp.core.crash

/**
 * Whether crash reports may leave this build right now.
 *
 * Release binds an always-open gate. Debug binds one driven by a Dev Settings toggle that defaults
 * off, so dev churn and the fake stack's deliberate failures never spend the fleet's shared
 * free-tier quota. Consulted from `beforeSend`, which is synchronous — implementations must not
 * block.
 */
interface SentryGate {
    fun isEnabled(): Boolean
}

/**
 * A gate that needs a coroutine running before it can answer. Debug-only in practice; crash-reporting
 * init starts it if the bound gate happens to be one.
 */
interface Startable {
    fun start()
}

/** Release: always report. */
class AlwaysOnSentryGate : SentryGate {
    override fun isEnabled(): Boolean = true
}
```

- [ ] **Step 4: Add the debug gate**

Create `app/src/debug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGate.kt`:

```kotlin
package com.mediplus.spapp.dev.crash

import com.mediplus.spapp.core.crash.SentryGate
import com.mediplus.spapp.core.crash.Startable
import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.dev.DevSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the dev toggle for crash reporting. `beforeSend` is synchronous and DataStore reads suspend,
 * so the answer is a snapshot kept current by a collector started in [start].
 *
 * The snapshot starts `false` and only ever changes on an actual emission: a debug build that
 * crashes before the store has been read sends nothing. Fail-closed, like a null freshness window
 * counting as stale.
 */
@Singleton
class DevSettingsSentryGate @Inject constructor(
    private val store: DevSettingsStore,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : SentryGate, Startable {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var enabled: Boolean = false

    override fun isEnabled(): Boolean = enabled

    /** Begin tracking the toggle. Called once, from crash-reporting init. */
    override fun start() {
        scope.launch {
            store.settings.collect { enabled = it.sentryEnabled }
        }
    }
}
```

- [ ] **Step 5: Add the setting**

In `app/src/debug/java/com/mediplus/spapp/dev/DevSettings.kt`, add the field to the data class after `diagnostics`:

```kotlin
    val diagnostics: DiagnosticsScenario = DiagnosticsScenario.OFF,
    /** Off by default: dev crashes must not spend the fleet's shared free-tier quota. */
    val sentryEnabled: Boolean = false,
```

add the key to `DevPrefKeys`:

```kotlin
    val SENTRY_ENABLED = booleanPreferencesKey("dev_sentry_enabled")
```

and the mapping line in `Preferences.toDevSettings()`:

```kotlin
        sentryEnabled = this[DevPrefKeys.SENTRY_ENABLED] ?: defaults.sentryEnabled,
```

- [ ] **Step 6: Add the store setter**

In `app/src/debug/java/com/mediplus/spapp/dev/DevSettingsStore.kt`, add to the interface:

```kotlin
    suspend fun setSentryEnabled(enabled: Boolean)
```

and to `DataStoreDevSettingsStore`:

```kotlin
    override suspend fun setSentryEnabled(enabled: Boolean) =
        edit { it[DevPrefKeys.SENTRY_ENABLED] = enabled }
```

- [ ] **Step 7: Bind the gate in both source sets**

Create `app/src/release/java/com/mediplus/spapp/core/di/SentryGateModule.kt`:

```kotlin
package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.crash.AlwaysOnSentryGate
import com.mediplus.spapp.core.crash.SentryGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: crash reporting is always on. */
@Module
@InstallIn(SingletonComponent::class)
object SentryGateModule {

    @Provides
    @Singleton
    fun provideSentryGate(): SentryGate = AlwaysOnSentryGate()
}
```

Create `app/src/debug/java/com/mediplus/spapp/core/di/SentryGateModule.kt`:

```kotlin
package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.crash.SentryGate
import com.mediplus.spapp.dev.crash.DevSettingsSentryGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: crash reporting is off unless the Dev Settings toggle says otherwise. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SentryGateModule {

    @Binds
    @Singleton
    abstract fun bindSentryGate(impl: DevSettingsSentryGate): SentryGate
}
```

- [ ] **Step 8: Run the gate tests to confirm they pass**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.dev.crash.DevSettingsSentryGateTest"
```

Expected: 4 tests, all PASS.

- [ ] **Step 9: Add the Dev Settings toggle**

In `DevSettingsViewModel.kt`, beside the other setters:

```kotlin
    fun setSentryEnabled(enabled: Boolean) = launchEdit { store.setSentryEnabled(enabled) }
```

In `DevSettingsScreen.kt`, add `onSentryEnabled: (Boolean) -> Unit` to the `DevSettingsScreen` parameter list, call the new row just before `DevActions(...)` at the end of the `Column`:

```kotlin
        SentryToggle(settings.sentryEnabled, onSentryEnabled)
```

and add the composable beside `MasterToggle`, following its shape exactly:

```kotlin
/**
 * Crash reporting is off in debug by default: the free tier is 5,000 errors/month for the whole
 * fleet, and dev churn plus the fake stack's deliberate failures would eat it.
 */
@Composable
private fun SentryToggle(sentryEnabled: Boolean, onSentryEnabled: (Boolean) -> Unit) {
    Text("Crash reporting", style = MaterialTheme.typography.headlineSmall)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Send crashes to Sentry")
            Text(
                text = "Off by default — debug events spend the fleet's shared monthly quota.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Switch(checked = sentryEnabled, onCheckedChange = onSentryEnabled)
    }
}
```

In `DevSettingsActivity.kt`, wire it into the `DevSettingsScreen(...)` call beside the other handlers:

```kotlin
                        onSentryEnabled = vm::setSentryEnabled,
```

- [ ] **Step 10: Run the whole debug suite**

```powershell
.\gradlew testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `DevSettingsMappingTest` and `DevSettingsViewModelTest` must still pass — if either asserts on an exhaustive `DevSettings` copy, update it to include `sentryEnabled = false`.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/crash/SentryGate.kt app/src/release/java/com/mediplus/spapp/core/di/SentryGateModule.kt app/src/debug/java/com/mediplus/spapp/core/di/SentryGateModule.kt app/src/debug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGate.kt app/src/debug/java/com/mediplus/spapp/dev/DevSettings.kt app/src/debug/java/com/mediplus/spapp/dev/DevSettingsStore.kt app/src/debug/java/com/mediplus/spapp/dev/ui/ app/src/testDebug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGateTest.kt
git commit -m "feat: gate crash reporting behind a Dev Settings toggle in debug

Release always reports; debug reports only when the new toggle is on,
defaulting off so dev churn does not spend the fleet's shared 5k/month
free tier. The gate is a volatile snapshot fed by a collector because
beforeSend is synchronous while DataStore reads suspend, and it starts
closed so a crash before the first emission sends nothing.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `SentryCrashReporting` — init, options, and app wiring

**Files:**
- Create: `app/src/main/java/com/mediplus/spapp/core/crash/CrashReporting.kt`
- Create: `app/src/main/java/com/mediplus/spapp/core/crash/SentryCrashReporting.kt`
- Create: `app/src/main/java/com/mediplus/spapp/core/di/CrashModule.kt`
- Modify: `app/src/main/java/com/mediplus/spapp/SpApp.kt`
- Test: `app/src/test/java/com/mediplus/spapp/core/crash/SentryCrashReportingTest.kt`

**Interfaces:**
- Consumes: `SentryScrubber` (Task 3), `SentryGate.isEnabled()` (Task 4), `BuildConfig.SENTRY_ENVIRONMENT` (Task 1), `PrefsDataStore.installId(): String`.
- Produces: `interface CrashReporting { fun init() }`, bound to `SentryCrashReporting`. Called once from `SpApp.onCreate()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/spapp/core/crash/SentryCrashReportingTest.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroidOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The options block is the whole configuration surface, so it is asserted directly rather than
 * through a live SDK init (which needs Android). Every value here was wrong in the wizard's output.
 */
class SentryCrashReportingTest {

    private fun configured(gateOpen: Boolean): SentryAndroidOptions =
        SentryAndroidOptions().also { options ->
            SentryCrashReporting.configure(
                options = options,
                environment = "production",
                gate = object : SentryGate {
                    override fun isEnabled(): Boolean = gateOpen
                },
            )
        }

    @Test
    fun `screenshots and view hierarchy are off`() {
        val options = configured(gateOpen = true)

        assertFalse(options.isAttachScreenshot)
        assertFalse(options.isAttachViewHierarchy)
    }

    @Test
    fun `user interaction capture is off`() {
        val options = configured(gateOpen = true)

        assertFalse(options.isEnableUserInteractionBreadcrumbs)
        assertFalse(options.isEnableUserInteractionTracing)
    }

    @Test
    fun `performance tracing is off but crash and ANR capture stay on`() {
        val options = configured(gateOpen = true)

        assertEquals(0.0, options.tracesSampleRate!!, 0.0)
        assertTrue(options.isAnrEnabled)
        assertTrue(options.isEnableUncaughtExceptionHandler)
    }

    @Test
    fun `pii and module reporting are off and the environment is tagged`() {
        val options = configured(gateOpen = true)

        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isSendModules)
        assertEquals("production", options.environment)
    }

    @Test
    fun `the scrubber is installed on both callbacks`() {
        val options = configured(gateOpen = true)

        assertNotNull(options.beforeSend)
        assertNotNull(options.beforeBreadcrumb)
    }

    @Test
    fun `an open gate lets an event through`() {
        val options = configured(gateOpen = true)

        assertNotNull(options.beforeSend!!.execute(SentryEvent(), Hint()))
    }

    @Test
    fun `a closed gate drops the event entirely`() {
        val options = configured(gateOpen = false)

        assertNull(options.beforeSend!!.execute(SentryEvent(), Hint()))
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryCrashReportingTest"
```

Expected: compilation failure — `Unresolved reference: SentryCrashReporting`.

- [ ] **Step 3: Add the interface**

Create `app/src/main/java/com/mediplus/spapp/core/crash/CrashReporting.kt`:

```kotlin
package com.mediplus.spapp.core.crash

/**
 * Starts crash reporting. Best-effort throughout: nothing here may affect the patient journey, in
 * the same spirit as `DiagnosticsPoller`.
 */
interface CrashReporting {
    fun init()
}
```

- [ ] **Step 4: Write the implementation**

Create `app/src/main/java/com/mediplus/spapp/core/crash/SentryCrashReporting.kt`:

```kotlin
package com.mediplus.spapp.core.crash

import android.content.Context
import com.mediplus.spapp.BuildConfig
import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.data.local.PrefsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configures and starts the Sentry SDK.
 *
 * Auto-init is disabled in the manifest so this block owns every decision: `beforeSend` and
 * `beforeBreadcrumb` are only settable programmatically, and screenshots must be *never captured*
 * rather than discarded post-capture — a bitmap of a patient's face must not exist, not merely go
 * unsent. The cost is losing the SDK's ContentProvider-stage init, so a crash inside Hilt's own
 * startup goes unreported; that trade is deliberate.
 *
 * `installId` is written onto the global scope asynchronously because DataStore reads suspend.
 * Events raised before that completes simply carry no user.
 */
@Singleton
class SentryCrashReporting @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gate: SentryGate,
    private val prefs: PrefsDataStore,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : CrashReporting {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun init() {
        // A malformed DSN or a failed init must never take the app down with it.
        runCatching {
            SentryAndroid.init(context) { options ->
                configure(options, BuildConfig.SENTRY_ENVIRONMENT, gate)
            }
        }
        scope.launch {
            val installId = prefs.installId()
            Sentry.configureScope { it.user = User().apply { id = installId } }
        }
    }

    companion object {

        /** Visible for testing: the entire configuration surface, with no Android dependency. */
        fun configure(options: SentryAndroidOptions, environment: String, gate: SentryGate) {
            val scrubber = SentryScrubber()

            // Never captured, not merely never sent: a screenshot of the face-check screen would
            // void the TransientFrame zeroing discipline outright.
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false

            // A clicked element's text can carry a member's name.
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false

            // The free tier's value is errors, not spans. HTTP breadcrumbs survive this: the OkHttp
            // integration's event listener emits them independently of trace sampling.
            options.tracesSampleRate = 0.0

            options.isAnrEnabled = true
            options.isEnableUncaughtExceptionHandler = true
            options.isEnableAutoSessionTracking = true

            options.isSendDefaultPii = false
            options.isSendModules = false
            options.environment = environment

            options.beforeBreadcrumb = scrubber
            options.beforeSend = SentryOptions.BeforeSendCallback { event, hint ->
                if (gate.isEnabled()) scrubber.execute(event, hint) else null
            }
        }
    }
}
```

- [ ] **Step 5: Add the Hilt module**

Create `app/src/main/java/com/mediplus/spapp/core/di/CrashModule.kt`:

```kotlin
package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.crash.CrashReporting
import com.mediplus.spapp.core.crash.SentryCrashReporting
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Crash reporting is the same implementation in both build types; only the gate differs. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporting(impl: SentryCrashReporting): CrashReporting
}
```

- [ ] **Step 6: Run the test to confirm it passes**

```powershell
.\gradlew testDebugUnitTest --tests "com.mediplus.spapp.core.crash.SentryCrashReportingTest"
```

Expected: 7 tests, all PASS. If a setter name differs in 8.51.0 the compiler says so immediately — correct the name against the `SentryAndroidOptions` class rather than deleting the assertion.

- [ ] **Step 7: Wire it into the Application**

In `app/src/main/java/com/mediplus/spapp/SpApp.kt`, add the injection and the call, and extend the KDoc:

```kotlin
    @Inject
    lateinit var crashReporting: CrashReporting

    override fun onCreate() {
        super.onCreate()
        // First: a crash in either observer's bind() should still be reported.
        crashReporting.init()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
    }
```

with the import `import com.mediplus.spapp.core.crash.CrashReporting`, and add to the class KDoc:

```
 * Crash reporting starts first, so a failure in either observer's bind() is still reported.
```

The debug gate's collector also needs starting. `Startable` and its `DevSettingsSentryGate`
implementation both already exist from Task 4, so this is one line inside `SentryCrashReporting.init()`,
placed immediately after the `runCatching` block and before the `scope.launch`:

```kotlin
        (gate as? Startable)?.start()
```

In release the bound gate is `AlwaysOnSentryGate`, which is not `Startable`, so the cast yields null
and nothing happens.

- [ ] **Step 8: Run the full suite and lint**

```powershell
.\gradlew testDebugUnitTest lintDebug
```

Expected: `BUILD SUCCESSFUL`, 460+ tests, 0 failures (438 before this work, plus 13 + 14 + 4 + 7 + 1 added).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/mediplus/spapp/core/crash/ app/src/main/java/com/mediplus/spapp/core/di/CrashModule.kt app/src/main/java/com/mediplus/spapp/SpApp.kt app/src/debug/java/com/mediplus/spapp/dev/crash/DevSettingsSentryGate.kt app/src/test/java/com/mediplus/spapp/core/crash/SentryCrashReportingTest.kt
git commit -m "feat: start Sentry from code with a hardened options block

Auto-init is off so this block owns every decision: screenshots and view
hierarchy never captured, user-interaction capture off, tracing at 0.0,
ANR and crash handlers on, PII and module reporting off, environment
tagged, and the scrubber installed on both callbacks behind the gate.
installId lands on the global scope asynchronously since DataStore reads
suspend.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Verify on device and update the docs

The one thing the 2026-08-06 test could not observe: whether a real member number survives a real HTTP call. The fake stack was on, so no HTTP happened.

**Files:**
- Modify: `app/src/main/java/com/mediplus/spapp/MainActivity.kt` (temporary, reverted in step 7)
- Modify: `app/src/main/AndroidManifest.xml` (temporary, reverted in step 7)
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-08-06-sentry-hardening-design.md` (status line)

- [ ] **Step 1: Confirm detekt adds no rows**

```bash
curl -sSLO https://github.com/detekt/detekt/releases/download/v1.23.7/detekt-cli-1.23.7.zip
unzip -q detekt-cli-1.23.7.zip
./detekt-cli-1.23.7/bin/detekt-cli --config config/detekt/detekt.yml \
  --input app/src/main/java --build-upon-default-config
```

Expected: the same 14 weighted issues as the documented baseline, with **no row naming anything under `core/crash/`**. Read the rows, not the total — the tally has been misread three times before. If a `core/crash/` row appears, fix it rather than recording it.

- [ ] **Step 2: Add a temporary one-shot capture**

In `MainActivity.onCreate`, after `super.onCreate(savedInstanceState)`:

```kotlin
        // TEMPORARY delivery check — one event per launch. Removed in step 7.
        io.sentry.Sentry.captureMessage("SP App hardened delivery check")
```

In `AndroidManifest.xml`, inside `<application>` beside the DSN:

```xml
        <!-- TEMPORARY: verbose SDK logging for the delivery check. Removed in step 7. -->
        <meta-data android:name="io.sentry.debug" android:value="true" />
```

- [ ] **Step 3: Install and configure the device**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r -t app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.mediplus.spapp/.dev.ui.DevSettingsActivity
```

In Dev Settings: turn **"Send crashes to Sentry" ON**, turn the **master "Fake backend enabled" OFF** so real HTTP occurs against the back office.

- [ ] **Step 4: Run a real journey and capture the log**

```powershell
& $adb logcat -c
& $adb shell am force-stop com.mediplus.spapp
& $adb shell am start -n com.mediplus.spapp/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

Sign in, tap a card, and let the member lookup run so a `members/<number>/…` request actually happens. Then:

```powershell
& $adb logcat -d -s Sentry:* > $env:TEMP\sentry-hardened.log
```

- [ ] **Step 5: Assert on the captured envelope**

```powershell
Select-String -Path $env:TEMP\sentry-hardened.log -Pattern "Envelope sent successfully|environment|attach-screenshot|Screenshot|members/"
```

Required:
- `Envelope sent successfully.`
- `io.sentry.attach-screenshot read: false` and **no** `"Screenshot"` line anywhere
- `"environment": "development"`
- every `members/` occurrence reads `members/{id}/…` — **no digit run appears after `members/`**
- no `logcat` breadcrumb category present

If a raw member number appears, stop and fix the scrubber; do not proceed.

- [ ] **Step 6: Confirm the closed gate really is closed**

Turn the Dev Settings toggle **off**, force-stop, relaunch, and confirm the log shows the capture being dropped with no `Envelope sent successfully.` for a new event.

- [ ] **Step 7: Revert the temporary instrumentation**

Remove the `captureMessage` line and its `io.sentry.Sentry` usage from `MainActivity.kt`, and the `io.sentry.debug` meta-data from the manifest. Confirm with `git diff` that `MainActivity.kt` matches its committed state.

- [ ] **Step 8: Update the docs**

In `docs/superpowers/specs/2026-08-06-sentry-hardening-design.md`, change the status line to `**Status:** Implemented — 2026-08-06`.

In `CLAUDE.md`, under "Conventions worth following", add after the `AuthInterceptor` bullet:

```markdown
- **Crash reports are allowlisted, not filtered.** `core/crash/ScrubRules` decides what may leave the
  device: breadcrumb categories are an allowlist (an unrecognised one is dropped, so a future SDK
  version leaks nothing by default), numeric path segments are templated to `{id}` so
  `members/634743753/services` reports as `members/{id}/services`, and long digit runs are redacted
  from message text. Screenshots and view-hierarchy capture are **off** — a bitmap of the face-check
  screen would void the `TransientFrame` zeroing discipline. Sentry auto-init is disabled; every
  option lives in `SentryCrashReporting.configure`. Note Sentry's OkHttp instrumentation is woven
  into OkHttp itself, so it bypasses `NetworkModule`'s release `Level.NONE` logging policy entirely —
  which is why the scrubber, not the logging level, is what protects request URLs.
```

and to "Current state to be aware of":

```markdown
- **Crash reporting is Sentry** (free-tier SaaS, `infoeaze/android`; design:
  `docs/superpowers/specs/2026-08-06-sentry-hardening-design.md`). ANR detection and the uncaught
  handler are on; performance tracing is off (`tracesSampleRate = 0.0`) because the free tier's
  5,000 errors/month across a fleet of hundreds is ~16 per device per month, and HTTP breadcrumbs
  survive that setting anyway. **Debug builds send nothing** unless "Send crashes to Sentry" is on in
  Dev Settings, so dev churn does not spend the fleet's quota; the gate starts closed, so a debug
  crash before DataStore is read sends nothing. `sentry.properties` is git-ignored but no longer
  required to build a release, since source upload is off.
```

- [ ] **Step 9: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-08-06-sentry-hardening-design.md
git commit -m "docs: record the verified Sentry configuration

On-device with the fake stack off: envelope accepted, environment
development, no screenshot attachment, and members/{id}/ templated in
the http breadcrumb — the one thing the 2026-08-06 test could not
observe because no real HTTP occurred.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review notes

**Spec coverage.** Every numbered wizard defect maps to a task: quota bomb (already removed; Task 6 step 7 confirms `MainActivity` stays clean), screenshots (T1 manifest + T5 options), URL member numbers (T2/T3), Logcat instrumentation (T1 plugin features), environment (T1 + T5), trace sampling (T5), source context (T1), plugin catalog and manifest tidy (T1). The scrub contract's every clause has a test in T2 or T3. The gate's fail-closed default is T4. The acceptance list is T6.

**Three known API risks**, all compile- or test-visible immediately, each with a stated correction rather than a shrug. None is to be resolved by weakening an assertion.

1. `Breadcrumb.getData()` mutability — T3 step 4 gives the rebuild-the-crumb fallback.
2. `Hint.clearAttachments()` presence in 8.51.0 — T3 step 4 gives `hint.attachments.clear()`.
3. `SentryAndroidOptions()` instantiation in a plain JVM unit test (T5). It is a field holder and should construct fine, helped by `isReturnDefaultValues = true` in `testOptions`. If it does touch Android at construction, keep the assertions and add Robolectric for that one class rather than moving the test to `androidTest`, where it would stop running in CI.

**Two deviations from the spec**, both deliberate:

- Task 2 splits the spec's single `EventScrubber.kt` into `ScrubRules.kt` (pure) plus `SentryScrubber.kt` (adapter), so the rules are testable without Sentry types and each file reads in one pass.
- The spec named the URL function `templatePath`; the plan uses `templateUrl`, since it takes and returns a whole URL rather than a path.

**Test count** rises from 438 to roughly 477 (13 + 14 + 4 + 7 + 1 new).
