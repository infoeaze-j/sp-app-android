package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.core.time.TimeProvider
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.VerifiedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * T049 — EvaluateVerifiedIdentityUseCase: composite + server-supplied freshness window blocks
 * stale/unverified; an absent window is treated as stale (FR-018, FR-024, FR-026).
 */
class EvaluateVerifiedIdentityUseCaseTest {

    private fun session() = InMemorySessionManager()

    private fun evaluate(manager: InMemorySessionManager, now: Long) =
        EvaluateVerifiedIdentityUseCase(manager, TimeProvider { now }).invoke()

    @Test
    fun `no identity is blocked on the document step`() {
        val result = evaluate(session(), now = 0)
        assertFalse(result.isCurrentlyVerified)
        assertEquals(Outstanding.DOCUMENT, result.outstanding)
    }

    @Test
    fun `document-only is blocked on the face step`() {
        val manager = session().apply {
            updateVerifiedIdentity { VerifiedIdentity("P1", memberVerified = true) }
        }
        val result = evaluate(manager, now = 0)
        assertEquals(Outstanding.FACE, result.outstanding)
    }

    @Test
    fun `absent window is treated as stale`() {
        val manager = session().apply {
            updateVerifiedIdentity {
                VerifiedIdentity("P1", memberVerified = true, faceVerified = true, sameSubject = true, verifiedAt = 0)
            }
            // no setVerificationWindow → null window
        }
        val result = evaluate(manager, now = 1_000)
        assertFalse(result.isCurrentlyVerified)
        assertEquals(Outstanding.STALE, result.outstanding)
    }

    @Test
    fun `fresh verification within the window is currently verified`() {
        val manager = session().apply {
            updateVerifiedIdentity {
                VerifiedIdentity("P1", memberVerified = true, faceVerified = true, sameSubject = true, verifiedAt = 1_000)
            }
            setVerificationWindow(900.seconds)
        }
        val result = evaluate(manager, now = 1_000 + 60_000) // 60s later, window 900s
        assertTrue(result.isCurrentlyVerified)
        assertEquals(Outstanding.NONE, result.outstanding)
    }

    @Test
    fun `verification older than the window is stale`() {
        val manager = session().apply {
            updateVerifiedIdentity {
                VerifiedIdentity("P1", memberVerified = true, faceVerified = true, sameSubject = true, verifiedAt = 0)
            }
            setVerificationWindow(60.seconds)
        }
        val result = evaluate(manager, now = 120_000) // 120s later, window 60s
        assertFalse(result.isCurrentlyVerified)
        assertEquals(Outstanding.STALE, result.outstanding)
    }

    /**
     * The enrollment step asks this use case whether it may proceed; it reads the patient off the
     * same answer, so a verified evaluation that omitted them would leave the summary anonymous.
     */
    @Test
    fun `a verified evaluation names the patient it verified`() {
        val patient = MemberDetails("P1", "Jane Doe", "1985-04-12", "ACTIVE", "Gold")
        val manager = session().apply {
            updateVerifiedIdentity {
                VerifiedIdentity(
                    memberNumber = "P1",
                    memberVerified = true,
                    faceVerified = true,
                    sameSubject = true,
                    verifiedAt = 1_000,
                    patient = patient,
                )
            }
            setVerificationWindow(900.seconds)
        }

        val result = evaluate(manager, now = 1_000 + 60_000)

        assertTrue(result.isCurrentlyVerified)
        assertEquals(patient, result.patient)
    }

    @Test
    fun `no identity means no patient`() {
        assertNull(evaluate(session(), now = 0).patient)
    }
}
