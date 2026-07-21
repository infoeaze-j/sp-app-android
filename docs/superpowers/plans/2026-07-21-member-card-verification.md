# Member Card Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the post-login eMRTD passport-chip scan with a member card NFC tap whose numeric card number (digits only, 7–32 chars) is verified by the back office, which returns the member's details.

**Architecture:** A new `MemberCardReader` interface reads an NDEF Text record off a tapped card and yields a validated `MemberNumber`. A new `MemberRepository` posts it to `POST /members/verify` and gets back a verdict plus member details. The eMRTD/JMRTD stack and `/documents/validate` are deleted outright, and the patient key is renamed `documentNumber` → `memberNumber` across the app, including the JSON field name and the `patients/{…}` URL segment.

**Tech Stack:** Kotlin, Hilt, Coroutines, Compose, Retrofit + kotlinx.serialization, Android NFC (`Ndef`/`NdefRecord`, reader mode), JUnit4 + MockK + coroutines-test + MockWebServer.

## Global Constraints

- **AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.3.10.** Do NOT add the `org.jetbrains.kotlin.android` plugin. Hilt ≥ 2.60. compileSdk 37.
- **Android Lint is the in-build gate** (`abortOnError = true`). Everything must pass `:app:lintDebug`.
- **Package root:** `com.mediplus.faceverify`.
- **Gradle commands assume the Android Studio JBR.** Bash: prefix each command with `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. All commands below are shown in Bash form.
- **Source sets:** production debug code → `app/src/debug/java/...`; debug-only unit tests → `app/src/testDebug/java/...` (NOT `app/src/test`, which also compiles for `testReleaseUnitTest` where debug classes are absent).
- **Baseline before starting:** `testDebugUnitTest` = **135 tests, 0 failures, 0 errors**. The suite must never be left red between tasks.
- **The member number is a patient identifier.** It must never be logged. `MemberNumber.toString()` is deliberately redacting.
- **Branch:** `feat/member-card-verification`, already created, already contains the design doc commit.

---

## File Structure

**Created — domain**
- `domain/model/MemberNumber.kt` — the validated card-number value class. Sole owner of the format rule.
- `domain/model/MemberModels.kt` — `MemberDetails`, `MemberVerification`.
- `domain/model/NfcModels.kt` — `NfcAvailability`, moved out of the deleted `DocumentModels.kt`.
- `domain/usecase/VerifyMemberUseCase.kt`

**Created — data**
- `data/remote/MemberApi.kt` — `POST members/verify` + DTOs.
- `data/repository/MemberRepository.kt` — interface + `MemberRepositoryImpl`.

**Created — core**
- `core/nfc/NfcHost.kt` — `NfcHost`, moved out of the deleted `NfcReader.kt`.
- `core/nfc/MemberCardReader.kt` — interface.
- `core/nfc/NdefMemberCardReader.kt` — real NDEF implementation.

**Created — ui**
- `ui/memberscan/MemberScanViewModel.kt`
- `ui/memberscan/MemberScanScreen.kt`

**Created — dev (debug source set)**
- `dev/nfc/FakeMemberCardReader.kt`, `dev/nfc/SwitchingMemberCardReader.kt`
- `dev/repository/FakeMemberRepository.kt`

**Created — tests**
- `test/.../domain/model/MemberNumberTest.kt`
- `test/.../domain/usecase/VerifyMemberUseCaseTest.kt`
- `test/.../data/remote/MemberApiContractTest.kt`
- `test/.../ui/memberscan/MemberScanViewModelTest.kt`
- `testDebug/.../dev/FakeMemberCardReaderTest.kt`, `SwitchingMemberCardReaderTest.kt`, `FakeMemberRepositoryTest.kt`

**Deleted**
- `core/nfc/NfcReader.kt`, `core/nfc/AccessKeyDeriver.kt`
- `ui/nfcscan/NfcScanScreen.kt`, `ui/nfcscan/NfcScanViewModel.kt`
- `data/remote/DocumentApi.kt`, `data/repository/DocumentRepository.kt`
- `domain/usecase/VerifyDocumentUseCase.kt`, `domain/model/DocumentModels.kt`
- `dev/nfc/FakeNfcReader.kt`, `dev/nfc/SwitchingNfcReader.kt`, `dev/repository/FakeDocumentRepository.kt`
- `test/.../core/nfc/AccessKeyDeriverTest.kt` (if present), `test/.../domain/usecase/VerifyDocumentUseCaseTest.kt`, `test/.../data/remote/DocumentApiContractTest.kt`, `test/.../ui/nfcscan/NfcScanViewModelTest.kt`
- `testDebug/.../dev/FakeNfcReaderTest.kt`, `SwitchingNfcReaderTest.kt`, `FakeDocumentRepositoryTest.kt`
- Dependencies `jmrtd`, `scuba-sc-android`, `bouncycastle-prov`

---

## Task 1: `MemberNumber` value class

Pure, zero dependencies, no Android types. Establishes the single format rule everything else consumes.

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/domain/model/MemberNumber.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/domain/model/MemberNumberTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MemberNumber` (`@JvmInline value class` wrapping `val value: String`), `MemberNumber.parse(raw: String?): MemberNumber?`, `MemberNumber.MIN_LENGTH = 7`, `MemberNumber.MAX_LENGTH = 32`. `toString()` returns the literal `"MemberNumber(***)"` and never the digits.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/domain/model/MemberNumberTest.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The member card number format rule lives here and nowhere else: digits only, longer than
 * 6 characters, bounded above so a garbage NDEF payload cannot become an unbounded URL segment.
 */
class MemberNumberTest {

    @Test
    fun `six digits is too short`() {
        assertNull(MemberNumber.parse("123456"))
    }

    @Test
    fun `seven digits is the shortest accepted number`() {
        assertEquals("1234567", MemberNumber.parse("1234567")?.value)
    }

    @Test
    fun `thirty-two digits is accepted`() {
        val raw = "1".repeat(32)
        assertEquals(raw, MemberNumber.parse(raw)?.value)
    }

    @Test
    fun `thirty-three digits is rejected`() {
        assertNull(MemberNumber.parse("1".repeat(33)))
    }

    @Test
    fun `leading zeros are preserved, not normalised away`() {
        assertEquals("0001234", MemberNumber.parse("0001234")?.value)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("1234567", MemberNumber.parse("  1234567 \n")?.value)
    }

    @Test
    fun `letters are rejected`() {
        assertNull(MemberNumber.parse("P1234567"))
    }

    @Test
    fun `interior spaces are rejected`() {
        assertNull(MemberNumber.parse("123 4567"))
    }

    @Test
    fun `a leading plus is rejected`() {
        assertNull(MemberNumber.parse("+1234567"))
    }

    @Test
    fun `empty and null are rejected`() {
        assertNull(MemberNumber.parse(""))
        assertNull(MemberNumber.parse("   "))
        assertNull(MemberNumber.parse(null))
    }

