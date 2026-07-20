package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.dev.repository.FakeDocumentRepository
import com.mediplus.faceverify.dev.repository.SwitchingDocumentRepository
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SwitchingRepositoryTest {

    private val read = ReadDocument(
        documentNumber = "X123",
        identity = DocumentIdentity("X123", "Doe", "Jane", "1990-01-01", "UTO", "F", LocalDate.of(2030, 1, 1), "GOV"),
        referencePhoto = null,
        securityObjectBase64 = null,
        dataGroupHashes = emptyMap(),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    private val realImpl = mockk<DocumentRepositoryImpl>().also {
        coEvery { it.validate(any()) } returns AppResult.Success(
            DocumentValidation(DocumentValidation.Authenticity.VALID, "REAL", true, true, true),
        )
    }

    @Test
    fun `delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val switching = SwitchingDocumentRepository(realImpl, FakeDocumentRepository(store), store)

        val result = switching.validate(read) as AppResult.Success
        assertEquals(null, result.data.reason) // fake VALID has null reason
    }

    @Test
    fun `delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val switching = SwitchingDocumentRepository(realImpl, FakeDocumentRepository(store), store)

        val result = switching.validate(read) as AppResult.Success
        assertEquals("REAL", result.data.reason)
    }
}
