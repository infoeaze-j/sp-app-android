package com.mediplus.spapp.data.remote

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.UpdateRepositoryImpl
import com.mediplus.spapp.domain.model.CurrentAppVersion
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
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
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
    fun `the binary download is authenticated, so an unauthorised one stays retryable`() = runTest {
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
    fun `a mid-stream disconnect stays transient and deletes the partial file`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(apkBytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertTrue("expected TransientFailure, got $result", result is AppResult.TransientFailure)
        assertFalse("partial file must not remain on disk", downloadedFile().exists())
    }

    @Test
    fun `clearing downloads removes leftovers from earlier runs`() = runTest {
        File(cacheDir, "update-v6.apk").writeBytes(ByteArray(10))
        File(cacheDir, "update-v7.apk").writeBytes(ByteArray(10))

        repository.clearDownloads()

        assertEquals(emptyList<File>(), cacheDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `a missing APK on the server stays transient`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.downloadAndVerify(infoFor(apkBytes)) { _, _ -> }

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHA = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6"
        const val APK_SIZE = 300_000
    }
}
