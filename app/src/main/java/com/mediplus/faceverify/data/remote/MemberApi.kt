package com.mediplus.faceverify.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Member card verification (FR-007–FR-011a). The card number is read on-device from an NDEF text
 * record; this endpoint returns the authoritative verdict and resolves the member.
 *
 * Placeholder contract — reconcile with the back office once it publishes its shape.
 */
interface MemberApi {

    @POST("members/verify")
    suspend fun verify(@Body body: VerifyMemberRequest): Response<VerifyMemberResponse>
}

@Serializable
data class VerifyMemberRequest(val memberNumber: String)

@Serializable
data class VerifyMemberResponse(
    val status: String,
    val reason: String? = null,
    val memberVerified: Boolean = false,
    val memberResolved: Boolean = false,
    val referenceOnFile: Boolean = false,
    val member: MemberDto? = null,
)

@Serializable
data class MemberDto(
    val memberNumber: String,
    val fullName: String = "",
    val dateOfBirth: String = "",
    val membershipStatus: String = "",
    val plan: String? = null,
)
