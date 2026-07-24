package com.mediplus.faceverify.data.remote

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.UpdateRepositoryImpl
import com.mediplus.faceverify.domain.model.UpdateInfo
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
 * Version-check contract (self-update design): a published build maps to [UpdateInfo]; an
 * undeployed endpoint (404) is "nothing published", never an error; 5xx and timeout classify like
 * every other endpoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateApiContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepositoryImpl
    private lateinit var cacheDir: File
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

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
        repository = UpdateRepositoryImpl(api, UnconfinedTestDispatcher(), cacheDir)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a published build maps to UpdateInfo`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latestVersionCode":7,"latestVersionName":"1.6",""" +
                    """"apkUrl":"https://backoffice.example.com/app/faceverify-7.apk",""" +
                    """"sha256":"$SHA","sizeBytes":12345678,"minSupportedVersionCode":5}""",
            ),
        )

        val result = repository.fetchVersionInfo()

        assertEquals(
            AppResult.Success(
                UpdateInfo(
                    latestVersionCode = 7,
                    latestVersionName = "1.6",
                    apkUrl = "https://backoffice.example.com/app/faceverify-7.apk",
                    sha256 = SHA,
                    sizeBytes = 12_345_678,
                    minSupportedVersionCode = 5,
                ),
            ),
            result,
        )
    }

    @Test
    fun `unknown keys in the response are ignored`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"latestVersionCode":7,"releaseNotes":"surprise field","channel":"stable"}""",
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
        apkUrl = server.url("/app/faceverify-7.apk").toString(),
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
