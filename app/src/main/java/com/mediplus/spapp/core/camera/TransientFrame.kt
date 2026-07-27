package com.mediplus.spapp.core.camera

import java.util.Base64

/**
 * A captured live face frame held **in memory only** for a single verification submission (FR-017).
 * It is never written to disk, DataStore, logs, or backups. The caller MUST [clear] it after the
 * decision returns or on abort; [clear] both drops the reference and zeroes the bytes so the image
 * cannot linger in memory.
 */
class TransientFrame(bytes: ByteArray) {

    private var data: ByteArray? = bytes

    val isCleared: Boolean
        get() = data == null

    /** Base64 for the single submission, or null once cleared. */
    fun asBase64(): String? = data?.let { Base64.getEncoder().encodeToString(it) }

    /** Zero the bytes and drop the reference. Idempotent. */
    fun clear() {
        data?.fill(0)
        data = null
    }
}
