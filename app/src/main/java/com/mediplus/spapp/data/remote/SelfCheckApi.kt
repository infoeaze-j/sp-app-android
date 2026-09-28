package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.network.NO_AUTH_HEADER_LINE
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The operator self check's endpoint probes. Each asks only "does this route answer?", so every
 * call returns the raw [ResponseBody] and no DTO is parsed — any status at all is a result.
 *
 * **Every probe is marked [NO_AUTH_HEADER_LINE], including the two the contract authenticates.**
 * The self check runs from sign-in, but marking them is what makes that safe regardless:
 * [com.mediplus.spapp.core.network.AuthInterceptor] reads a 401 on a request that carried a token
 * as a session loss, so a probe must never carry one. Without a token those two routes are
 * *expected* to answer 401 — which is the answer the self check is looking for.
 */
interface SelfCheckApi {

    @Headers(NO_AUTH_HEADER_LINE)
    @GET("app/releases/latest")
    suspend fun latestRelease(@Query("versionCode") versionCode: Int): Response<ResponseBody>

    @Headers(NO_AUTH_HEADER_LINE)
    @GET("auth/session")
    suspend fun session(): Response<ResponseBody>

    @Headers(NO_AUTH_HEADER_LINE)
    @POST("members/verify")
    suspend fun memberVerify(@Body body: VerifyMemberRequest): Response<ResponseBody>
}