    @Test
    fun `toString never exposes the digits`() {
        val number = MemberNumber.parse("1234567")!!

        assertEquals("MemberNumber(***)", number.toString())
        assertEquals("MemberNumber(***)", "$number")
        assertEquals("1234567", number.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberNumberTest*'
```

Expected: FAIL — compilation error, `Unresolved reference: MemberNumber`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mediplus/faceverify/domain/model/MemberNumber.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

/**
 * A member card number: digits only, longer than 6 characters (FR-011a). Constructed only through
 * [parse], so an instance is proof the format rule already passed — callers never re-validate.
 *
 * [toString] is deliberately redacting: the number identifies a patient, so an accidental
 * interpolation into a log line or an exception message must not leak it. Use [value] to send it.
 */
@JvmInline
value class MemberNumber private constructor(val value: String) {

    override fun toString(): String = REDACTED

    companion object {
        /** "More than 6 characters" — the shortest accepted number is 7 digits. */
        const val MIN_LENGTH = 7

        /** Bounded above so a garbage NDEF payload cannot become an unbounded URL path segment. */
        const val MAX_LENGTH = 32

        private const val REDACTED = "MemberNumber(***)"
        private val PATTERN = Regex("^[0-9]{$MIN_LENGTH,$MAX_LENGTH}$")

        /** The validated number, or null when [raw] is absent or not a well-formed card number. */
        fun parse(raw: String?): MemberNumber? {
            val trimmed = raw?.trim() ?: return null
            return if (PATTERN.matches(trimmed)) MemberNumber(trimmed) else null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberNumberTest*'
```

Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/domain/model/MemberNumber.kt \
        app/src/test/java/com/mediplus/faceverify/domain/model/MemberNumberTest.kt
git commit -m "feat: add MemberNumber value class with digits-only 7-32 rule"
```

---

## Task 2: Rename the patient key `documentNumber` → `memberNumber`

Mechanical, green-to-green. Nothing else in the plan is written against two conflicting names.

**This is a contract change, not a pure refactor.** `documentNumber` is also the `@Path("documentNumber")` URL segment in `EnrollmentApi.kt` (lines 17, 18, 20, 22, 26, 28) and a `@Serializable` JSON key in `FaceVerifyRequest`. After this task the app calls `patients/{memberNumber}/services` and posts `{"memberNumber": …}` to `face/verify`. That is the intended placeholder contract; `docs/openapi.yaml` is updated in Task 4.

**Files:** 29 files under `app/src` containing `documentNumber`, plus `JourneyState.kt` and `AppRoute.kt` for the two other renames.

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `VerifiedIdentity(memberNumber: String, memberVerified: Boolean = false, faceVerified: Boolean = false, sameSubject: Boolean = false, verifiedAt: Long? = null)`; `JourneyStep.MEMBER_SCAN`; `JourneyGate.furthestReachable(sessionActive, memberVerified, consentGranted, faceVerified, currentlyVerified, lockedOut)`; `FaceRepository.verify(memberNumber: String, frame: TransientFrame)`; `EnrollmentRepository.listServices(memberNumber)` / `.enroll(memberNumber, serviceId, idempotencyKey)` / `.recheck(memberNumber, idempotencyKey)`; `Enrollment.memberNumber`.

- [ ] **Step 1: Apply the three renames**

```bash
grep -rl 'documentNumber\|documentVerified\|DOCUMENT_SCAN' app/src --include=*.kt \
  | xargs sed -i 's/documentNumber/memberNumber/g; s/documentVerified/memberVerified/g; s/DOCUMENT_SCAN/MEMBER_SCAN/g'
```

This intentionally also rewrites files that Task 10 deletes (`NfcReader.kt`, `AccessKeyDeriver.kt`, `DocumentApi.kt`, `DocumentModels.kt`, `VerifyDocumentUseCase.kt` and their tests). Renaming them costs nothing and keeps the build green until they go.

- [ ] **Step 2: Fix the doc comments the rename made wrong**

`sed` renames identifiers inside KDoc too, producing sentences that now read oddly. Fix these four by hand:

In `app/src/main/java/com/mediplus/faceverify/domain/model/JourneyState.kt`, replace the `@param` lines:

```kotlin
 * @param memberNumber ties the composite to exactly one patient (FR-011a)
 * @param memberVerified set only on server VALID + memberVerified (FR-008)
```

In `app/src/main/java/com/mediplus/faceverify/ui/navigation/AppRoute.kt`, change the enum constant:

```kotlin
    NfcScan("nfc", JourneyStep.MEMBER_SCAN),
```

(The route is renamed to `MemberScan` in Task 9, once the screen it points at exists.)

In `app/src/main/java/com/mediplus/faceverify/domain/model/JourneyState.kt`, the `JourneyStep` enum should now read:

```kotlin
enum class JourneyStep {
    NOT_SIGNED_IN,
    SIGNED_IN,
    MEMBER_SCAN,
    CONSENT,
    FACE_CHECK,
    READY_TO_ENROLL,
    ENROLLMENT,
}
```

and `furthestReachable`'s fallback comment:

```kotlin
        else -> JourneyStep.MEMBER_SCAN // verified but stale → re-verify (FR-026)
```

- [ ] **Step 3: Verify the whole suite is still green**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, still 135 tests, 0 failures. Confirm no `documentNumber` survives:

```bash
grep -rn 'documentNumber\|documentVerified\|DOCUMENT_SCAN' app/src --include=*.kt | wc -l
```

Expected: `0`.

- [ ] **Step 4: Commit**

```bash
git add -A app/src
git commit -m "refactor!: rename patient key documentNumber -> memberNumber

Also renames the JSON field and the patients/{...} URL segment, so this
changes the wire contract as well as the Kotlin identifiers."
```

---

## Task 3: New business codes and their user-facing messages

Adds `MEMBER_INVALID` and `CARD_UNREADABLE` alongside the existing document codes. The document codes are removed in Task 10, once nothing references them.

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/core/result/AppResult.kt` (the `BusinessCode` enum, lines 59–74)
- Modify: `app/src/main/java/com/mediplus/faceverify/core/result/ErrorMapper.kt` (`businessMessage`, lines 33–93)
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/faceverify/core/result/ErrorMapperTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `BusinessCode.MEMBER_INVALID`, `BusinessCode.CARD_UNREADABLE`; string resources `err_member_invalid_title`/`_body`, `err_card_unreadable_title`/`_body`, and `action_enter_manually`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/mediplus/faceverify/core/result/ErrorMapperTest.kt`, inside the existing test class:

```kotlin
    @Test
    fun `member invalid maps to its own message with a rescan action`() {
        val message = mapper.toUserMessage(AppError.Business(BusinessCode.MEMBER_INVALID, "MEMBERSHIP_EXPIRED"))

        assertEquals(R.string.err_member_invalid_title, message.titleRes)
        assertEquals(R.string.err_member_invalid_body, message.bodyRes)
        assertEquals(R.string.action_rescan, message.actionRes)
    }

    @Test
    fun `an unreadable card offers manual entry rather than a bare retry`() {
        val message = mapper.toUserMessage(AppError.Business(BusinessCode.CARD_UNREADABLE))

        assertEquals(R.string.err_card_unreadable_title, message.titleRes)
        assertEquals(R.string.err_card_unreadable_body, message.bodyRes)
        assertEquals(R.string.action_enter_manually, message.actionRes)
    }
```

If the existing class names its mapper field something other than `mapper`, match that name.

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*ErrorMapperTest*'
```

Expected: FAIL — `Unresolved reference: MEMBER_INVALID`.

- [ ] **Step 3: Write minimal implementation**

In `app/src/main/java/com/mediplus/faceverify/core/result/AppResult.kt`, add two constants to `BusinessCode` immediately after `DOCUMENT_EXPIRED`:

```kotlin
    MEMBER_INVALID,
    CARD_UNREADABLE,
```

In `app/src/main/res/values/strings.xml`, add after the `action_open_settings` line:

```xml
    <string name="action_enter_manually">Enter number</string>
```

and add a new block before the `<!-- ===== Live face check (US3) ===== -->` comment:

```xml
    <!-- ===== Member card errors ===== -->
    <string name="err_member_invalid_title">Membership not valid</string>
    <string name="err_member_invalid_body">This membership can\'t be used right now. Ask the member to contact support.</string>
    <string name="err_card_unreadable_title">Card couldn\'t be read</string>
    <string name="err_card_unreadable_body">Try tapping again, or enter the number printed on the card.</string>
```

In `app/src/main/java/com/mediplus/faceverify/core/result/ErrorMapper.kt`, add two branches to `businessMessage`, immediately after the `BusinessCode.DOCUMENT_EXPIRED` branch:

```kotlin
        BusinessCode.MEMBER_INVALID -> UiMessage(
            R.string.err_member_invalid_title,
            R.string.err_member_invalid_body,
            R.string.action_rescan,
        )
        BusinessCode.CARD_UNREADABLE -> UiMessage(
            R.string.err_card_unreadable_title,
            R.string.err_card_unreadable_body,
            R.string.action_enter_manually,
        )
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failures, 0 errors, and the total up by exactly 2 from the previous task's run. (Do not hard-code a total — count from the run.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/core/result/ \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/mediplus/faceverify/core/result/ErrorMapperTest.kt
git commit -m "feat: add MEMBER_INVALID and CARD_UNREADABLE business codes"
```

---

## Task 4: `MemberApi` + OpenAPI contract

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/data/remote/MemberApi.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/domain/model/MemberModels.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt`
- Modify: `docs/openapi.yaml`

**Interfaces:**
- Consumes: `MemberNumber` (Task 1).
- Produces: `MemberApi.verify(body: VerifyMemberRequest): Response<VerifyMemberResponse>`; `VerifyMemberRequest(memberNumber: String)`; `VerifyMemberResponse(status: String, reason: String?, memberVerified: Boolean, memberResolved: Boolean, referenceOnFile: Boolean, member: MemberDto?)`; `MemberDto(memberNumber, fullName, dateOfBirth, membershipStatus, plan)`; domain `MemberDetails`, `MemberVerification` with nested `enum class Status { VALID, INVALID }`.

- [ ] **Step 1: Write the domain models**

Create `app/src/main/java/com/mediplus/faceverify/domain/model/MemberModels.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

/**
 * The member details the back office returns for a verified card. Shown on the confirmation step so
 * the operator can check them against the person in front of them (FR-011).
 */
data class MemberDetails(
    val memberNumber: String,
    val fullName: String,
    val dateOfBirth: String,
    val membershipStatus: String,
    val plan: String?,
)

/**
 * The authoritative server verdict for a scanned member card (FR-008). Membership validity is
 * entirely server-owned — a member card carries no expiry, so there is no local pre-check.
 */
data class MemberVerification(
    val status: Status,
    val reason: String?,
    val memberVerified: Boolean,
    val memberResolved: Boolean,
    val referenceOnFile: Boolean,
    val member: MemberDetails?,
) {
    enum class Status { VALID, INVALID }
}
```

- [ ] **Step 2: Write the API interface**

Create `app/src/main/java/com/mediplus/faceverify/data/remote/MemberApi.kt`:

```kotlin
package com.mediplus.faceverify.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Member card verification (FR-007–FR-011a). The card number is read on-device from an NDEF text
 * record; this endpoint returns the authoritative verdict and resolves the member.
 *
 * Placeholder contract — reconcile with the back office once it publishes its shape.
 */
interface MemberApi {

    @POST("members/verify")
    suspend fun verify(@Body body: VerifyMemberRequest): Response<VerifyMemberResponse>
}

@Serializable
data class VerifyMemberRequest(val memberNumber: String)

@Serializable
data class VerifyMemberResponse(
    val status: String,
    val reason: String? = null,
    val memberVerified: Boolean = false,
    val memberResolved: Boolean = false,
    val referenceOnFile: Boolean = false,
    val member: MemberDto? = null,
)

@Serializable
data class MemberDto(
    val memberNumber: String,
    val fullName: String = "",
    val dateOfBirth: String = "",
    val membershipStatus: String = "",
    val plan: String? = null,
)
```

- [ ] **Step 3: Register the API with Hilt**

In `app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt`, add the import `com.mediplus.faceverify.data.remote.MemberApi` and this provider after `provideDocumentApi`:

```kotlin
    @Provides
    @Singleton
    fun provideMemberApi(retrofit: Retrofit): MemberApi = retrofit.create()
```

- [ ] **Step 4: Update the OpenAPI document**

In `docs/openapi.yaml`, replace the whole `/documents/validate:` block (lines 96–125) with:

```yaml
  /members/verify:
    post:
      tags: [Members]
      summary: Verify a scanned member card and resolve the member
      description: |
        The card number is read on-device from an NDEF text record (or entered by
        the operator when the card is unreadable); this endpoint returns the
        authoritative verdict and resolves the member by card number (FR-008, FR-011a).
      operationId: verifyMember
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/VerifyMemberRequest'
      responses:
        '200':
          description: |
            Verdict returned. `status: INVALID` (with `reason`) is a valid 200
            response — the membership is rejected, not member-verified (FR-008).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/VerifyMemberResponse'
        '404':
          description: Member not resolvable; halt (cannot key subsequent calls).
        '401':
          $ref: '#/components/responses/SessionInvalidated'
        '5XX':
          description: Transient/timeout — retry allowed without losing session or prior steps (FR-009).
```

Then, in `components.schemas`, delete `ValidateDocumentRequest`, `ValidateDocumentResponse`, and `IdentityFieldsDto`, and add:

```yaml
    VerifyMemberRequest:
      type: object
      required: [memberNumber]
      properties:
        memberNumber:
          type: string
          pattern: '^[0-9]{7,32}$'
          description: Digits only, longer than 6 characters.
    VerifyMemberResponse:
      type: object
      required: [status]
      properties:
        status:
          type: string
          enum: [VALID, INVALID]
        reason:
          type: string
          nullable: true
        memberVerified:
          type: boolean
        memberResolved:
          type: boolean
        referenceOnFile:
          type: boolean
        member:
          $ref: '#/components/schemas/Member'
    Member:
      type: object
      required: [memberNumber]
      properties:
        memberNumber: { type: string }
        fullName: { type: string }
        dateOfBirth: { type: string, format: date }
        membershipStatus: { type: string }
        plan: { type: string, nullable: true }
```

Finally, rename the `DocumentNumber` path parameter: change `parameters.DocumentNumber` to `parameters.MemberNumber` with `name: memberNumber`, update the two `$ref: '#/components/parameters/DocumentNumber'` uses, and change both `/patients/{documentNumber}/…` path keys to `/patients/{memberNumber}/…`.

- [ ] **Step 5: Verify it compiles**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/data/remote/MemberApi.kt \
        app/src/main/java/com/mediplus/faceverify/domain/model/MemberModels.kt \
        app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt \
        docs/openapi.yaml
git commit -m "feat: add members/verify API contract and domain models"
```

---

## Task 5: `MemberRepository` + contract test

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/data/repository/MemberRepository.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/data/remote/MemberApiContractTest.kt`

**Interfaces:**
- Consumes: `MemberApi`, `VerifyMemberRequest`, `VerifyMemberResponse`, `MemberDto` (Task 4); `MemberNumber` (Task 1); `MemberVerification`, `MemberDetails` (Task 4).
- Produces: `interface MemberRepository { suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> }` and `class MemberRepositoryImpl @Inject constructor(api: MemberApi, @param:IoDispatcher dispatcher: CoroutineDispatcher)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/data/remote/MemberApiContractTest.kt`:

```kotlin
package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.MemberRepositoryImpl
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * Member verification contract (FR-008): VALID, INVALID+reason, 404, 5xx, and timeout each map to
 * the correct [AppResult] through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MemberRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val memberNumber = MemberNumber.parse("1234567")!!

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val api: MemberApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = MemberRepositoryImpl(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `VALID card maps to a verified verification carrying the member details`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"VALID","memberVerified":true,"memberResolved":true,"referenceOnFile":true,""" +
                    """"member":{"memberNumber":"1234567","fullName":"Jane Doe","dateOfBirth":"1985-04-12",""" +
                    """"membershipStatus":"ACTIVE","plan":"Gold"}}""",
            ),
        )

        val result = repository.verify(memberNumber)

        val verification = (result as AppResult.Success).data
        assertEquals(MemberVerification.Status.VALID, verification.status)
        assertTrue(verification.memberVerified)
        assertEquals("Jane Doe", verification.member?.fullName)
        assertEquals("Gold", verification.member?.plan)
    }

    @Test
    fun `the request sends the card number as memberNumber`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"VALID"}"""))

