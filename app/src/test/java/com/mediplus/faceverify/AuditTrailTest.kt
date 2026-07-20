package com.mediplus.faceverify

import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.VerificationAttempt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T059 — audit/outcome records carry metadata only and never raw biometrics (FR-017, FR-030). The
 * types that represent recorded outcomes (sign-in/document/face/enrollment) must not declare any
 * ByteArray (image) field. The single biometric ByteArray in the codebase lives on the transient
 * read/frame types, never on an audit record.
 */
class AuditTrailTest {

    private val auditTypes = listOf(
        VerificationAttempt::class.java,
        FaceDecision::class.java,
        DocumentValidation::class.java,
        Enrollment::class.java,
    )

    @Test
    fun `audit records declare no raw image bytes`() {
        auditTypes.forEach { type ->
            val byteArrayFields = type.declaredFields.filter { it.type == ByteArray::class.java }
            assertTrue(
                "${type.simpleName} must not carry raw biometric bytes; found: $byteArrayFields",
                byteArrayFields.isEmpty(),
            )
        }
    }
}
