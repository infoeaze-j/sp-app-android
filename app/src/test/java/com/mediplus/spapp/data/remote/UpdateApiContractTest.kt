package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.network.AuthInterceptor
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.repository.UpdateRepositoryImpl
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Release-check contract (`app.releases.latest`): a published build maps to [UpdateInfo],
 * `{"latest": null}` is "nothing published" rather than an error, the running versionCode is sent
 * so the server can compute its own verdict, and 5xx/timeout classify like every other endpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateApiContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepositoryImpl
    private lateinit var cacheDir: File
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val currentVersion = CurrentAppVersion(code = 5, name = "1.5")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = tempFolder.newFolder("updates")
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            // Mirrors NetworkModule's real client, which NetworkModuleTest pins. The same-origin
            // check is over the URL the *response* named, so a followed redirect would move the
            // download to a host that check never saw.
            .followRedirects(false)
            .build()
        val api: UpdateApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
        repository = UpdateRepositoryImpl(api, UnconfinedTestDispatcher(), cacheDir, currentVersion)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a published build maps to UpdateInfo and reports the running version`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latest":{"versionCode":7,"versionName":"1.6","minSupportedVersionCode":5,""" +
                    """"url":"https://backoffice.example.com/api/v1/app/releases/7/binary","sha256":"$SHA",""" +
                    """"sizeBytes":12345678,"releaseNotes":"Fixes","publishedAt":"2026-07-20T09:00:00Z"},""" +
                    """"updateRequired":false,"updateAvailable":true}""",
            ),
        )

        val result = repository.fetchVersionInfo()

        assertEquals(
            AppResult.Success(
                UpdateInfo(
                    latestVersionCode = 7,
                    latestVersionName = "1.6",
                    apkUrl = "https://backoffice.example.com/api/v1/app/releases/7/binary",
                    sha256 = SHA,
                    sizeBytes = 12_345_678,
                    minSupportedVersionCode = 5,
                    updateRequired = false,
                    updateAvailable = true,
                    releaseNotes = "Fixes",
                ),
            ),
            result,
        )
        assertEquals("/app/releases/latest?versionCode=5", server.takeRequest().path)
    }

    @Test
    fun `nothing published is an empty answer, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"latest":null}"""))

        assertEquals(AppResult.Success(null), repository.fetchVersionInfo())
    }

    @Test
    fun `the server verdicts are read even when spelled as strings`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latest":{"versionCode":7,"versionName":"1.6","minSupportedVersionCode":1,""" +
                    """"url":"https://backoffice.example.com/api/v1/app/releases/7/binary","sha256":"$SHA",""" +
                    """"sizeBytes":1,"releaseNotes":null,"publishedAt":null},""" +
                    """"updateRequired":"true","updateAvailable":"true"}""",
            ),
        )

        val info = (repository.fetchVersionInfo() as AppResult.Success).data

        assertTrue(info!!.updateRequired)
        assertTrue(info.updateAvailable)
    }

    @Test
    fun `unknown keys in the response are ignored`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latest":{"versionCode":7,"channel":"stable"},"updateAvailable":true,"surprise":1}""",
            ),
        )

        val info = (repository.fetchVersionInfo() as AppResult.Success).data

        assertEquals(7, info?.latestVersionCode)
    }

    @Test
    fun `an undeployed endpoint means nothing published, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(AppResult.Success(null), repository.fetchVersionInfo())
    }

    @Test
    fun `server error maps to transient failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository.fetchVersionInfo()

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `an unexpected status stays retryable rather than failing hard`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.fetchVersionInfo()

        assertEquals(TransientKind.UNKNOWN, (result as AppResult.TransientFailure).error.kind)
    }

    @Test
    fun `no response within the timeout maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setBodyDelay(3, TimeUnit.SECONDS).setBody("{}"))

        assertEquals(AppResult.Timeout, repository.fetchVersionInfo())
    }

    // ---- Which half of the endpoint pair carries the token ----

    /**
     * The spec marks **both** halves of the pair `security: []` — the binary was opened up so a
     * client that has missed a required release can update before it is able to sign in. Both tests
     * run through a real [AuthInterceptor] with a live session, because a session in memory is the
     * only condition under which the interceptor would otherwise attach a token.
     */
    private fun authedApi(sessionManager: SessionManager): UpdateApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionManager)).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create()

    @Test
    fun `the release check goes out unauthenticated`() = runTest {
        val sessionManager = InMemorySessionManager()
        sessionManager.set(
            Session("tok-live", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"latest":null}"""))

        authedApi(sessionManager).latestRelease(versionCode = 5)

        val recorded = server.takeRequest()
        assertNull("the release check is `security: []`", recorded.getHeader("Authorization"))
        assertNull("the no-auth marker must not reach the wire", recorded.getHeader("X-No-Auth"))
    }

    @Test
    fun `the binary download goes out unauthenticated even with a live session`() = runTest {
        // A forced update runs before sign-in, so there is no token to present. Attaching one
        // anyway would also hand AuthInterceptor a 401 it would read as a session loss.
        val sessionManager = InMemorySessionManager()
        sessionManager.set(
            Session("tok-live", Operator("op-1", "Sam"), expiresAt = null, state = SessionState.Active),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(4))))

        authedApi(sessionManager).downloadApk(server.url("/app/releases/7/binary").toString())

        val recorded = server.takeRequest()
        assertNull("the binary is `security: []`", recorded.getHeader("Authorization"))
        assertNull("the no-auth marker must not reach the wire", recorded.getHeader("X-No-Auth"))
    }

    // ---- APK download + verification ----

    private val apkBytes = ByteArray(APK_SIZE) { (it % 251).toByte() }

    private fun infoFor(bytes: ByteArray, declaredSha: String = sha256Of(bytes)) = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = server.url("/app/releases/7/binary").toString(),
        sha256 = declaredSha,
        sizeBytes = bytes.size.toLong(),
        minSupportedVersionCode = 5,
    )

    private fun enqueueApk(bytes: ByteArray) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
    }

    private fun downloadedFile(): File = File(cacheDir, "update-v7.apk")

    @Test
    fun `matching bytes verify and land byte-identical on disk`() = runTest {
        enqueueApk(apkBytes)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { sofar, total ->
            progress.add(sofar to total)
        }

        val downloaded = (result as AppResult.Success).data
        assertEquals(7, downloaded.versionCode)
        assertArrayEquals(apkBytes, downloaded.file.readBytes())
        assertTrue("progress must be reported", progress.isNotEmpty())
        assertEquals("progress must end complete", apkBytes.size.toLong(), progress.last().first)
        assertTrue(
            "progress must be monotonic",
            progress.zipWithNext().all { (a, b) -> a.first <= b.first },
        )
        assertTrue("total must be the declared size", progress.all { it.second == apkBytes.size.toLong() })
    }

    @Test
    fun `an unexpected 401 on the unauthenticated binary stays retryable`() = runTest {
        // The endpoint is `security: []`, so a 401 here is a server bug rather than a session
        // problem. It must not crash and must not be read as anything but retryable.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthenticated."}"""))

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
        assertFalse(downloadedFile().exists())
    }

    @Test
    fun `tampered bytes are rejected as corrupted and deleted`() = runTest {
        val tampered = apkBytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        enqueueApk(tampered)

        val result = repository.downloadAndVerify(infoFor(tampered, declaredSha = sha256Of(apkBytes))) { _, _ -> }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse("corrupted file must not remain on disk", downloadedFile().exists())
    }

    @Test
    fun `a truncated body is rejected as corrupted and deleted`() = runTest {
        // Server closes the body cleanly but short of the declared size.
        enqueueApk(apkBytes.copyOf(apkBytes.size - 4096))

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(downloadedFile().exists())
    }

    @Test
    fun `an uppercase declared sha still verifies`() = runTest {
        enqueueApk(apkBytes)

        val result = repository.downloadAndVerify(
            infoFor(apkBytes, declaredSha = sha256Of(apkBytes).uppercase()),
        ) { _, _ -> }

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `a mid-stream disconnect is reported as interrupted and keeps the partial`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(apkBytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(
            TransientKind.DOWNLOAD_INTERRUPTED,
            (result as AppResult.TransientFailure).error.kind,
        )
        assertTrue("the partial is what the next attempt resumes from", downloadedFile().exists())
    }

    @Test
    fun `pruning keeps a partial for a build still on offer and drops the rest`() = runTest {
        // Running build is 5, so 4 and 5 can never be installed again; 6 may still be the offer.
        File(cacheDir, "update-v4.apk").writeBytes(ByteArray(10))
        File(cacheDir, "update-v5.apk").writeBytes(ByteArray(10))
        File(cacheDir, "update-v6.apk").writeBytes(ByteArray(10))
        File(cacheDir, "stray.tmp").writeBytes(ByteArray(10))

        repository.pruneObsoleteDownloads()

        assertEquals(
            listOf("update-v6.apk"),
            cacheDir.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    // ---- Resume ----

    /** Serves `[from, total)` as a well-formed 206, the way a range-capable server would. */
    private fun enqueuePartialFrom(bytes: ByteArray, from: Int) {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                .setBody(Buffer().write(bytes, from, bytes.size - from)),
        )
    }

    private fun writePartial(byteCount: Int) {
        downloadedFile().writeBytes(apkBytes.copyOf(byteCount))
    }

    @Test
    fun `a fresh download sends no range and pins identity encoding`() = runTest {
        enqueueApk(apkBytes)

        repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        val recorded = server.takeRequest()
        assertNull("nothing on disk to resume from", recorded.getHeader("Range"))
        assertEquals(
            "transparent gzip would desynchronise every resume offset",
            "identity",
            recorded.getHeader("Accept-Encoding"),
        )
    }

    @Test
    fun `a partial is resumed with a range request and verifies once appended`() = runTest {
        writePartial(PARTIAL_SIZE)
        enqueuePartialFrom(apkBytes, PARTIAL_SIZE)

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals("bytes=$PARTIAL_SIZE-", server.takeRequest().getHeader("Range"))
        assertTrue("expected Success, got $result", result is AppResult.Success)
        assertArrayEquals(apkBytes, downloadedFile().readBytes())
    }

    @Test
    fun `a resumed download reports progress from the offset, never from zero`() = runTest {
        writePartial(PARTIAL_SIZE)
        enqueuePartialFrom(apkBytes, PARTIAL_SIZE)
        val progress = mutableListOf<Pair<Long, Long>>()

        repository.downloadAndVerify(infoFor(apkBytes)) { sofar, total -> progress.add(sofar to total) }

        assertEquals("progress starts where the partial ended", PARTIAL_SIZE.toLong(), progress.first().first)
        assertEquals("progress must end complete", apkBytes.size.toLong(), progress.last().first)
        assertTrue("progress must be monotonic", progress.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `a server that ignores the range restarts cleanly from zero`() = runTest {
        // The shippable case: the spec declares only 200 for the binary, so this is what a back
        // office with no range support does, and it must still produce a correct install.
        writePartial(PARTIAL_SIZE)
        enqueueApk(apkBytes)

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals("bytes=$PARTIAL_SIZE-", server.takeRequest().getHeader("Range"))
        assertTrue("expected Success, got $result", result is AppResult.Success)
        assertArrayEquals("the stale prefix must be truncated, not prepended", apkBytes, downloadedFile().readBytes())
    }

    @Test
    fun `a range the server cannot satisfy is retried from zero without a second tap`() = runTest {
        writePartial(PARTIAL_SIZE)
        server.enqueue(MockResponse().setResponseCode(416))
        enqueueApk(apkBytes)

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals("bytes=$PARTIAL_SIZE-", server.takeRequest().getHeader("Range"))
        assertNull("the retry must ask for the whole file", server.takeRequest().getHeader("Range"))
        assertTrue("expected Success, got $result", result is AppResult.Success)
        assertArrayEquals(apkBytes, downloadedFile().readBytes())
    }

    @Test
    fun `a 206 starting at the wrong offset fails verification rather than corrupting the file`() = runTest {
        writePartial(PARTIAL_SIZE)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                // Claims an offset we did not ask for; the body cannot be appended to our prefix.
                .setHeader("Content-Range", "bytes 0-${apkBytes.size - 1}/${apkBytes.size}")
                .setBody(Buffer().write(apkBytes, PARTIAL_SIZE, apkBytes.size - PARTIAL_SIZE)),
        )

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse("a file that failed the digest must never survive", downloadedFile().exists())
    }

    @Test
    fun `a partial at or beyond the declared size is discarded instead of resumed`() = runTest {
        downloadedFile().writeBytes(ByteArray(apkBytes.size + 1))
        enqueueApk(apkBytes)

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertNull("an oversized prefix is not a prefix", server.takeRequest().getHeader("Range"))
        assertTrue("expected Success, got $result", result is AppResult.Success)
    }

    @Test
    fun `a prefix belonging to different bytes cannot survive verification`() = runTest {
        // The digest is the backstop that makes opportunistic resume safe at all.
        downloadedFile().writeBytes(ByteArray(PARTIAL_SIZE) { 0 })
        enqueuePartialFrom(apkBytes, PARTIAL_SIZE)

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse("deleting here is what stops the same failure repeating", downloadedFile().exists())
    }

    @Test
    fun `a partial for a superseded build is discarded when a new one starts`() = runTest {
        File(cacheDir, "update-v6.apk").writeBytes(ByteArray(10))
        enqueueApk(apkBytes)

        repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertFalse("only the build being fetched keeps its partial", File(cacheDir, "update-v6.apk").exists())
    }

    @Test
    fun `a missing APK on the server stays transient`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    // ---- Bounds: what a single response is allowed to make the device do ----

    @Test
    fun `a body running past the declared size is rejected and leaves nothing to resume`() = runTest {
        // The failure mode this closes is not a bad install — the digest already stops that — it is
        // an unbounded write. Deleting matters as much as stopping: a partial that survives is
        // resumed and grown again every six hours, forever.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(APK_SIZE * 2))))

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse("an overlong transfer must not leave a partial behind", downloadedFile().exists())
    }

    @Test
    fun `a redirected binary is refused rather than fetched from wherever it points`() = runTest {
        // CheckForUpdateUseCase validated the URL the response named. A 30x lets that same response
        // choose a different host afterwards, which is the one judgement the client never makes.
        val elsewhere = MockWebServer()
        elsewhere.start()
        try {
            elsewhere.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", elsewhere.url("/anyones.apk").toString()),
            )

            val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

            assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
            assertEquals("the redirect target must never be contacted", 0, elsewhere.requestCount)
            assertFalse(downloadedFile().exists())
        } finally {
            elsewhere.shutdown()
        }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHA = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6"
        const val APK_SIZE = 300_000
        /** Deliberately not a multiple of the 64 KB copy buffer, so resume cannot land on a seam. */
        const val PARTIAL_SIZE = 100_001
    }
}
