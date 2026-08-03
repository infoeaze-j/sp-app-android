package com.mediplus.spapp.data.repository

import com.mediplus.spapp.domain.model.UpdateInfo
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
    fun `a partial file is not verified and is never digested as if complete`() {
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
}
