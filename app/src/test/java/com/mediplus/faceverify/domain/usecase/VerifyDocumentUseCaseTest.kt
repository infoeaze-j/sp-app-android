package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.core.time.DateProvider
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * T026 — VerifyDocumentUseCase marks a document verified ONLY on locally-not-expired + server VALID,
 * and surfaces a specific reason otherwise (FR-008, FR-011a).
 */
class VerifyDocumentUseCaseTest {

    private val repository = mockk<DocumentRepository>()
    private lateinit var sessionManager: InMemorySessionManager
    private val today = LocalDate.of(2026, 7, 20)
    private lateinit var useCase: VerifyDocumentUseCase

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        useCase = VerifyDocumentUseCase(repository, sessionManager, DateProvider { today })
    }

    private fun readDocument(expiry: LocalDate = LocalDate.of(2030, 1, 1)) = ReadDocument(
        memberNumber = "P1234567",
        identity = DocumentIdentity(
            memberNumber = "P1234567",
            surname = "DOE",
            givenNames = "JANE",
            dateOfBirth = "900101",
            nationality = "UTO",
            sex = "F",
            expiryDate = expiry,
            issuingAuthority = "UTO",
        ),
        referencePhoto = null,
        securityObjectBase64 = "c29k",
        dataGroupHashes = mapOf("DG1" to "aGFzaA=="),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    private fun validation(
        authenticity: DocumentValidation.Authenticity = DocumentValidation.Authenticity.VALID,
        verified: Boolean = true,
        resolved: Boolean = true,
        reason: String? = null,
    ) = DocumentValidation(authenticity, reason, verified, referenceOnFile = true, patientResolved = resolved)

    @Test
    fun `valid document marks the composite document-verified`() = runTest {
        coEvery { repository.validate(any()) } returns AppResult.Success(validation())

        val result = useCase(readDocument())

        assertTrue(result is AppResult.Success)
        val identity = sessionManager.verifiedIdentity.value
        assertEquals("P1234567", identity?.memberNumber)
        assertTrue(identity?.memberVerified == true)
        assertFalse(identity?.faceVerified == true)
    }

    @Test
    fun `expired document is rejected locally without calling the server`() = runTest {
        val result = useCase(readDocument(expiry = LocalDate.of(2020, 1, 1)))

        assertEquals(
            BusinessCode.DOCUMENT_EXPIRED,
            (result as AppResult.BusinessRejection).error.code,
        )
        verify { repository wasNot Called }
    }

    @Test
    fun `unresolved patient is rejected`() = runTest {
        coEvery { repository.validate(any()) } returns AppResult.Success(validation(resolved = false))

        val result = useCase(readDocument())

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `server INVALID surfaces a document-invalid rejection`() = runTest {
        coEvery { repository.validate(any()) } returns
            AppResult.Success(validation(authenticity = DocumentValidation.Authenticity.INVALID, verified = false, reason = "tampered"))

        val result = useCase(readDocument())

        val error = (result as AppResult.BusinessRejection).error
        assertEquals(BusinessCode.DOCUMENT_INVALID, error.code)
        assertEquals("tampered", error.serverReason)
    }

    @Test
    fun `transient failure is propagated`() = runTest {
        coEvery { repository.validate(any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

        val result = useCase(readDocument())

        assertTrue(result is AppResult.TransientFailure)
    }
}
