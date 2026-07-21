package com.mediplus.faceverify.core.result

import com.mediplus.faceverify.R
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
        // Invalid credentials must not read the same as a membership rejection, etc. (FR-021 specificity).
        val invalidCreds = mapper.toUserMessage(AppError.Business(BusinessCode.INVALID_CREDENTIALS))
        val memberInvalid = mapper.toUserMessage(AppError.Business(BusinessCode.MEMBER_INVALID))
        val duplicate = mapper.toUserMessage(AppError.Business(BusinessCode.DUPLICATE_SERVICE))
        assertNotEquals(invalidCreds.bodyRes, memberInvalid.bodyRes)
        assertNotEquals(memberInvalid.bodyRes, duplicate.bodyRes)
    }

    @Test
    fun `member invalid maps to its own message with a rescan action`() {
        val message = mapper.toUserMessage(AppError.Business(BusinessCode.MEMBER_INVALID, "MEMBERSHIP_EXPIRED"))

        assertEquals(R.string.err_member_invalid_title, message.titleRes)
        assertEquals(R.string.err_member_invalid_body, message.bodyRes)
        assertEquals(R.string.action_rescan, message.actionRes)
    }

    @Test
    fun `an unreadable card offers manual entry rather than a bare retry`() {
        val message = mapper.toUserMessage(AppError.Business(BusinessCode.CARD_UNREADABLE))

        assertEquals(R.string.err_card_unreadable_title, message.titleRes)
        assertEquals(R.string.err_card_unreadable_body, message.bodyRes)
        assertEquals(R.string.action_enter_manually, message.actionRes)
    }
}
