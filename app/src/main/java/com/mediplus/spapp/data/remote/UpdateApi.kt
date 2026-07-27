package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Self-update: the published-version check and the APK download
 * (docs/superpowers/specs/2026-07-24-self-update-design.md).
 *
 * Placeholder contract invented app-side — reconcile with the back office once it publishes its
 * shape. The check is deliberately unauthenticated: it runs at launch, before sign-in.
 */
interface UpdateApi {

    @GET("app/version")
    suspend fun checkVersion(): Response<AppVersionResponse>

    /** Streams the APK bytes without buffering; the caller digests and writes them chunk by chunk. */
    @Streaming
    @GET
    suspend fun downloadApk(@Url url: String): Response<ResponseBody>
}

@Serializable
data class AppVersionResponse(
    val latestVersionCode: Int,
    val latestVersionName: String = "",
    val apkUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val minSupportedVersionCode: Int = 0,
)
