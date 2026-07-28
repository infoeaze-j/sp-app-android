package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.data.repository.EnrollmentRepositoryImpl
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * T048 — Enrollment contract (FR-020, FR-022, FR-023) against `members.services.index`,
 * `members.enrollments.store` and `members.enrollments.show`: list, confirmed enroll, duplicate,
 * ineligible, timeout, and the idempotent re-check each map to the correct [AppResult].
 *
 * The store response is exercised in both spellings the spec makes plausible — the documented bare
 * string and the object the show endpoint documents on the same path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: EnrollmentRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        val api: EnrollmentApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = EnrollmentRepositoryImpl(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun enroll() =
        repository.enroll("1234567", EnrollmentRequest("s1", "ver-1", "ZAR", Money(15_000), "key1"))

    @Test
    fun `lists eligible services with their currencies and the visit date`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"id":"s1","code":"CONS","description":"Consultation",""" +
                    """"eligibleForPatient":true,"alreadyEnrolled":false}],""" +
                    """"currencies":[{"code":"ZAR","label":"Rand (R)","minorUnitExponent":2,"isDefault":true},""" +
                    """{"code":"JPY","label":"Yen","minorUnitExponent":0,"isDefault":false}],""" +
                    """"visitDate":"2026-07-20"}""",
            ),
        )

        val result = repository.listServices("1234567")

        val catalog = (result as AppResult.Success).data
        assertEquals("/members/1234567/services", server.takeRequest().path)
        assertEquals(1, catalog.services.size)
        assertEquals("s1", catalog.services.first().serviceId)
        assertEquals("CONS", catalog.services.first().code)
        assertEquals("Consultation", catalog.services.first().description)
        assertEquals("ZAR", catalog.currencies.first().code)
        assertEquals(2, catalog.currencies.first().minorUnitExponent)
        assertTrue(catalog.currencies.first().isDefault)
        assertEquals(0, catalog.currencies[1].minorUnitExponent)
        assertEquals("2026-07-20", catalog.visitDate)
    }

    @Test
    fun `a services response with no currencies key parses to an empty list`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"id":"s1","code":"CONS","description":"Consultation",""" +
                    """"eligibleForPatient":true,"alreadyEnrolled":false}],"visitDate":"2026-07-20"}""",
            ),
        )

        val result = repository.listServices("1234567")

        assertTrue((result as AppResult.Success).data.currencies.isEmpty())
    }

    @Test
    fun `a confirmed enrollment returned as an object succeeds`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"enrollmentId":"E1","status":"recorded",""" +
                    """"service":{"id":"s1","code":"CONS","description":"Consultation"},""" +
                    """"currency":"ZAR","amountMinor":15000,"visitDate":"2026-07-20",""" +
                    """"recordedAt":"2026-07-20T12:40:00Z"}""",
            ),
        )

        val result = enroll()

        val data = (result as AppResult.Success).data
        assertEquals("E1", data.enrollmentId)
        assertEquals("ZAR", data.currency)
        assertEquals(Money(15_000), data.amount)
        assertEquals("Consultation", data.service.description)
        assertEquals("2026-07-20", data.visitDate)
    }

    @Test
    fun `a confirmed enrollment returned as a bare id string also succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""E1""""))

        val result = enroll()

        val data = (result as AppResult.Success).data
        assertEquals("E1", data.enrollmentId)
        // The caller's own values stand in for whatever a bare id cannot carry.
        assertEquals("ZAR", data.currency)
        assertEquals(Money(15_000), data.amount)
    }

    @Test
    fun `the enroll body carries the verification id, currency and minor units`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""""E1""""))

        enroll()

        val recorded = server.takeRequest()
        assertEquals("/members/1234567/enrollments", recorded.path)
        val json = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("s1", json.getValue("serviceId").jsonPrimitive.content)
        assertEquals("ver-1", json.getValue("verificationId").jsonPrimitive.content)
        assertEquals("key1", json.getValue("idempotencyKey").jsonPrimitive.content)
        assertEquals("ZAR", json.getValue("currency").jsonPrimitive.content)
        assertEquals(15_000L, json.getValue("amountMinor").jsonPrimitive.content.toLong())
    }

    @Test
    fun `duplicate is prevented`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"code":"ENROLLMENT_DUPLICATE","message":"Already added"}}"""),
        )

        val result = enroll()

        assertEquals(BusinessCode.DUPLICATE_SERVICE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `ineligible is a specific rejection`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"message":"Not eligible.","errors":{"serviceId":["Not eligible."]}}"""),
        )

        val result = enroll()

        assertEquals(BusinessCode.SERVICE_INELIGIBLE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `a stale verification is reported by its error code, not its status`() = runTest {
        // The spec's preamble is explicit that clients branch on error.code, never on status alone.
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"error":{"code":"VERIFICATION_STALE","message":"Expired","details":{}}}"""),
        )

        val result = enroll()

        val error = (result as AppResult.BusinessRejection).error
        assertEquals(BusinessCode.NOT_CURRENTLY_VERIFIED, error.code)
        assertEquals("VERIFICATION_STALE", error.serverReason)
    }

    @Test
    fun `a success we cannot read is uncertain, never a confirmation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        val result = enroll()

        assertTrue("expected a transient failure, got $result", result is AppResult.TransientFailure)
    }

    @Test
    fun `timeout mid-submit is uncertain, never success`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        assertEquals(AppResult.Timeout, enroll())
    }

    @Test
    fun `recheck returns null when nothing was created`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.recheck("1234567", "key1")

        assertTrue(result is AppResult.Success)
        assertNull((result as AppResult.Success).data)
    }

    @Test
    fun `recheck reads an empty object as never created`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = repository.recheck("1234567", "key1")

        assertNull((result as AppResult.Success).data)
    }

    @Test
    fun `recheck finding a confirmed enrollment carries what the server echoed back`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"enrollmentId":"E1","status":"recorded",""" +
                    """"service":{"id":"s1","code":"CONS","description":"Consultation"},""" +
                    """"currency":"ZAR","amountMinor":15000,"visitDate":"2026-07-20",""" +
                    """"recordedAt":"2026-07-20T12:40:00Z"}""",
            ),
        )

        val result = repository.recheck("1234567", "key1")

        val enrollment = (result as AppResult.Success).data
        assertEquals("E1", enrollment?.enrollmentId)
        assertEquals("ZAR", enrollment?.currency)
        assertEquals(Money(15_000), enrollment?.amount)
        assertEquals(
            "/members/1234567/enrollments?idempotencyKey=key1",
            server.takeRequest().path,
        )
    }
}
