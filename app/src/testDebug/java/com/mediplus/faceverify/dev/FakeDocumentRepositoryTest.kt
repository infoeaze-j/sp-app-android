package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.dev.repository.FakeDocumentRepository
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FakeDocumentRepositoryTest {

    private val read = ReadDocument(
        documentNumber = "X123",
        identity = DocumentIdentity("X123", "Doe", "Jane", "1990-01-01", "UTO", "F", LocalDate.of(2030, 1, 1), "GOV"),
        referencePhoto = null,
        securityObjectBase64 = null,
        dataGroupHashes = emptyMap(),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    @Test
    fun `success returns a VALID verified document`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.SUCCESS, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.VALID, validation.authenticity)
        assertEquals(true, validation.documentVerified)
    }

    @Test
    fun `invalid scenario is a 200 with INVALID authenticity, not a rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.INVALID, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        val validation = (result as AppResult.Success).data
        assertEquals(DocumentValidation.Authenticity.INVALID, validation.authenticity)
        assertEquals(false, validation.documentVerified)
    }

    @Test
    fun `patient not found is a business rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(document = DocumentScenario.PATIENT_NOT_FOUND, latencyMillis = 0L))

        val result = FakeDocumentRepository(store).validate(read)

        assertEquals(BusinessCode.PATIENT_NOT_FOUND, (result as AppResult.BusinessRejection).error.code)
    }
}