        repository.verify(memberNumber)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""memberNumber":"1234567""""))
    }

    @Test
    fun `INVALID card carries the specific reason`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"INVALID","reason":"MEMBERSHIP_EXPIRED","memberVerified":false,"memberResolved":true}""",
            ),
        )

        val verification = (repository.verify(memberNumber) as AppResult.Success).data

        assertEquals(MemberVerification.Status.INVALID, verification.status)
        assertEquals("MEMBERSHIP_EXPIRED", verification.reason)
    }

    @Test
    fun `an unresolvable member maps to a patient-not-found rejection`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.verify(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `server error maps to transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.verify(memberNumber)

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `no response within the timeout maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        assertEquals(AppResult.Timeout, repository.verify(memberNumber))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberApiContractTest*'
```

Expected: FAIL — `Unresolved reference: MemberRepositoryImpl`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mediplus/faceverify/data/repository/MemberRepository.kt`:

```kotlin
package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.MemberApi
import com.mediplus.faceverify.data.remote.MemberDto
import com.mediplus.faceverify.data.remote.VerifyMemberRequest
import com.mediplus.faceverify.data.remote.VerifyMemberResponse
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Submits a scanned member card number for the authoritative verdict and member resolution
 * (FR-008, FR-011a). Transport outcomes become [AppResult]; the business interpretation
 * (verified vs. rejected) is [com.mediplus.faceverify.domain.usecase.VerifyMemberUseCase]'s job.
 */
interface MemberRepository {
    suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification>
}

class MemberRepositoryImpl @Inject constructor(
    private val api: MemberApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : MemberRepository {

    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> =
        apiCall(dispatcher, { api.verify(VerifyMemberRequest(memberNumber.value)) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.toVerification())
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.MEMBER_INVALID))
            }
        }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun VerifyMemberResponse.toVerification() = MemberVerification(
    status = if (status.equals("VALID", ignoreCase = true)) {
        MemberVerification.Status.VALID
    } else {
        MemberVerification.Status.INVALID
    },
    reason = reason,
    memberVerified = memberVerified,
    memberResolved = memberResolved,
    referenceOnFile = referenceOnFile,
    member = member?.toDomain(),
)

private fun MemberDto.toDomain() = MemberDetails(
    memberNumber = memberNumber,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    membershipStatus = membershipStatus,
    plan = plan,
)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberApiContractTest*'
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/data/repository/MemberRepository.kt \
        app/src/test/java/com/mediplus/faceverify/data/remote/MemberApiContractTest.kt
git commit -m "feat: add MemberRepository posting to members/verify"
```

---

## Task 6: `VerifyMemberUseCase`

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCase.kt`
- Test: `app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCaseTest.kt`

**Interfaces:**
- Consumes: `MemberRepository` (Task 5), `MemberNumber` (Task 1), `MemberVerification` (Task 4), `SessionManager`, `VerifiedIdentity` (renamed in Task 2).
- Produces: `class VerifyMemberUseCase @Inject constructor(memberRepository: MemberRepository, sessionManager: SessionManager)` with `suspend operator fun invoke(memberNumber: MemberNumber): AppResult<MemberVerification>`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCaseTest.kt`:

```kotlin
package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A card is member-verified ONLY on server VALID + memberVerified + resolved; every rejection
 * surfaces a specific reason (FR-008, FR-011a). Membership validity is server-owned — there is no
 * local expiry pre-check, because a member card carries no expiry date.
 */
class VerifyMemberUseCaseTest {

    private val repository = mockk<MemberRepository>()
    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var useCase: VerifyMemberUseCase
    private val memberNumber = MemberNumber.parse("1234567")!!

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        useCase = VerifyMemberUseCase(repository, sessionManager)
    }

    private fun details() = MemberDetails(
        memberNumber = "1234567",
        fullName = "Jane Doe",
        dateOfBirth = "1985-04-12",
        membershipStatus = "ACTIVE",
        plan = "Gold",
    )

    private fun verification(
        status: MemberVerification.Status = MemberVerification.Status.VALID,
        verified: Boolean = true,
        resolved: Boolean = true,
        reason: String? = null,
        member: MemberDetails? = details(),
    ) = MemberVerification(status, reason, verified, resolved, referenceOnFile = true, member = member)

    @Test
    fun `a valid card marks the composite member-verified`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification())

        val result = useCase(memberNumber)

        assertTrue(result is AppResult.Success)
        val identity = sessionManager.verifiedIdentity.value
        assertEquals("1234567", identity?.memberNumber)
        assertTrue(identity?.memberVerified == true)
        assertFalse(identity?.faceVerified == true)
    }

    @Test
    fun `an unresolved member is rejected`() = runTest {
        coEvery { repository.verify(any()) } returns
            AppResult.Success(verification(resolved = false, member = null))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `a resolved member with no details is still rejected`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification(member = null))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `server INVALID surfaces a member-invalid rejection carrying the reason`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(
            verification(
                status = MemberVerification.Status.INVALID,
                verified = false,
                reason = "MEMBERSHIP_EXPIRED",
            ),
        )

        val error = (useCase(memberNumber) as AppResult.BusinessRejection).error

        assertEquals(BusinessCode.MEMBER_INVALID, error.code)
        assertEquals("MEMBERSHIP_EXPIRED", error.serverReason)
    }

    @Test
    fun `VALID but not memberVerified is still a rejection`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification(verified = false))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.MEMBER_INVALID,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `transient failure is propagated`() = runTest {
        coEvery { repository.verify(any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

        assertTrue(useCase(memberNumber) is AppResult.TransientFailure)
    }

    @Test
    fun `a fresh scan resets the composite for the new member`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification())
        useCase(memberNumber)
        sessionManager.updateVerifiedIdentity { it?.copy(faceVerified = true, sameSubject = true) }

        useCase(MemberNumber.parse("7654321")!!)

        val identity = sessionManager.verifiedIdentity.value
        assertEquals("7654321", identity?.memberNumber)
        assertFalse(identity?.faceVerified == true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*VerifyMemberUseCaseTest*'
```

Expected: FAIL — `Unresolved reference: VerifyMemberUseCase`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCase.kt`:

```kotlin
package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import com.mediplus.faceverify.domain.model.VerifiedIdentity
import javax.inject.Inject

/**
 * Turns a scanned card number into a verified-or-rejected outcome (FR-007, FR-008). A card is
 * member-verified ONLY when the back office returns VALID + memberVerified for a resolved member.
 * Any rejection surfaces a specific reason.
 *
 * Unlike the document flow this replaces, there is no local pre-check: a member card carries no
 * expiry date, so membership validity is entirely the back office's to decide.
 */
class VerifyMemberUseCase @Inject constructor(
    private val memberRepository: MemberRepository,
    private val sessionManager: SessionManager,
) {
    suspend operator fun invoke(memberNumber: MemberNumber): AppResult<MemberVerification> =
        when (val result = memberRepository.verify(memberNumber)) {
            is AppResult.Success -> interpret(memberNumber, result.data)
            else -> result
        }

    private fun interpret(
        memberNumber: MemberNumber,
        verification: MemberVerification,
    ): AppResult<MemberVerification> {
        // Without resolved details there is nothing to key /face/verify or /patients/... on.
        if (!verification.memberResolved || verification.member == null) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
        }
        val verified = verification.status == MemberVerification.Status.VALID && verification.memberVerified
        if (!verified) {
            return AppResult.BusinessRejection(
                AppError.Business(BusinessCode.MEMBER_INVALID, serverReason = verification.reason),
            )
        }
        // A fresh scan resets the composite for this member; face verification comes next (FR-032).
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity(memberNumber = memberNumber.value, memberVerified = true)
        }
        return AppResult.Success(verification)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*VerifyMemberUseCaseTest*'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCase.kt \
        app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyMemberUseCaseTest.kt
git commit -m "feat: add VerifyMemberUseCase interpreting the card verdict"
```

---

## Task 7: `MemberCardReader` + NDEF implementation

`NfcHost` currently lives in `NfcReader.kt`, which Task 10 deletes. Move it to its own file **now** (and delete the declaration from `NfcReader.kt` in the same step) so the two readers can coexist until the eMRTD path goes.

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcHost.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/nfc/MemberCardReader.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/core/nfc/NdefMemberCardReader.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/domain/model/NfcModels.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcReader.kt` (remove the `NfcHost` declaration, lines 40–45)
- Modify: `app/src/main/java/com/mediplus/faceverify/domain/model/DocumentModels.kt` (remove `NfcAvailability`, lines 5–15)

**Interfaces:**
- Consumes: `MemberNumber` (Task 1), `BusinessCode.CARD_UNREADABLE` (Task 3).
- Produces: `@JvmInline value class NfcHost(val activity: Activity)`; `interface MemberCardReader { suspend fun isAvailable(): NfcAvailability; suspend fun awaitAndRead(host: NfcHost, onCardPresented: () -> Unit = {}): AppResult<MemberNumber> }`; `@Singleton class NdefMemberCardReader @Inject constructor(@param:ApplicationContext context: Context, @param:IoDispatcher dispatcher: CoroutineDispatcher)`.

`NdefMemberCardReader` reads Android framework types and is **not** JVM-unit-testable — the same position `JmrtdNfcReader` holds today. It is device-gated. Coverage for the reader contract comes from `FakeMemberCardReaderTest` in Task 8.

- [ ] **Step 1: Move `NfcAvailability` to its own file**

Create `app/src/main/java/com/mediplus/faceverify/domain/model/NfcModels.kt`:

```kotlin
package com.mediplus.faceverify.domain.model

/** Whether the device can read an NFC card right now (FR-010). */
enum class NfcAvailability {
    /** NFC hardware present and enabled. */
    AVAILABLE,

    /** NFC hardware present but turned off in settings. */
    DISABLED,

    /** No NFC hardware on this device. */
    UNAVAILABLE,
}
```

Then delete the `NfcAvailability` enum (lines 5–15) from `app/src/main/java/com/mediplus/faceverify/domain/model/DocumentModels.kt`. Both files are in the same package, so no import changes are needed anywhere.

- [ ] **Step 2: Move `NfcHost` to its own file**

Create `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcHost.kt`:

```kotlin
package com.mediplus.faceverify.core.nfc

import android.app.Activity

/**
 * The UI host a reader needs to listen for a tap. Wrapping the [Activity] keeps `android.nfc`
 * types out of the ViewModel and lets alternative readers (e.g. the debug fake) ignore it.
 */
@JvmInline
value class NfcHost(val activity: Activity)
```

Then delete lines 40–45 of `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcReader.kt` — the KDoc block and the `@JvmInline value class NfcHost(val activity: Activity)` declaration. Leave the rest of that file alone; it is deleted in Task 10.

- [ ] **Step 3: Write the reader interface**

Create `app/src/main/java/com/mediplus/faceverify/core/nfc/MemberCardReader.kt`:

```kotlin
package com.mediplus.faceverify.core.nfc

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability

/**
 * Reads a member card number from a tapped NFC card (FR-007, FR-010). The reader owns NFC
 * reader-mode setup and teardown, so no `android.nfc` type ever reaches the ViewModel.
 */
interface MemberCardReader {
    suspend fun isAvailable(): NfcAvailability

    /**
     * Suspends until a card is presented to [host], then reads its number.
     * [onCardPresented] fires once the card is in range, before the read, so the UI can
     * distinguish "waiting for a tap" from "reading". Cancelling the caller stops listening.
     *
     * A card that carries no readable number is a
     * [com.mediplus.faceverify.core.result.BusinessCode.CARD_UNREADABLE] rejection, not a transient
     * failure — retrying the tap will not help, so the UI routes to manual entry instead.
     */
    suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit = {},
    ): AppResult<MemberNumber>
}
```

- [ ] **Step 4: Write the NDEF implementation**

Create `app/src/main/java/com/mediplus/faceverify/core/nfc/NdefMemberCardReader.kt`:

```kotlin
package com.mediplus.faceverify.core.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads the member card number from the first well-known NDEF Text record on a tapped card.
 * NDEF is unauthenticated, so unlike the eMRTD reader this replaces there is no access key to
 * derive and no secure-messaging handshake — the tag is simply read.
 */
@Singleton
class NdefMemberCardReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.UNAVAILABLE
        return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
    }

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> {
        val adapter = NfcAdapter.getDefaultAdapter(host.activity)
            ?: return AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        return try {
            val tag = awaitTag(adapter, host.activity)
            onCardPresented()
            read(tag)
        } finally {
            // Reader mode must stay on for the whole read; only tear it down once we're done.
            runCatching { adapter.disableReaderMode(host.activity) }
        }
    }

    /** Enables NFC reader mode and suspends until a card is presented (or the caller is cancelled). */
    private suspend fun awaitTag(adapter: NfcAdapter, activity: Activity): Tag =
        suspendCancellableCoroutine { continuation ->
            // All four tag technologies: member card stock varies, and unlike the eMRTD reader we
            // do want the platform's NDEF check, so FLAG_READER_SKIP_NDEF_CHECK is deliberately absent.
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            continuation.invokeOnCancellation {
                runCatching { adapter.disableReaderMode(activity) }
            }
            adapter.enableReaderMode(
                activity,
                { tag -> if (continuation.isActive) continuation.resume(tag) },
                flags,
                null,
            )
        }

    private suspend fun read(tag: Tag): AppResult<MemberNumber> = withContext(dispatcher) {
        val ndef = Ndef.get(tag) ?: return@withContext unreadable()
        try {
            ndef.connect()
            val message = ndef.ndefMessage ?: return@withContext unreadable()
            val number = message.records
                .firstOrNull { it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT) }
                ?.let(::decodeTextRecord)
                ?.let(MemberNumber::parse)
                ?: return@withContext unreadable()
            AppResult.Success(number)
        } catch (e: Exception) {
            // A card moved away mid-read or a comms drop is retriable; nothing sensitive is logged.
            AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN, e))
        } finally {
            runCatching { ndef.close() }
        }
    }

    /**
     * NDEF Text record payload: byte 0 is a status byte whose low 6 bits hold the IANA language-code
     * length and whose high bit selects UTF-16 over UTF-8; the text follows the language code.
     */
    private fun decodeTextRecord(record: NdefRecord): String? {
        val payload = record.payload
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val languageLength = status and TEXT_LANGUAGE_LENGTH_MASK
        val charset = if (status and TEXT_ENCODING_UTF16_FLAG != 0) Charsets.UTF_16 else Charsets.UTF_8
        val offset = 1 + languageLength
        if (offset >= payload.size) return null
        return String(payload, offset, payload.size - offset, charset)
    }

    /** No NDEF message, no text record, or a payload that is not a well-formed card number. */
    private fun unreadable(): AppResult<MemberNumber> =
        AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))

    private companion object {
        const val TEXT_LANGUAGE_LENGTH_MASK = 0x3F
        const val TEXT_ENCODING_UTF16_FLAG = 0x80
    }
}
```

- [ ] **Step 5: Verify it compiles and the suite is still green**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failures, 0 errors. This task adds no tests, so the total is unchanged from Task 6.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/core/nfc/ \
        app/src/main/java/com/mediplus/faceverify/domain/model/
git commit -m "feat: add MemberCardReader with an NDEF text-record implementation"
```

---

## Task 8: Dev fakes for the card reader and the member repository

**Files:**
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/nfc/FakeMemberCardReader.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/nfc/SwitchingMemberCardReader.kt`
- Create: `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeMemberRepository.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/DevSettingsStore.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/core/di/NfcModule.kt`, `app/src/release/java/com/mediplus/faceverify/core/di/NfcModule.kt`, `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`
- Modify: `app/src/debug/java/com/mediplus/faceverify/dev/ui/DevSettingsScreen.kt`, `DevSettingsViewModel.kt`
- Modify: `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`, `DevSettingsMappingTest.kt`
- Create: `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeMemberCardReaderTest.kt`, `SwitchingMemberCardReaderTest.kt`, `FakeMemberRepositoryTest.kt`

**This task is strictly additive.** The new `CardScenario`/`MemberScenario` enums and the new `card`/`member` settings fields are added *alongside* the existing `NfcScenario`/`DocumentScenario` and `nfc`/`document`, and the new DI bindings are added alongside the old ones. Nothing is renamed or removed here — `FakeNfcReader` still reads `settings.nfc`, and renaming that field out from under it would leave the debug source set uncompilable in the middle of the task. Task 11 removes the old set in one pass, once nothing references it.

The eventual removal is safe at runtime: `DevSettings.kt:35` parses persisted names with `runCatching { enumValueOf<E>(name) }.getOrNull() ?: default`, so an orphaned `dev_scenario_nfc` value silently falls back to the default rather than throwing. No migration needed.

**Interfaces:**
- Consumes: `MemberCardReader`, `NdefMemberCardReader` (Task 7); `MemberRepository`, `MemberRepositoryImpl` (Task 5); `MemberNumber` (Task 1).
- Produces: `enum class CardScenario { SUCCESS, UNREADABLE, TIMEOUT, NFC_DISABLED, NO_NFC_HARDWARE }`; `enum class MemberScenario { SUCCESS, INVALID, PATIENT_NOT_FOUND, SERVER_ERROR }`; `DevSettings.card: CardScenario`, `DevSettings.member: MemberScenario`; `DevSettingsStore.setCard(...)`, `.setMember(...)`; `FakeData.memberNumber: MemberNumber`, `FakeData.memberDetails: MemberDetails`, `FakeData.verificationValid`, `FakeData.verificationInvalid`; `FakeMemberCardReader`, `SwitchingMemberCardReader`, `FakeMemberRepository`, `SwitchingMemberRepository`.

- [ ] **Step 1: Add the new scenario enums and settings fields**

Append to `app/src/debug/java/com/mediplus/faceverify/dev/DevScenarios.kt` (leave `NfcScenario` and `DocumentScenario` in place):

```kotlin
enum class MemberScenario { SUCCESS, INVALID, PATIENT_NOT_FOUND, SERVER_ERROR }

/**
 * The emulated member card tap. Unlike the other scenarios this one fakes *device hardware*, not a
 * back-office response, so it also covers the two no-hardware states the scan screen can show.
 */
enum class CardScenario { SUCCESS, UNREADABLE, TIMEOUT, NFC_DISABLED, NO_NFC_HARDWARE }
```

In `app/src/debug/java/com/mediplus/faceverify/dev/DevSettings.kt`, add two fields to `DevSettings` (after `document`):

```kotlin
    val card: CardScenario = CardScenario.SUCCESS,
    val member: MemberScenario = MemberScenario.SUCCESS,
```

two keys to `DevPrefKeys`:

```kotlin
    val CARD = stringPreferencesKey("dev_scenario_card")
    val MEMBER = stringPreferencesKey("dev_scenario_member")
```

and two lines to `toDevSettings()`:

```kotlin
        card = this[DevPrefKeys.CARD].toEnumOr(defaults.card),
        member = this[DevPrefKeys.MEMBER].toEnumOr(defaults.member),
```

Add the matching setters to the `DevSettingsStore` interface and to `DataStoreDevSettingsStore`:

```kotlin
    suspend fun setCard(scenario: CardScenario)
    suspend fun setMember(scenario: MemberScenario)
```

```kotlin
    override suspend fun setCard(scenario: CardScenario) =
        edit { it[DevPrefKeys.CARD] = scenario.name }

    override suspend fun setMember(scenario: MemberScenario) =
        edit { it[DevPrefKeys.MEMBER] = scenario.name }
```

and to `app/src/testDebug/java/com/mediplus/faceverify/dev/TestDevSettingsStore.kt`:

```kotlin
    override suspend fun setCard(scenario: CardScenario) { state.value = state.value.copy(card = scenario) }
    override suspend fun setMember(scenario: MemberScenario) { state.value = state.value.copy(member = scenario) }
```

Finally add "Card" and "Member" pickers to `DevSettingsScreen.kt` / `DevSettingsViewModel.kt`, following the shape of the existing "NFC" and "Document" pickers. Leave those two in place for now; Task 11 removes them.

- [ ] **Step 2: Add the canned member data**

In `app/src/debug/java/com/mediplus/faceverify/dev/FakeData.kt`, **add** the following (leave `readDocument`, `validationValid`, and `validationInvalid` alone — `FakeNfcReader` and `FakeDocumentRepository` still use them until Task 11):

```kotlin
    /** The card number the emulated tap returns. */
    val memberNumber: MemberNumber = MemberNumber.parse("1234567")!!

    val memberDetails: MemberDetails = MemberDetails(
        memberNumber = "1234567",
        fullName = "Jane Doe",
        dateOfBirth = "1985-04-12",
        membershipStatus = "ACTIVE",
        plan = "Gold",
    )

    val verificationValid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.VALID,
        reason = null,
        memberVerified = true,
        memberResolved = true,
        referenceOnFile = true,
        member = memberDetails,
    )

    val verificationInvalid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.INVALID,
        reason = "MEMBERSHIP_EXPIRED",
        memberVerified = false,
        memberResolved = true,
        referenceOnFile = true,
        member = memberDetails,
    )
```

with imports `com.mediplus.faceverify.domain.model.MemberDetails`, `MemberNumber`, `MemberVerification`.

- [ ] **Step 3: Write the failing fake-reader test**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeMemberCardReaderTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import android.app.Activity
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.dev.nfc.FakeMemberCardReader
import com.mediplus.faceverify.domain.model.NfcAvailability
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMemberCardReaderTest {

    private val host = NfcHost(mockk<Activity>(relaxed = true))

    private fun reader(scenario: CardScenario, latencyMillis: Long = 0L) =
        FakeMemberCardReader(TestDevSettingsStore(DevSettings(card = scenario, latencyMillis = latencyMillis)))

    @Test
    fun `success emulates a tap and returns the canned card number`() = runTest {
        val result = reader(CardScenario.SUCCESS).awaitAndRead(host)

        assertEquals(FakeData.memberNumber, (result as AppResult.Success).data)
    }

    @Test
    fun `the card is reported as presented before the read completes`() = runTest {
        var presented = false

        reader(CardScenario.SUCCESS).awaitAndRead(host) { presented = true }

        assertTrue(presented)
    }

    @Test
    fun `the simulated tap waits for the configured latency`() = runTest {
        val start = currentTime

        reader(CardScenario.SUCCESS, latencyMillis = 250L).awaitAndRead(host)

        // Two waits: one for the tap, one for the read.
        assertEquals(500L, currentTime - start)
    }

    @Test
    fun `an unreadable card is a business rejection routing to manual entry`() = runTest {
        val result = reader(CardScenario.UNREADABLE).awaitAndRead(host)

        assertEquals(
            BusinessCode.CARD_UNREADABLE,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `the timeout scenario yields an uncertain outcome`() = runTest {
        assertEquals(AppResult.Timeout, reader(CardScenario.TIMEOUT).awaitAndRead(host))
    }

    @Test
    fun `availability reflects the emulated hardware state`() = runTest {
        assertEquals(NfcAvailability.AVAILABLE, reader(CardScenario.SUCCESS).isAvailable())
        assertEquals(NfcAvailability.AVAILABLE, reader(CardScenario.UNREADABLE).isAvailable())
        assertEquals(NfcAvailability.DISABLED, reader(CardScenario.NFC_DISABLED).isAvailable())
        assertEquals(NfcAvailability.UNAVAILABLE, reader(CardScenario.NO_NFC_HARDWARE).isAvailable())
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*FakeMemberCardReaderTest*'
```

Expected: FAIL — `Unresolved reference: FakeMemberCardReader`.

- [ ] **Step 5: Write the fakes**

Create `app/src/debug/java/com/mediplus/faceverify/dev/nfc/FakeMemberCardReader.kt`:

```kotlin
package com.mediplus.faceverify.dev.nfc

import com.mediplus.faceverify.core.nfc.MemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.dev.CardScenario
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Emulated member card tap: lets the whole scan step run on an emulator or an NFC-less device.
 * The tap is simulated — after the dev latency the card is "presented", after another it is
 * "read" — so the screen still moves through ReadyToScan → Reading → Verifying exactly as on device.
 */
class FakeMemberCardReader @Inject constructor(
    private val store: DevSettingsStore,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability = when (store.current().card) {
        CardScenario.NFC_DISABLED -> NfcAvailability.DISABLED
        CardScenario.NO_NFC_HARDWARE -> NfcAvailability.UNAVAILABLE
        else -> NfcAvailability.AVAILABLE
    }

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> {
        val settings = store.current()
        delay(settings.latencyMillis) // waiting for the operator to present the card
        onCardPresented()
        delay(settings.latencyMillis) // reading the tag

        return when (settings.card) {
            CardScenario.SUCCESS -> AppResult.Success(FakeData.memberNumber)
            CardScenario.TIMEOUT -> AppResult.Timeout
            CardScenario.UNREADABLE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))
            // Reached only if the screen starts a scan despite unavailable hardware.
            CardScenario.NFC_DISABLED,
            CardScenario.NO_NFC_HARDWARE,
            -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        }
    }
}
```

Create `app/src/debug/java/com/mediplus/faceverify/dev/nfc/SwitchingMemberCardReader.kt`:

```kotlin
package com.mediplus.faceverify.dev.nfc

import com.mediplus.faceverify.core.nfc.MemberCardReader
import com.mediplus.faceverify.core.nfc.NdefMemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability
import javax.inject.Inject

/** Debug-only router: emulate the card tap when the master toggle is on, else use real NFC. */
class SwitchingMemberCardReader @Inject constructor(
    private val real: NdefMemberCardReader,
    private val fake: FakeMemberCardReader,
    private val store: DevSettingsStore,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability = pick().isAvailable()

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> = pick().awaitAndRead(host, onCardPresented)

    private suspend fun pick(): MemberCardReader = if (store.current().fakeEnabled) fake else real
}
```

Create `app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeMemberRepository.kt`:

```kotlin
package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.dev.MemberScenario
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake member verification: returns the persisted [MemberScenario]. */
class FakeMemberRepository @Inject constructor(
    private val store: DevSettingsStore,
) : MemberRepository {

    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.member) {
            MemberScenario.SUCCESS -> AppResult.Success(FakeData.verificationValid)
            MemberScenario.INVALID -> AppResult.Success(FakeData.verificationInvalid)
            MemberScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            MemberScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }
}
```

- [ ] **Step 6: Add the switching repository and the new DI bindings**

In `app/src/debug/java/com/mediplus/faceverify/dev/repository/SwitchingRepositories.kt`, **add** (leave `SwitchingDocumentRepository` in place until Task 11):

```kotlin
class SwitchingMemberRepository @Inject constructor(
    private val real: MemberRepositoryImpl,
    private val fake: FakeMemberRepository,
    private val store: DevSettingsStore,
) : MemberRepository {
    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> =
        pick().verify(memberNumber)

    private suspend fun pick(): MemberRepository = if (store.current().fakeEnabled) fake else real
}
```

adding the imports `MemberRepository`, `MemberRepositoryImpl`, `MemberVerification`, `MemberNumber`.

In `app/src/debug/java/com/mediplus/faceverify/core/di/RepositoryModule.kt`, **add** a binding alongside the document one:

```kotlin
    @Binds
    @Singleton
    abstract fun bindMemberRepository(impl: SwitchingMemberRepository): MemberRepository
```

The `release` `RepositoryModule` gets the matching addition binding `MemberRepositoryImpl` to `MemberRepository`.

In `app/src/debug/java/com/mediplus/faceverify/core/di/NfcModule.kt`, **add** alongside `bindNfcReader`:

```kotlin
    @Binds
    @Singleton
    abstract fun bindMemberCardReader(impl: SwitchingMemberCardReader): MemberCardReader
```

In `app/src/release/java/com/mediplus/faceverify/core/di/NfcModule.kt`, add:

```kotlin
    @Binds
    @Singleton
    abstract fun bindMemberCardReader(impl: NdefMemberCardReader): MemberCardReader
```

Both modules keep their existing `NfcReader` binding — `NfcScanViewModel` still injects it until Task 11.

- [ ] **Step 7: Write the two remaining fake tests**

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/FakeMemberRepositoryTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.dev.repository.FakeMemberRepository
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeMemberRepositoryTest {

    private fun repository(scenario: MemberScenario) =
        FakeMemberRepository(TestDevSettingsStore(DevSettings(member = scenario, latencyMillis = 0L)))

    @Test
    fun `success returns the canned valid verification`() = runTest {
        val result = repository(MemberScenario.SUCCESS).verify(FakeData.memberNumber)

        assertEquals(FakeData.verificationValid, (result as AppResult.Success).data)
    }

    @Test
    fun `invalid is a successful call carrying an INVALID verdict`() = runTest {
        val verification = (repository(MemberScenario.INVALID).verify(FakeData.memberNumber) as AppResult.Success).data

        assertEquals(MemberVerification.Status.INVALID, verification.status)
        assertEquals("MEMBERSHIP_EXPIRED", verification.reason)
    }

    @Test
    fun `an unresolvable member is a business rejection`() = runTest {
        val result = repository(MemberScenario.PATIENT_NOT_FOUND).verify(FakeData.memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `a server error is transient`() = runTest {
        val result = repository(MemberScenario.SERVER_ERROR).verify(FakeData.memberNumber)

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }
}
```

Create `app/src/testDebug/java/com/mediplus/faceverify/dev/SwitchingMemberCardReaderTest.kt`:

```kotlin
package com.mediplus.faceverify.dev

import android.app.Activity
import com.mediplus.faceverify.core.nfc.NdefMemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.nfc.FakeMemberCardReader
import com.mediplus.faceverify.dev.nfc.SwitchingMemberCardReader
import com.mediplus.faceverify.domain.model.MemberNumber
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingMemberCardReaderTest {

    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val real = mockk<NdefMemberCardReader>(relaxed = true)

    private fun reader(fakeEnabled: Boolean): Pair<SwitchingMemberCardReader, TestDevSettingsStore> {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = fakeEnabled, latencyMillis = 0L))
        return SwitchingMemberCardReader(real, FakeMemberCardReader(store), store) to store
    }

    @Test
    fun `the fake is used when the master toggle is on`() = runTest {
        val (switching, _) = reader(fakeEnabled = true)

        val result = switching.awaitAndRead(host)

        assertEquals(FakeData.memberNumber, (result as AppResult.Success).data)
        coVerify(exactly = 0) { real.awaitAndRead(any(), any()) }
    }

    @Test
    fun `the real reader is used when the master toggle is off`() = runTest {
        val (switching, _) = reader(fakeEnabled = false)
        val realNumber = MemberNumber.parse("7654321")!!
        coEvery { real.awaitAndRead(any(), any()) } returns AppResult.Success(realNumber)

        val result = switching.awaitAndRead(host)

        assertEquals(realNumber, (result as AppResult.Success).data)
    }

    @Test
    fun `availability follows the same routing`() = runTest {
        val (switching, _) = reader(fakeEnabled = true)

        switching.isAvailable()

        coVerify(exactly = 0) { real.isAvailable() }
    }
}
```

- [ ] **Step 8: Run the whole suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failures. Because this task is additive, the existing document/NFC fake tests still compile and still pass untouched.

- [ ] **Step 9: Commit**

```bash
git add app/src/debug app/src/release app/src/testDebug
git commit -m "feat(dev): fake the member card tap and members/verify"
```

---

## Task 9: The member scan screen

Note the flow inversion relative to the document screen it replaces. The eMRTD chip carried the identity, so the order was read → confirm → server. A member card carries only a number, so the details come from the server: **read → verify → confirm → advance**. `onConfirm` therefore does not call the back office; it only advances.

**Files:**
- Create: `app/src/main/java/com/mediplus/faceverify/ui/memberscan/MemberScanViewModel.kt`
- Create: `app/src/main/java/com/mediplus/faceverify/ui/memberscan/MemberScanScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mediplus/faceverify/ui/memberscan/MemberScanViewModelTest.kt`

**Interfaces:**
- Consumes: `MemberCardReader`, `NfcHost` (Task 7); `VerifyMemberUseCase` (Task 6); `MemberNumber` (Task 1); `MemberDetails` (Task 4); `ErrorMapper`, `UiMessage`.
- Produces: `MemberScanPhase` (sealed interface with `CheckingAvailability`, `Unavailable(availability)`, `ReadyToScan`, `Reading`, `ManualEntry`, `Verifying`, `Confirm(member)`, `Failed(message, retryable)`, `Verified`); `MemberScanUiState(phase)`; `MemberScanViewModel` with `checkAvailability()`, `startScan(host)`, `stopScan()`, `showManualEntry()`, `submitManualNumber(raw)`, `onConfirm()`, `retry()`; `@Composable MemberScanRoute(onVerified: () -> Unit, modifier: Modifier, viewModel: MemberScanViewModel)`.

The design lists eight phases; `Unavailable` is the ninth and is required — `CardScenario` covers `NFC_DISABLED` and `NO_NFC_HARDWARE`, and the screen must explain both. Because manual entry exists, `Unavailable` is no longer a dead end: it offers the keypad.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mediplus/faceverify/ui/memberscan/MemberScanViewModelTest.kt`:

```kotlin
package com.mediplus.faceverify.ui.memberscan

import android.app.Activity
import com.mediplus.faceverify.core.nfc.MemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.DefaultErrorMapper
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.usecase.VerifyMemberUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemberScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MemberCardReader>()
    private val verifyMember = mockk<VerifyMemberUseCase>()
    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val number = MemberNumber.parse("1234567")!!

    private val details = MemberDetails("1234567", "Jane Doe", "1985-04-12", "ACTIVE", "Gold")
    private val verification = MemberVerification(
        MemberVerification.Status.VALID, null, memberVerified = true,
        memberResolved = true, referenceOnFile = true, member = details,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MemberScanViewModel(reader, verifyMember, DefaultErrorMapper())

    @Test
    fun `an available reader lands on ReadyToScan`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(MemberScanPhase.ReadyToScan, vm.uiState.value.phase)
    }

    @Test
    fun `disabled NFC surfaces the unavailable phase`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.DISABLED

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            MemberScanPhase.Unavailable(NfcAvailability.DISABLED),
            vm.uiState.value.phase,
        )
    }

    @Test
    fun `a successful tap verifies and lands on Confirm with the server's details`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        assertEquals(MemberScanPhase.Confirm(details), vm.uiState.value.phase)
    }

    @Test
    fun `confirming advances to Verified`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()
        vm.onConfirm()

        assertEquals(MemberScanPhase.Verified, vm.uiState.value.phase)
    }

    @Test
    fun `an unreadable card fails and manual entry is reachable from there`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.phase is MemberScanPhase.Failed)

        vm.showManualEntry()

        assertEquals(MemberScanPhase.ManualEntry, vm.uiState.value.phase)
    }

    @Test
    fun `a manually entered number is verified like a tapped one`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.showManualEntry()
        vm.submitManualNumber("1234567")
        advanceUntilIdle()

        assertEquals(MemberScanPhase.Confirm(details), vm.uiState.value.phase)
    }

    @Test
    fun `a malformed manual number never reaches the back office`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE

        val vm = viewModel()
        advanceUntilIdle()
        vm.showManualEntry()
        vm.submitManualNumber("12345")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.phase is MemberScanPhase.Failed)
        coVerify(exactly = 0) { verifyMember(any()) }
    }

    @Test
    fun `a rejected membership surfaces a non-retryable failure`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.MEMBER_INVALID, "MEMBERSHIP_EXPIRED"))

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        val phase = vm.uiState.value.phase as MemberScanPhase.Failed
        assertEquals(false, phase.retryable)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberScanViewModelTest*'
