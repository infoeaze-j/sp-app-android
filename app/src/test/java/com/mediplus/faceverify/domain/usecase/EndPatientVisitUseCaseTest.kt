package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.domain.model.Operator
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.model.VerifiedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Ending a visit discards the patient, not the operator: the composite goes, the sign-in stays.
 * Without the first the next card scan starts with the previous patient still fully verified;
 * without the second the operator would be bounced to sign-in between every patient.
 */
class EndPatientVisitUseCaseTest {

    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var useCase: EndPatientVisitUseCase

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        useCase = EndPatientVisitUseCase(sessionManager)
    }

    private fun signedInWithVerifiedPatient() {
        sessionManager.set(
            Session(
                token = "t",
                operator = Operator("op-1", "Nurse Ada"),
                expiresAt = null,
                state = SessionState.Active,
            ),
        )
        sessionManager.setVerificationWindow(15.minutes)
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity(
                memberNumber = "1234567",
                memberVerified = true,
                faceVerified = true,
                sameSubject = true,
                verifiedAt = 1_000L,
            )
        }
    }

    @Test
    fun `the verified patient is discarded`() {
        signedInWithVerifiedPatient()

        useCase()

        assertNull(sessionManager.verifiedIdentity.value)
    }

    @Test
    fun `the operator stays signed in`() {
        signedInWithVerifiedPatient()

        useCase()

        assertNotNull(sessionManager.session.value)
        assertEquals(SessionState.Active, sessionManager.sessionState.value)
    }

    /** The window is back-office-owned and operator-scoped — re-fetching it per patient would be waste. */
    @Test
    fun `the verification window survives`() {
        signedInWithVerifiedPatient()

        useCase()

        assertEquals(15.minutes, sessionManager.verificationWindow.value)
    }

    @Test
    fun `ending a visit with no patient is a no-op`() {
        useCase()

        assertNull(sessionManager.verifiedIdentity.value)
    }
}
