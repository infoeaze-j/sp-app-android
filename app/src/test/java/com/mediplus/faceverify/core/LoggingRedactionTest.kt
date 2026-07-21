package com.mediplus.faceverify.core

import com.mediplus.faceverify.core.network.AuthInterceptor
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.Operator
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * T058 — no session token, member number, or biometric data appears in logged output (FR-029,
 * FR-030, SC-005). Mirrors the production logging policy: HEADERS level with Authorization redacted,
 * so request/response bodies (which may carry member numbers or images) are never logged, and the
 * bearer token is redacted even at the header level.
 */
class LoggingRedactionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val logBuffer = StringBuilder()

    private val token = "SECRET-TOKEN-abc123"
    private val memberNumber = "P9988776655"
    private val cardNumber = MemberNumber.parse("1234567")!!
    private val imageBase64 = "QUJDREVGRw==BIOMETRIC-IMAGE"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val sessionManager = InMemorySessionManager().apply {
            set(Session(token, Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active))
        }
        val logging = HttpLoggingInterceptor { line -> logBuffer.appendLine(line) }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
        }
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionManager))
            .addInterceptor(logging)
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `token, member number, and image never appear in logs`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"memberNumber":"$memberNumber"}"""))
        val body = """{"memberNumber":"$memberNumber","image":"$imageBase64"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/face/verify")).post(body).build()

        client.newCall(request).execute().close()

        val logs = logBuffer.toString()
        assertFalse("token leaked: $logs", logs.contains(token))
        assertFalse("memberNumber leaked: $logs", logs.contains(memberNumber))
        assertFalse("image leaked: $logs", logs.contains(imageBase64))
    }

    @Test
    fun `a card number posted to members verify never reaches the logs`() {
        // HEADERS level means bodies are never logged; this pins that guarantee against a level change.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"VALID"}"""))
        val body = """{"memberNumber":"${cardNumber.value}"}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/members/verify")).post(body).build()

        client.newCall(request).execute().close()

        assertFalse("card number leaked: $logBuffer", logBuffer.toString().contains(cardNumber.value))
    }

    @Test
    fun `the card number cannot leak through an accidental string interpolation`() {
        assertFalse("$cardNumber".contains(cardNumber.value))
        assertFalse(cardNumber.toString().contains(cardNumber.value))
    }
}