```

Expected: FAIL — `Unresolved reference: MemberScanViewModel`.

- [ ] **Step 3: Write the ViewModel**

Create `app/src/main/java/com/mediplus/faceverify/ui/memberscan/MemberScanViewModel.kt`:

```kotlin
package com.mediplus.faceverify.ui.memberscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.nfc.MemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.usecase.VerifyMemberUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every state the member card step can be in (Principle III). */
sealed interface MemberScanPhase {
    data object CheckingAvailability : MemberScanPhase
    data class Unavailable(val availability: NfcAvailability) : MemberScanPhase
    data object ReadyToScan : MemberScanPhase
    data object Reading : MemberScanPhase
    data object ManualEntry : MemberScanPhase
    data object Verifying : MemberScanPhase
    data class Confirm(val member: MemberDetails) : MemberScanPhase
    data class Failed(val message: UiMessage, val retryable: Boolean) : MemberScanPhase
    data object Verified : MemberScanPhase
}

data class MemberScanUiState(val phase: MemberScanPhase = MemberScanPhase.CheckingAvailability)

/**
 * Drives the member card step (FR-007–FR-011a). The card carries only a number, so the details
 * shown for confirmation come from the back office: read → verify → confirm → advance.
 */
@HiltViewModel
class MemberScanViewModel @Inject constructor(
    private val cardReader: MemberCardReader,
    private val verifyMember: VerifyMemberUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemberScanUiState())
    val uiState: StateFlow<MemberScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        checkAvailability()
    }

    /** Re-evaluate NFC hardware state (also used to recover from the disabled/unavailable state). */
    fun checkAvailability() {
        viewModelScope.launch {
            _uiState.value = when (val availability = cardReader.isAvailable()) {
                NfcAvailability.AVAILABLE -> MemberScanUiState(MemberScanPhase.ReadyToScan)
                else -> MemberScanUiState(MemberScanPhase.Unavailable(availability))
            }
        }
    }

    /**
     * Start listening for a card tap on [host]. Idempotent: a scan already in flight is left alone.
     * The job outlives recomposition; [stopScan] ends it.
     */
    fun startScan(host: NfcHost) {
        if (scanJob?.isActive == true) return
        if (_uiState.value.phase != MemberScanPhase.ReadyToScan) return

        scanJob = viewModelScope.launch {
            val result = cardReader.awaitAndRead(host) {
                _uiState.value = MemberScanUiState(MemberScanPhase.Reading)
            }
            when (result) {
                is AppResult.Success -> verify(result.data)
                else -> _uiState.value =
                    MemberScanUiState(MemberScanPhase.Failed(map(result), retryable = true))
            }
        }
    }

    /** Stop listening (screen left the composition); the reader tears down its NFC reader mode. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /** The card is damaged or this device has no NFC — let the operator type the number instead. */
    fun showManualEntry() {
        stopScan()
        _uiState.value = MemberScanUiState(MemberScanPhase.ManualEntry)
    }

    /** Verify an operator-entered number. Malformed input never reaches the back office. */
    fun submitManualNumber(raw: String) {
        val number = MemberNumber.parse(raw)
        if (number == null) {
            _uiState.value = MemberScanUiState(
                MemberScanPhase.Failed(
                    errorMapper.toUserMessage(AppError.Business(BusinessCode.CARD_UNREADABLE)),
                    retryable = true,
                ),
            )
            return
        }
        viewModelScope.launch { verify(number) }
    }

    /** Operator confirmed the displayed member — the composite is already member-verified. */
    fun onConfirm() {
        if (_uiState.value.phase !is MemberScanPhase.Confirm) return
        _uiState.value = MemberScanUiState(MemberScanPhase.Verified)
    }

    /** Return to a scannable state after a failure (session/prior steps are preserved) (FR-009). */
    fun retry() {
        stopScan()
        checkAvailability()
    }

    private suspend fun verify(number: MemberNumber) {
        _uiState.value = MemberScanUiState(MemberScanPhase.Verifying)
        _uiState.value = when (val result = verifyMember(number)) {
            is AppResult.Success -> result.data.member
                ?.let { MemberScanUiState(MemberScanPhase.Confirm(it)) }
                ?: MemberScanUiState(
                    MemberScanPhase.Failed(
                        errorMapper.toUserMessage(AppError.Business(BusinessCode.PATIENT_NOT_FOUND)),
                        retryable = false,
                    ),
                )
            else -> MemberScanUiState(
                MemberScanPhase.Failed(map(result), retryable = isRetryable(result)),
            )
        }
    }

    private fun map(result: AppResult<*>): UiMessage =
        errorMapper.toUserMessage(result.appErrorOrNull() ?: AppError.Business(BusinessCode.GENERIC))

    private fun isRetryable(result: AppResult<*>): Boolean =
        result is AppResult.TransientFailure || result is AppResult.Timeout
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*MemberScanViewModelTest*'
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Add the screen strings**

