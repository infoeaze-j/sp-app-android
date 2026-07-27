package com.mediplus.spapp.core.diagnostics

/**
 * Reads a [DeviceStateSnapshot] off the main thread. The single seam through which any
 * `android.os`/`android.net` framework access happens — no platform type reaches a use case or
 * ViewModel (mirrors [com.mediplus.spapp.core.nfc.MemberCardReader]).
 */
interface DeviceDiagnostics {
    suspend fun snapshot(): DeviceStateSnapshot
}
