package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.domain.model.DownloadedApk
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * The byte-moving half of the self-update download, kept apart from [UpdateRepositoryImpl] so that
 * class is left deciding what a response *means* rather than how to stream it. Nothing here talks
 * to the network or to Hilt; it is all file and header arithmetic — whether the bytes can be
 * trusted once they arrive is [ApkVerification]'s question, not this file's.
 *
 * The contract that makes resuming safe lives in `verified` (in [ApkVerification]): a finished file
 * is accepted only when its SHA-256 and byte count both match what the back office published. Every
 * optimistic decision below — reusing a prefix, trusting a 206 — can therefore only ever cost a
 * wasted transfer, never an install of the wrong bytes.
 */

internal const val HTTP_RANGE_NOT_SATISFIABLE = 416

private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024
private const val PERCENT = 100
private const val HEADER_CONTENT_RANGE = "Content-Range"
private val CONTENT_RANGE_START = Regex("""bytes\s+(\d+)-""")
private val DOWNLOAD_NAME = Regex("""update-v(\d+)\.apk""")

/** Where a single transfer starts and how it reports itself, so the stream helpers stay small. */
internal class TransferPlan(
    val totalBytes: Long,
    val startAt: Long,
    val onProgress: suspend (Long, Long) -> Unit,
)

/** The raw outcome of streaming a body to disk, before verification. */
internal data class StreamedApk(val bytes: Long, val shaHex: String)

/** The build a cached download belongs to, or null for a name we did not write. */
internal fun versionCodeOf(fileName: String): Int? =
    DOWNLOAD_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Whether [response] is really serving the range we asked for. A 200 here means the server ignored
 * `Range` and is sending the whole file — common, and the reason resume has to stay opportunistic.
 */
internal fun servesRangeFrom(response: Response<ResponseBody>, offset: Long): Boolean {
    if (offset <= 0 || response.code() != HttpURLConnection.HTTP_PARTIAL) return false
    // A 206 without Content-Range is taken at its word; the digest is the real check.
    val contentRange = response.headers()[HEADER_CONTENT_RANGE] ?: return true
    return CONTENT_RANGE_START.find(contentRange)?.groupValues?.get(1)?.toLongOrNull() == offset
}

/**
 * How much of [target] is worth resuming from. A file at or past the declared size is not a prefix
 * of anything we still want, so it is discarded rather than range-requested — which also pre-empts
 * most of the 416s the server would otherwise have to tell us about.
 */
internal fun resumableBytes(target: File, declaredSize: Long): Long {
    val onDisk = target.length()
    if (onDisk <= 0 || onDisk >= declaredSize) {
        target.delete()
        return 0L
    }
    return onDisk
}

internal suspend fun streamTo(target: File, body: ResponseBody, plan: TransferPlan): StreamedApk {
    target.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    val resuming = plan.startAt > 0
    // Re-reading the prefix off local disk costs a fraction of re-fetching it over the network.
    if (resuming) digest.updateWithPrefixOf(target, plan.startAt)
    plan.onProgress(plan.startAt, plan.totalBytes)
    val written = body.byteStream().use { input ->
        // append=false truncates, which is what discards a prefix the server would not resume.
        FileOutputStream(target, resuming).use { output ->
            copyChunks(input, output, digest, plan)
        }
    }
    plan.onProgress(written, plan.totalBytes)
    return StreamedApk(bytes = written, shaHex = digest.digest().toHex())
}

private suspend fun copyChunks(
    input: InputStream,
    output: OutputStream,
    digest: MessageDigest,
    plan: TransferPlan,
): Long {
    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
    var written = plan.startAt
    var lastPercent = percentOf(written, plan.totalBytes)
    while (true) {
        val read = input.read(buffer)
        if (read == -1) return written
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        written += read
        val percent = percentOf(written, plan.totalBytes)
        if (percent != lastPercent) {
            lastPercent = percent
            plan.onProgress(written, plan.totalBytes)
        }
    }
}

internal fun MessageDigest.updateWithPrefixOf(file: File, byteCount: Long) {
    val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
    var remaining = byteCount
    file.inputStream().use { input ->
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read == -1) break
            update(buffer, 0, read)
            remaining -= read
        }
    }
}

/** A dropped transfer. Note what is absent: the partial stays on disk for the next attempt. */
internal fun interrupted(cause: IOException): AppResult<DownloadedApk> =
    AppResult.TransientFailure(AppError.Transient(TransientKind.DOWNLOAD_INTERRUPTED, cause))

private fun percentOf(written: Long, total: Long): Int =
    if (total > 0) ((written * PERCENT) / total).toInt() else 0

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