In `app/src/main/res/values/strings.xml`, replace the whole `<!-- ===== NFC document scan (US2) ===== -->` block (lines 31–52) with:

```xml
    <!-- ===== Member card scan (US2) ===== -->
    <string name="card_title">Scan member card</string>
    <string name="card_prompt_tap">Hold the member card flat against the back of the phone and keep it still.</string>
    <string name="card_unavailable_disabled">NFC is turned off. Turn it on in settings, or enter the card number instead.</string>
    <string name="card_unavailable_none">This device has no NFC reader. Enter the card number printed on the card.</string>
    <string name="card_manual_title">Enter card number</string>
    <string name="card_manual_desc">Type the number printed on the member card.</string>
    <string name="card_number_label">Card number</string>
    <string name="card_reading">Reading card…</string>
    <string name="card_verifying">Checking membership…</string>
    <string name="card_confirm_title">Confirm member</string>
    <string name="card_confirm_desc">Check these details match the person in front of you.</string>
    <string name="card_field_name">Name</string>
    <string name="card_field_number">Card number</string>
    <string name="card_field_dob">Date of birth</string>
    <string name="card_field_status">Membership</string>
    <string name="card_field_plan">Plan</string>
    <string name="card_confirm_button">Looks correct</string>
    <string name="card_submit_button">Check membership</string>
```

