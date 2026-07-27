package com.mediplus.spapp.core.nfc

import android.app.Activity

/**
 * The UI host a reader needs to listen for a tap. Wrapping the [Activity] keeps `android.nfc`
 * types out of the ViewModel and lets alternative readers (e.g. the debug fake) ignore it.
 */
@JvmInline
value class NfcHost(val activity: Activity)
