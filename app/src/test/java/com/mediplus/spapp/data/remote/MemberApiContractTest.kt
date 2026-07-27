package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.MemberRepositoryImpl
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
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