- [ ] **Step 6: Write the screen**

Create `app/src/main/java/com/mediplus/faceverify/ui/memberscan/MemberScanScreen.kt`, modelled directly on the existing `NfcScanScreen.kt` (same imports, same `LocalSpacing`, same `Field` helper, same `ErrorState`/`LoadingState` usage). The differences that matter:

- `MemberScanRoute` needs no `AccessKeyDeriver`; it wires `onManualEntry = viewModel::showManualEntry`, `onSubmitNumber = viewModel::submitManualNumber`, `onConfirm = viewModel::onConfirm`, `onRetry = viewModel::retry`, `onOpenSettings` as today.
- The `when (val phase = state.phase)` gains a `MemberScanPhase.ManualEntry -> ManualEntryContent(onSubmitNumber, modifier)` branch and maps `Verifying` to `LoadingState(messageRes = R.string.card_verifying)`.
- `UnavailableContent` keeps the "Open settings" button for `DISABLED` and additionally shows an `OutlinedButton` calling `onManualEntry` in both unavailable states — manual entry means no-NFC is no longer a dead end.
- `ReadyToScanContent` shows `card_title` / `card_prompt_tap` plus an `OutlinedButton` for `onManualEntry`.
- `ManualEntryContent` is a single field:

```kotlin
@Composable
private fun ManualEntryContent(
    onSubmitNumber: (String) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    var number by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.card_manual_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.card_manual_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        OutlinedTextField(
            value = number,
            // Filtering here is ergonomics, not validation — MemberNumber.parse is the rule.
            onValueChange = { number = it.filter(Char::isDigit).take(MemberNumber.MAX_LENGTH) },
            label = { Text(stringResource(R.string.card_number_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSubmitNumber(number) },
            enabled = number.length >= MemberNumber.MIN_LENGTH,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.lg).heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.card_submit_button)) }
    }
}
```

