package com.mediplus.spapp.core.di

import com.mediplus.spapp.BuildConfig
import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.network.DeviceIdInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Retrofit + OkHttp + kotlinx.serialization stack. The [AuthInterceptor] attaches the session token
 * and treats 401 as invalidation (FR-002, FR-004). The logging interceptor is debug-only and capped
 * at HEADERS with the Authorization header redacted, so no token, identity, or biometric body is
 * ever written to logs (FR-029). Redirects are refused client-wide — see [provideOkHttpClient].
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @BaseUrl
    fun provideBaseUrl(): String = BuildConfig.BASE_URL

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // HEADERS only (never BODY): request/response bodies may carry identity or biometric
            // data. Redact the bearer token even at HEADERS level.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceIdInterceptor: DeviceIdInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(deviceIdInterceptor)
        .addInterceptor(loggingInterceptor)
        // Every request this app makes is aimed at an origin the CLIENT chose: BuildConfig.BASE_URL,
        // or an apkUrl CheckForUpdateUseCase has already checked against it. A 30x hands that choice
        // back to the response, after the check — which is the single judgement the same-origin rule
        // exists to keep away from the server. docs/openapi.json declares no 3xx on any endpoint, so
        // a redirect is always off-contract; letting it through as a plain non-2xx keeps it visible
        // and retryable instead of silently obeyed. Client-wide rather than download-only because
        // OkHttp strips only Authorization across a host change: X-Device-Id would otherwise ride
        // along to whoever the redirect named, and a same-host redirect keeps the bearer token too.
        .followRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L
}
