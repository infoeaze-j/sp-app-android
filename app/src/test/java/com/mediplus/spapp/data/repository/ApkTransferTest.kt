package com.mediplus.spapp.data.repository

import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The file-and-digest arithmetic behind the resumable download. [alreadyVerified] is what stops a
 * finished-but-uninstalled APK being re-fetched every time the headless flow wakes up — the state
 * the unattended design parks in whenever the platform is waiting on a confirmation tap.
 *
 * [streamTo] is pinned here for the one property the digest cannot supply: the transfer must stop
 * writing at the size the server declared. The digest decides whether bytes are *trustworthy*, but
 * it only ever runs on a body that ended — so a body that never ends is bounded by nothing else.
 */
class ApkTransferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun info(sizeBytes: Long, sha256: String) = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = "https://backoffice.example.com/api/v1/app/releases/7/binary",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        minSupportedVersionCode = 1,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileOf(name: String, bytes: ByteArray): File =
        tempFolder.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `a finished file whose size and digest both match is already verified`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes)

        assertTrue(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes))))
    }

    @Test
    fun `an uppercase published digest still matches`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes)

        assertTrue(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes).uppercase())))
    }

    @Test
    fun `a file of the right size but the wrong bytes is not verified`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", ByteArray(64) { 0 })

        assertFalse(alreadyVerified(target, info(64, sha256Hex(bytes))))
    }

    @Test
    fun `a partial file is not verified`() {
        val bytes = ByteArray(64) { it.toByte() }
        val target = fileOf("update-v7.apk", bytes.copyOf(30))

        assertFalse(alreadyVerified(target, info(bytes.size.toLong(), sha256Hex(bytes))))
    }

    @Test
    fun `a missing file is not verified`() {
        val bytes = ByteArray(64) { it.toByte() }

        assertFalse(
            alreadyVerified(File(tempFolder.root, "absent.apk"), info(64, sha256Hex(bytes))),
        )
    }

    // ---- The declared size is a hard ceiling on what may be written ----

    private fun planFor(totalBytes: Long, startAt: Long = 0L) =
        TransferPlan(totalBytes = totalBytes, startAt = startAt) { _, _ -> }

    @Test
    fun `a body of exactly the declared size is not mistaken for an overshoot`() = runTest {
        val bytes = ByteArray(DECLARED.toInt()) { it.toByte() }
        val target = File(tempFolder.root, "update-v7.apk")

        val streamed = streamTo(target, bytes.toResponseBody(), planFor(DECLARED))

        assertEquals(DECLARED, streamed.bytes)
        assertEquals(sha256Hex(bytes), streamed.shaHex)
        assertEquals(DECLARED, target.length())
    }

    @Test
    fun `a body that runs past the declared size stops instead of filling the disk`() = runTest {
        // A server that keeps sending — hostile, or merely wrong about sizeBytes. Without a ceiling
        // this loop only ends when the volume does, and the partial is then resumed and grown again
        // on every later attempt, which no field device ever recovers from.
        val target = File(tempFolder.root, "update-v7.apk")

        val streamed = streamTo(target, ByteArray(OVERLONG).toResponseBody(), planFor(DECLARED))

        assertTrue("the overshoot must be detected: ${streamed.bytes}", streamed.bytes > DECLARED)
        assertTrue("nothing past the declaration may be written", target.length() <= DECLARED)
    }

    @Test
    fun `a resumed transfer counts its prefix against the declared size`() = runTest {
        // The ceiling is on the whole file, not on one response, or a resume would reset the budget.
        val target = fileOf("update-v7.apk", ByteArray(RESUME_AT.toInt()))

        val streamed = streamTo(
            target,
            ByteArray(OVERLONG).toResponseBody(),
            planFor(DECLARED, startAt = RESUME_AT),
        )

        assertTrue("the overshoot must be detected: ${streamed.bytes}", streamed.bytes > DECLARED)
        assertTrue("nothing past the declaration may be written", target.length() <= DECLARED)
    }

    private companion object {
        const val DECLARED = 4_096L
        const val RESUME_AT = 4_000L
        const val OVERLONG = 1_000_000
    }
}
