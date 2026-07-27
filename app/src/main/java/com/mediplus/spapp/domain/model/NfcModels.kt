package com.mediplus.spapp.domain.model

/** Whether the device can read an NFC card right now (FR-010). */
enum class NfcAvailability {
    /** NFC hardware present and enabled. */
    AVAILABLE,

    /** NFC hardware present but turned off in settings. */
    DISABLED,

    /** No NFC hardware on this device. */
    UNAVAILABLE,
}
