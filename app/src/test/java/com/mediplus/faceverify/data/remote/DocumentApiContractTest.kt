package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * T025 — Document validation contract (FR-008): VALID, INVALID+reason, transient (5xx), and timeout
 * each map to the correct [AppResult] through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DocumentRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val api: DocumentApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = DocumentRepositoryImpl(api, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun readDocument() = ReadDocument(
        documentNumber = "P1234567",
        identity = DocumentIdentity(
            "P1234567", "DOE", "JANE", "900101", "UTO", "F", LocalDate.of(2030, 1, 1), "UTO",
        ),
        referencePhoto = null,
        securityObjectBase64 = "c29k",
        dataGroupHashes = mapOf("DG1" to "aGFzaA=="),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    @Test
    fun `VALID document maps to a verified validation`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"authenticity":"VALID","documentVerified":true,"referenceOnFile":true,"patientResolved":true}""",
            ),
        )

        val result = repository.validate(readDocument())

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.VALID, validation.authenticity)
        assertTrue(validation.documentVerified)
    }

    @Test
    fun `INVALID document carries the specific reason`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"authenticity":"INVALID","reason":"integrity-failed","documentVerified":false,"patientResolved":true}""",
            ),
        )

        val result = repository.validate(readDocument())

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.INVALID, validation.authenticity)
        assertEquals("integrity-failed", validation.reason)
    }

    @Test
    fun `server error maps to transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.validate(readDocument())

        assertTrue(result is AppResult.TransientFailure)
        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `no response within the timeout maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        val result = repository.validate(readDocument())

        assertEquals(AppResult.Timeout, result)
    }
}