- `ConfirmContent(member: MemberDetails, …)` shows `card_field_name` → `member.fullName`, `card_field_number` → `member.memberNumber`, `card_field_dob` → `member.dateOfBirth`, `card_field_status` → `member.membershipStatus`, and `card_field_plan` → `member.plan` (skip the plan row when null).

- [ ] **Step 7: Verify compile + full suite**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL, 0 failures, lint clean.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/ui/memberscan/ \
        app/src/main/res/values/strings.xml \
        app/src/test/java/com/mediplus/faceverify/ui/memberscan/
git commit -m "feat: add the member card scan screen with manual entry fallback"
```

---

## Task 10: Point navigation at the new screen

**Files:**
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/mediplus/faceverify/ui/navigation/NavGraph.kt`

**Interfaces:**
- Consumes: `MemberScanRoute` (Task 9), `JourneyStep.MEMBER_SCAN` (Task 2).
- Produces: `AppRoute.MemberScan("memberscan", JourneyStep.MEMBER_SCAN)`.

- [ ] **Step 1: Rename the route**

Replace `app/src/main/java/com/mediplus/faceverify/ui/navigation/AppRoute.kt` with:

```kotlin
package com.mediplus.faceverify.ui.navigation

import com.mediplus.faceverify.domain.model.JourneyStep

/**
 * The four destinations of the enforced sequential journey (FR-032). Each maps to the
 * [JourneyStep] a user must have reached to be allowed here; nav guards enforce reachability.
 */
enum class AppRoute(val path: String, val requiredStep: JourneyStep) {
    SignIn("signin", JourneyStep.NOT_SIGNED_IN),
    MemberScan("memberscan", JourneyStep.MEMBER_SCAN),
    FaceCheck("face", JourneyStep.FACE_CHECK),
    AddService("addservice", JourneyStep.ENROLLMENT),
}
```

- [ ] **Step 2: Rewire the graph**

In `app/src/main/java/com/mediplus/faceverify/ui/navigation/NavGraph.kt`:

- Change the import `com.mediplus.faceverify.ui.nfcscan.NfcScanRoute` → `com.mediplus.faceverify.ui.memberscan.MemberScanRoute`.
- Replace every `AppRoute.NfcScan` with `AppRoute.MemberScan` and `NfcScanRoute(` with `MemberScanRoute(`.
- Update the comment at line 82 to read `// Journey complete: return to the card step to process the next patient.`
- Delete the now-dead `PlaceholderDestination` composable (lines 93–98) and its `Box`/`fillMaxSize`/`Text`/`Alignment`/`Modifier` imports if nothing else uses them.

- [ ] **Step 3: Verify**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/mediplus/faceverify/ui/navigation/
git commit -m "feat: route sign-in to the member card scan step"
```

---

## Task 11: Delete the eMRTD path

Nothing references it now. This task is pure removal — if anything fails to compile, a previous task left a reference behind.

**Files:**
- Delete: `app/src/main/java/com/mediplus/faceverify/core/nfc/NfcReader.kt`, `core/nfc/AccessKeyDeriver.kt`
- Delete: `app/src/main/java/com/mediplus/faceverify/ui/nfcscan/` (whole directory)
- Delete: `app/src/main/java/com/mediplus/faceverify/data/remote/DocumentApi.kt`, `data/repository/DocumentRepository.kt`
- Delete: `app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyDocumentUseCase.kt`, `domain/model/DocumentModels.kt`
- Delete: `app/src/debug/java/com/mediplus/faceverify/dev/nfc/FakeNfcReader.kt`, `dev/nfc/SwitchingNfcReader.kt`, `dev/repository/FakeDocumentRepository.kt`
- Delete: the corresponding tests under `app/src/test` and `app/src/testDebug`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/result/AppResult.kt`, `core/result/ErrorMapper.kt`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt`, both `NfcModule.kt` files
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1: Delete the source files**

```bash
git rm -r app/src/main/java/com/mediplus/faceverify/ui/nfcscan \
          app/src/main/java/com/mediplus/faceverify/core/nfc/NfcReader.kt \
          app/src/main/java/com/mediplus/faceverify/core/nfc/AccessKeyDeriver.kt \
          app/src/main/java/com/mediplus/faceverify/data/remote/DocumentApi.kt \
          app/src/main/java/com/mediplus/faceverify/data/repository/DocumentRepository.kt \
          app/src/main/java/com/mediplus/faceverify/domain/usecase/VerifyDocumentUseCase.kt \
          app/src/main/java/com/mediplus/faceverify/domain/model/DocumentModels.kt \
          app/src/debug/java/com/mediplus/faceverify/dev/nfc/FakeNfcReader.kt \
          app/src/debug/java/com/mediplus/faceverify/dev/nfc/SwitchingNfcReader.kt \
          app/src/debug/java/com/mediplus/faceverify/dev/repository/FakeDocumentRepository.kt \
          app/src/test/java/com/mediplus/faceverify/ui/nfcscan \
          app/src/test/java/com/mediplus/faceverify/domain/usecase/VerifyDocumentUseCaseTest.kt \
          app/src/test/java/com/mediplus/faceverify/data/remote/DocumentApiContractTest.kt \
          app/src/testDebug/java/com/mediplus/faceverify/dev/FakeNfcReaderTest.kt \
          app/src/testDebug/java/com/mediplus/faceverify/dev/SwitchingNfcReaderTest.kt \
          app/src/testDebug/java/com/mediplus/faceverify/dev/FakeDocumentRepositoryTest.kt
