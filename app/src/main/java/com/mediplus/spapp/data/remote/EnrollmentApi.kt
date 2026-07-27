package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Service enrollment endpoints (FR-018–FR-023a). Enrollment is idempotent (idempotency key) so a
 * retry after an uncertain outcome never creates a duplicate (Decision 7).
 */
interface EnrollmentApi {

    @GET("patients/{memberNumber}/services")
    suspend fun listServices(@Path("memberNumber") memberNumber: String): Response<ServicesResponse>

    @POST("patients/{memberNumber}/enrollments")
    suspend fun enroll(
        @Path("memberNumber") memberNumber: String,
        @Body body: EnrollRequest,
    ): Response<EnrollmentResponse>

    @GET("patients/{memberNumber}/enrollments")
    suspend fun recheck(
        @Path("memberNumber") memberNumber: String,
        @Query("idempotencyKey") idempotencyKey: String,
    ): Response<EnrollmentResponse>
}

@Serializable
data class ServicesResponse(
    val services: List<ServiceDto> = emptyList(),
    val currencies: List<CurrencyDto> = emptyList(),
)

@Serializable
data class CurrencyDto(val value: String, val label: String)

@Serializable
data class ServiceDto(
    val serviceId: String,
    val description: String,
    val eligibleForPatient: Boolean = false,
    val alreadySelected: Boolean = false,
)

@Serializable
data class EnrollRequest(
    val serviceId: String,
    val idempotencyKey: String,
    /** The currency's `value`, never its display label. */
    val currency: String,
    /** Minor units, so no floating-point rounding can reach the wire. */
    val amountCents: Long,
)

@Serializable
data class EnrollmentResponse(
    val enrollmentId: String? = null,
    val status: String,
    val reason: String? = null,
    val timestamp: String? = null,
)
