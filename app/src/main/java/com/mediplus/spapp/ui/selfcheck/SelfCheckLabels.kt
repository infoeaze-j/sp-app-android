package com.mediplus.spapp.ui.selfcheck

import androidx.annotation.StringRes
import com.mediplus.spapp.R
import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckWarning
import com.mediplus.spapp.domain.model.SelfCheckItem

/* The self check's vocabulary, one string resource per value — nothing on the screen is free text. */

@StringRes
internal fun SelfCheckItem.labelRes(): Int = when (this) {
    SelfCheckItem.LOCAL_NETWORK -> R.string.selfcheck_item_local_network
    SelfCheckItem.INTERNET -> R.string.selfcheck_item_internet
    SelfCheckItem.API -> R.string.selfcheck_item_api
    SelfCheckItem.ENDPOINT_LOGIN -> R.string.selfcheck_item_login
    SelfCheckItem.ENDPOINT_SESSION -> R.string.selfcheck_item_session
    SelfCheckItem.ENDPOINT_MEMBER_VERIFY -> R.string.selfcheck_item_member_verify
    SelfCheckItem.PERM_CAMERA -> R.string.selfcheck_item_camera
    SelfCheckItem.PERM_NOTIFICATIONS -> R.string.selfcheck_item_notifications
    SelfCheckItem.PERM_INSTALL_APPS -> R.string.selfcheck_item_install_apps
    SelfCheckItem.NFC -> R.string.selfcheck_item_nfc
}

@StringRes
internal fun CheckFailure.labelRes(): Int = when (this) {
    CheckFailure.NO_NETWORK -> R.string.selfcheck_failure_no_network
    CheckFailure.AIRPLANE_MODE -> R.string.selfcheck_failure_airplane_mode
    CheckFailure.NOT_VALIDATED -> R.string.selfcheck_failure_not_validated
    CheckFailure.UNREACHABLE -> R.string.selfcheck_failure_unreachable
    CheckFailure.TIMEOUT -> R.string.selfcheck_failure_timeout
    CheckFailure.SERVER_ERROR -> R.string.selfcheck_failure_server_error
    CheckFailure.UNEXPECTED_STATUS -> R.string.selfcheck_failure_unexpected_status
    CheckFailure.DENIED -> R.string.selfcheck_failure_denied
    CheckFailure.DISABLED -> R.string.selfcheck_failure_disabled
    CheckFailure.UNSUPPORTED -> R.string.selfcheck_failure_unsupported
}

@StringRes
internal fun CheckWarning.labelRes(): Int = when (this) {
    CheckWarning.MOBILE_DATA -> R.string.selfcheck_warning_mobile_data
    CheckWarning.VPN -> R.string.selfcheck_warning_vpn
}

@StringRes
internal fun NetworkTransport.labelRes(): Int = when (this) {
    NetworkTransport.WIFI -> R.string.selfcheck_transport_wifi
    NetworkTransport.CELLULAR -> R.string.selfcheck_transport_cellular
    NetworkTransport.ETHERNET -> R.string.selfcheck_transport_ethernet
    NetworkTransport.VPN -> R.string.selfcheck_transport_vpn
    // Never shown: the use case reports a link of no named kind as no `via` at all.
    NetworkTransport.NONE -> R.string.selfcheck_status_passed
}
