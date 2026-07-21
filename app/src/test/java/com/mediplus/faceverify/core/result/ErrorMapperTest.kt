package com.mediplus.faceverify.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T010 — ErrorMapper guarantees (FR-021, FR-029):
 *  - every [AppError] maps to a message (non-zero title + body resource),
 *  - the mapper NEVER incorporates a server reason into the message (non-revealing),
 *  - [UiMessage] is structurally incapable of carrying free text (no String fields), so
 *    identity/biometric data can never leak through it.
 */
class ErrorMapperTest {

    private val mapper = DefaultErrorMapper()

    private fun allErrors(): List<AppError> = buildList {
        BusinessCode.entries.forEach { add(AppError.Business(it)) }
        TransientKind.entries.forEach { add(AppError.Transient(it)) }
        add(AppError.Timeout)
        SessionErrorKind.entries.forEach { add(AppError.SessionInvalid(it)) }
    }

    @Test
    fun `every error maps to a message with a title and body`() {
        allErrors().forEach { error ->
            val message = mapper.toUserMessage(error)
            assertNotEquals("title missing for $error", 0, message.titleRes)
            assertNotEquals("body missing for $error", 0, message.bodyRes)
        }
    }

    @Test
    fun `server reason never changes the mapped message (non-revealing)`() {
        val sensitive = "memberNumber=99887766 name=DOE face-embedding=0xDEADBEEF token=abc123"
        BusinessCode.entries.forEach { code ->
            val withReason = mapper.toUserMessage(AppError.Business(code, serverReason = sensitive))
            val withoutReason = mapper.toUserMessage(AppError.Business(code, serverReason = null))
            assertEquals(
                "mapping for $code must not depend on the server reason",
                withoutReason,
                withReason,
            )
        }
    }

    @Test
    fun `UiMessage has no free-text field that could leak sensitive data`() {
        val stringFields = UiMessage::class.java.declaredFields.filter {
            it.type == String::class.java
        }
        assertTrue(
            "UiMessage must reference resources only, never raw String text; found: $stringFields",
            stringFields.isEmpty(),
        )
    }

    @Test
    fun `distinct business codes surface distinct, specific messages`() {
        // Invalid credentials must not read the same as a document rejection, etc. (FR-021 specificity).
        val invalidCreds = mapper.toUserMessage(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
        val docInvalid = mapper.toUserMessage(AppError.Business(BusinessCode.DOCUMENT_INVALID))
        val duplicate = mapper.toUserMessage(AppError.Business(BusinessCode.DUPLICATE_SERVICE))
        assertNotEquals(invalidCreds.bodyRes, docInvalid.bodyRes)
        assertNotEquals(docInvalid.bodyRes, duplicate.bodyRes)
    }
}
