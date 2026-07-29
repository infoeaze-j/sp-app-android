package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.network.NO_AUTH_HEADER_LINE
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Self-update — `app.releases.latest` and `app.releases.binary` in docs/openapi.json
 * (design: docs/superpowers/specs/2026-07-24-self-update-design.md).
 *
 * **Both halves of the pair are `security: []`.** The check is unauthenticated because it runs at
 * launch, before sign-in, and always answers 200: "nothing published yet" is a fact about the fleet,
 * not a failure, and answering it with 404 made that state indistinguishable from a routing mistake.
 * The binary is unauthenticated for the reason the spec now gives — a client that has missed a
 * *required* release has to be able to update before it can sign in, and there is no token to
 * present at that point. Its URL must still stay same-origin with the API, so the client never has
 * to trust a host this response could have named freely.
 */
interface UpdateApi {

    /** @param versionCode the running build, so the server can compute `updateRequired` itself. */
    @Headers(NO_AUTH_HEADER_LINE)
    @GET("app/releases/latest")
    suspend fun latestRelease(@Query("versionCode") versionCode: Int): Response<LatestReleaseResponse>

    /**
     * Streams the APK bytes without buffering; the caller digests and writes them chunk by chunk.
     *
     * @param range `bytes=N-` to resume an interrupted download, or null for a fresh one — Retrofit
     *   omits a null header, so a first attempt sends no `Range` at all. The server is free to
     *   ignore it and answer 200; the caller handles that by restarting from zero.
     * @param acceptEncoding pinned to `identity` deliberately. Left unset, OkHttp's BridgeInterceptor
     *   adds `Accept-Encoding: gzip`, transparently decompresses, and drops `Content-Length` — which
     *   would silently desynchronise every resume offset.
     */
    @Streaming
    @Headers(NO_AUTH_HEADER_LINE)
    @GET
    suspend fun downloadApk(
        @Url url: String,
        @Header("Range") range: String? = null,
        @Header("Accept-Encoding") acceptEncoding: String = "identity",
    ): Response<ResponseBody>
}

@Serializable
data class LatestReleaseResponse(
    /** Null means nothing is published — the normal answer for a fleet with no release yet. */
    val latest: ReleaseDto? = null,
    /**
     * Computed by the server so the "must update" rule lives in one place. Spelled `string` in the
     * spec beside an object-typed binary download, so read leniently and defaulting to false —
     * the fail-open answer, which never strands a clinic on a bad payload.
     */
    @Serializable(with = LenientBooleanSerializer::class)
    val updateRequired: Boolean = false,
    @Serializable(with = LenientBooleanSerializer::class)
    val updateAvailable: Boolean = false,
)

@Serializable
data class ReleaseDto(
    val versionCode: Int,
    val versionName: String = "",
    /**
     * Dropped from the published contract — the server computes `updateRequired` itself now. Kept,
     * and defaulted to 0, purely so the client's own floor comparison degrades to inert rather than
     * to a parse failure; see CheckForUpdateUseCase.
     */
    val minSupportedVersionCode: Int = 0,
    /** Same origin as the API by construction, so the client never has to trust a host named here. */
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
)
