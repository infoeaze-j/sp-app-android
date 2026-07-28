package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Self-update — `app.releases.latest` and `app.releases.binary` in docs/openapi.json
 * (design: docs/superpowers/specs/2026-07-24-self-update-design.md).
 *
 * The check is unauthenticated because it runs at launch, before sign-in, and always answers 200:
 * "nothing published yet" is a fact about the fleet, not a failure, and answering it with 404 made
 * that state indistinguishable from a routing mistake. The binary, by contrast, is authenticated so
 * published builds are not publicly harvestable — which is also why its URL must stay same-origin
 * with the API, since the bearer token rides along.
 */
interface UpdateApi {

    /** @param versionCode the running build, so the server can compute `updateRequired` itself. */
    @GET("app/releases/latest")
    suspend fun latestRelease(@Query("versionCode") versionCode: Int): Response<LatestReleaseResponse>

    /** Streams the APK bytes without buffering; the caller digests and writes them chunk by chunk. */
    @Streaming
    @GET
    suspend fun downloadApk(@Url url: String): Response<ResponseBody>
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
    val minSupportedVersionCode: Int = 0,
    /** Same origin as the API by construction; the client attaches its bearer token to it. */
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val releaseNotes: String? = null,
    val publishedAt: String? = null,
)
