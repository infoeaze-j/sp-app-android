package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.domain.model.Money
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/**
 * T048 — Enrollment contract (FR-020, FR-022, FR-023): list, confirmed enroll, duplicate,
 * ineligible, timeout, and the idempotent re-check each map to the correct [AppResult].
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

    @Test
    fun `lists eligible services with their currencies`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"serviceId":"s1","description":"Consultation","eligibleForPatient":true,"alreadySelected":false}],"currencies":[{"value":"ZAR","label":"Rand (R)"}]}""",
            ),
        )

        val result = repository.listServices("P1")

        val catalog = (result as AppResult.Success).data
        assertEquals(1, catalog.services.size)
        assertEquals("Consultation", catalog.services.first().description)
        assertEquals(1, catalog.currencies.size)
        assertEquals("ZAR", catalog.currencies.first().value)
        assertEquals("Rand (R)", catalog.currencies.first().label)
    }

    @Test
    fun `a services response with no currencies key parses to an empty list`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"serviceId":"s1","description":"Consultation","eligibleForPatient":true,"alreadySelected":false}]}""",
            ),
        )

        val result = repository.listServices("P1")

        assertTrue((result as AppResult.Success).data.currencies.isEmpty())
    }

    @Test
    fun `confirmed enrollment succeeds`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"enrollmentId":"E1","status":"CONFIRMED","timestamp":"2026-07-20T12:40:00Z"}""",
            ),
        )

        val result = repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        assertTrue(result is AppResult.Success)
        assertEquals("E1", (result as AppResult.Success).data.enrollmentId)
    }

    @Test
    fun `the enroll body carries the currency and the amount in cents`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"enrollmentId":"E1","status":"CONFIRMED","timestamp":"2026-07-20T12:40:00Z"}""",
            ),
        )

        repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("currency missing from $body", body.contains(""""currency":"ZAR""""))
        assertTrue("amountCents missing from $body", body.contains(""""amountCents":15000"""))
    }

    @Test
    fun `duplicate is prevented`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"status":"DUPLICATE"}"""))

        val result = repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        assertEquals(BusinessCode.DUPLICATE_SERVICE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `ineligible is a specific rejection`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"status":"REJECTED","reason":"ineligible"}"""))

        val result = repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        assertEquals(BusinessCode.SERVICE_INELIGIBLE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `timeout mid-submit is uncertain, never success`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        val result = repository.enroll("P1", "s1", "ZAR", Money(15_000), "key1")

        assertEquals(AppResult.Timeout, result)
    }

    @Test
    fun `recheck returns null when nothing was created`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.recheck("P1", "key1")

        assertTrue(result is AppResult.Success)
        assertNull((result as AppResult.Success).data)
    }
}