```

Also `git rm` `app/src/test/java/com/mediplus/faceverify/core/nfc/AccessKeyDeriverTest.kt` if it exists.

- [ ] **Step 2: Remove the DI bindings and the document API provider**

In `app/src/main/java/com/mediplus/faceverify/core/di/ApiModule.kt`, delete `provideDocumentApi` and the `DocumentApi` import.
In both `NfcModule.kt` files, delete the `bindNfcReader` function and its now-unused imports, leaving only the `MemberCardReader` binding.
In both `RepositoryModule.kt` files, delete the `bindDocumentRepository` function and its imports.

- [ ] **Step 2a: Remove the superseded dev-fake set**

Task 8 deliberately left these in place so the debug source set stayed compilable. Remove them now, in one pass:

- `app/src/debug/.../dev/DevScenarios.kt` — delete `NfcScenario` and `DocumentScenario`.
- `app/src/debug/.../dev/DevSettings.kt` — delete the `nfc` and `document` fields, the `NFC` and `DOCUMENT` keys, and their two `toDevSettings()` lines.
- `app/src/debug/.../dev/DevSettingsStore.kt` — delete `setNfc` and `setDocument` from the interface and the implementation.
- `app/src/testDebug/.../dev/TestDevSettingsStore.kt` — delete the two matching overrides.
- `app/src/debug/.../dev/repository/SwitchingRepositories.kt` — delete `SwitchingDocumentRepository` and its imports.
- `app/src/debug/.../dev/FakeData.kt` — delete `readDocument`, `validationValid`, `validationInvalid`, and the now-unused `DocIntegrityResult`, `DocumentIdentity`, `DocumentValidation`, `ReadDocument`, `LocalDate` imports.
- `app/src/debug/.../dev/ui/DevSettingsScreen.kt` and `DevSettingsViewModel.kt` — delete the "NFC" and "Document" pickers, leaving "Card" and "Member".
- `app/src/testDebug/.../dev/DevSettingsMappingTest.kt` and `SwitchingRepositoryTest.kt` — delete the cases covering the removed fields and the removed switching repo.

The persisted `dev_scenario_nfc` / `dev_scenario_document` DataStore keys are simply abandoned. `DevSettings.kt:35` falls back to the default on an unknown key, so no migration is needed and a device carrying old values keeps working.

- [ ] **Step 3: Remove the retired business codes**

In `app/src/main/java/com/mediplus/faceverify/core/result/AppResult.kt`, delete `DOCUMENT_INVALID` and `DOCUMENT_EXPIRED` from `BusinessCode`.
In `core/result/ErrorMapper.kt`, delete their two `businessMessage` branches.
In `app/src/main/res/values/strings.xml`, delete `err_document_invalid_title`/`_body` and `err_document_expired_title`/`_body`.
In `app/src/test/java/com/mediplus/faceverify/core/result/ErrorMapperTest.kt`, delete any test asserting on those two codes.

- [ ] **Step 4: Drop the three eMRTD dependencies**

In `app/build.gradle.kts`, delete lines 143–146 (the `// NFC / eMRTD` comment and the three `implementation` lines).
In `gradle/libs.versions.toml`, delete the `jmrtd`/`scubaScAndroid`/`bouncycastle` version entries (lines 32–35) and the three library entries (lines 112–115).

- [ ] **Step 5: Verify nothing references the removed code**

```bash
grep -rn 'jmrtd\|scuba\|JmrtdNfcReader\|NfcReader\|AccessKeyDeriver\|DocumentApi\|DocumentRepository\|ReadDocument\|DocumentValidation\|DocAccessKey\|DocumentIdentity\|DocIntegrityResult\|DOCUMENT_INVALID\|DOCUMENT_EXPIRED\|nfcscan' \
  app/src gradle/libs.versions.toml app/build.gradle.kts
```

Expected: no matches.

- [ ] **Step 6: Full verification**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL, 0 failures, lint clean.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor!: remove the eMRTD document path and its dependencies

Deletes the JMRTD reader, access-key derivation, /documents/validate, and
the nfcscan screen, along with the jmrtd, scuba-sc-android, and
bouncycastle-prov dependencies."
```

---

## Task 12: Prove the member number never reaches the logs

The card number identifies a patient, so it must not appear in an OkHttp log line. `LoggingRedactionTest` already asserts this class of property for the existing fields; extend it to the new one.

**Files:**
- Modify: `app/src/test/java/com/mediplus/faceverify/core/LoggingRedactionTest.kt`

**Interfaces:**
- Consumes: `MemberNumber` (Task 1), the logging interceptor under test.
- Produces: nothing.

- [ ] **Step 1: Read the existing test to match its shape**

```bash
cat app/src/test/java/com/mediplus/faceverify/core/LoggingRedactionTest.kt
```

It currently references `memberNumber` (renamed from `documentNumber` in Task 2). Note how it builds its interceptor and captures log output — reuse that exact harness rather than inventing a second one.

- [ ] **Step 2: Add the failing assertions**

Add two cases to the existing class, matching its established harness:

1. A `POST members/verify` request whose body contains `"memberNumber":"1234567"` produces log output that does **not** contain `1234567`. The interceptor is configured at `HEADERS` level, so bodies are never logged — this test pins that guarantee against a future level change.
2. `MemberNumber.parse("1234567")!!.toString()` does not contain `1234567`, so an accidental string interpolation cannot leak it.

- [ ] **Step 3: Run the test**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:testDebugUnitTest --tests '*LoggingRedactionTest*'
```

Expected: PASS. If case 1 fails, the interceptor level was raised somewhere — fix the interceptor, not the test.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/mediplus/faceverify/core/LoggingRedactionTest.kt
git commit -m "test: pin that the member number never reaches the logs"
```

---

## Task 13: Amend the feature spec

**Files:**
- Modify: `specs/001-identity-verification-enrollment/spec.md`
- Rename: `specs/001-identity-verification-enrollment/contracts/nfc-document-api.md` → `contracts/member-card-api.md`
- Modify: `specs/001-identity-verification-enrollment/data-model.md`, `quickstart.md`, `plan.md`

- [ ] **Step 1: Rewrite the affected requirements in `spec.md`**

- **FR-011a reverses.** Replace its current text with: *"The app MUST use the member card number as the key that identifies the patient. The number is read from the card by NFC; when the card is unreadable the operator MAY enter it manually, and the same format rule (digits only, longer than 6 characters) applies to both paths."*
- **FR-007 – FR-011** re-scoped from identity document to member card. Drop any local-expiry language: a member card carries no expiry, so validity is server-owned.
- **FR-032** journey becomes `sign in → scan member card → live face check → add service`.
- **User Story 2** retitle to *"Verify a person's membership with their member card"*; rewrite its four acceptance scenarios for tap → verify → confirm, unreadable card → manual entry, membership rejected → specific reason, NFC unavailable → manual entry.
- **Key Entity** `Identity Document` → `Member Card`: unique card number (patient lookup key, digits only, >6 chars), and the server-returned member details (name, date of birth, membership status, plan).
- Update the Clarification at line 22 to name the member card number rather than the document number.

- [ ] **Step 2: Rewrite the contract doc**

```bash
git mv specs/001-identity-verification-enrollment/contracts/nfc-document-api.md \
       specs/001-identity-verification-enrollment/contracts/member-card-api.md
```

Rewrite its body for the on-device pre-step (NDEF text record, no access key, no secure messaging) followed by `POST /members/verify`, mirroring the request/response shape now in `docs/openapi.yaml`.

- [ ] **Step 3: Update the remaining spec artifacts**

In `data-model.md`, replace the `ReadDocument`/`DocumentValidation`/`DocumentIdentity` entities with `MemberNumber`, `MemberVerification`, and `MemberDetails`, and rename `VerifiedIdentity.documentNumber`/`documentVerified`.
In `quickstart.md`, replace the passport-tap walkthrough with the member card tap and the manual-entry fallback.
In `plan.md`, update any architecture prose naming JMRTD, BAC, DG1/DG2, or `/documents/validate`.

- [ ] **Step 4: Check for stragglers**

```bash
grep -rn 'documentNumber\|eMRTD\|JMRTD\|DG1\|DG2\|BAC\|documents/validate' specs/ docs/openapi.yaml
```

Expected: no matches outside historical notes.

- [ ] **Step 5: Commit**

```bash
git add specs/
git commit -m "docs: amend spec 001 for member card verification"
```

---

## Task 14: Final verification

- [ ] **Step 1: Clean full build and the complete gate**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL. Record the actual test total and confirm 0 failures, 0 errors:

```bash
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t, "skipped="s, "failures="f, "errors="e}'
```

Baseline before this work was 135 tests. Expect roughly 155–165 after; the exact number depends on how many document tests were removed versus member tests added. **Report the real number, not the estimate.**

- [ ] **Step 2: Confirm the release variant excludes all dev code**

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. The release variant binds `NdefMemberCardReader` and `MemberRepositoryImpl` directly, with no `Switching*` or `Fake*` class on the classpath.

- [ ] **Step 3: Device-gated work — record, do not claim**

The following cannot be verified in this environment and must be reported as outstanding, not as passing:
- `NdefMemberCardReader` against real card stock — the whole NDEF decode path is untested until a physical card is tapped.
- `NfcAvailabilityTest` and `FaceCaptureTest` instrumented suites — they compile, they have not run.
- End-to-end walkthrough of the amended `quickstart.md`.

- [ ] **Step 4: Update the project memory**

Update `faceverify-verification-status.md` in the memory directory to record that the eMRTD/JMRTD path is gone, that `NdefMemberCardReader` is the new device-gated component, and that the BAC-only limitation note is obsolete.
