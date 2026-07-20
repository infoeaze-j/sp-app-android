package com.mediplus.faceverify.data.remote

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

    @GET("patients/{documentNumber}/services")
    suspend fun listServices(@Path("documentNumber") documentNumber: String): Response<ServicesResponse>

    @POST("patients/{documentNumber}/enrollments")
    suspend fun enroll(
        @Path("documentNumber") documentNumber: String,
        @Body body: EnrollRequest,
    ): Response<EnrollmentResponse>

    @GET("patients/{documentNumber}/enrollments")
    suspend fun recheck(
        @Path("documentNumber") documentNumber: String,
        @Query("idempotencyKey") idempotencyKey: String,
    ): Response<EnrollmentResponse>
}

@Serializable
data class ServicesResponse(val services: List<ServiceDto> = emptyList())

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
)

@Serializable
data class EnrollmentResponse(
    val enrollmentId: String? = null,
    val status: String,
    val reason: String? = null,
    val timestamp: String? = null,
)
