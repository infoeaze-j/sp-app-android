package com.mediplus.spapp.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Authentication & session endpoints (FR-001–FR-006). Wire shape is provisional (see
 * contracts/auth-api.md); the [com.mediplus.spapp.data.repository.AuthRepository] interface is
 * the stable seam.
 */
interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/session")
    suspend fun session(): Response<Unit>
}

@Serializable
data class LoginRequest(
    val identifier: String,
    val secret: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: String? = null,
    val operator: OperatorDto,
    val config: SessionConfigDto? = null,
)

@Serializable
data class OperatorDto(
    val operatorId: String,
    val displayName: String? = null,
    val permissions: List<String> = emptyList(),
)

@Serializable
data class SessionConfigDto(
    @SerialName("verificationWindowSeconds")
    val verificationWindowSeconds: Long? = null,
)
