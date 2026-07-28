package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Member card verification (FR-007–FR-011a) — `members.verify` in docs/openapi.json. The card
 * number is read on-device from an NDEF text record; this endpoint returns the authoritative
 * verdict and resolves the member.
 *
 * The member number travels in the body rather than the path deliberately: it is the patient key,
 * and in a URL it would land in access logs, proxy logs and history. An INVALID membership is a
 * successful call reporting a rejection, not an error — the operator has to be told the card is no
 * good, and the client has to tell that apart from a network failure.
 */
interface MemberApi {

    @POST("members/verify")
    suspend fun verify(@Body body: VerifyMemberRequest): Response<MemberVerificationResource>
}

@Serializable
data class VerifyMemberRequest(val memberNumber: String)

@Serializable
data class MemberVerificationResource(
    /** `VALID` or `INVALID`. */
    val status: String,
    /** Diagnostic only — never rendered; the operator has no business knowing why cover lapsed. */
    val reason: String? = null,
    val member: MemberDto? = null,
    val referenceOnFile: Boolean = false,
    val capabilities: MemberCapabilitiesDto = MemberCapabilitiesDto(),
)

@Serializable
data class MemberDto(
    val memberNumber: String,
    val fullName: String = "",
    val dateOfBirth: String? = null,
    val plan: String? = null,
)

@Serializable
data class MemberCapabilitiesDto(
    /** Spelled `string` in the spec beside a boolean sibling; read leniently, defaulting to false. */
    @Serializable(with = LenientBooleanSerializer::class)
    val canVerifyFace: Boolean = false,
    val canEnroll: Boolean = false,
)
