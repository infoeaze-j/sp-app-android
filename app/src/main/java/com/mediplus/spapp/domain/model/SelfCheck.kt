package com.mediplus.spapp.domain.model

import com.mediplus.spapp.core.diagnostics.NetworkTransport

/**
 * One row of the operator's self check (troubleshoot) screen, reachable from sign-in. The self
 * check reads the real device and the real network — never the debug fake stack, since a check
 * that reports the fakes would say nothing about the device in the operator's hand.
 */
enum class SelfCheckItem {
    LOCAL_NETWORK,
    INTERNET,
    API,

    /**
     * Shown but never probed: the back office locks an account after repeated failed sign-ins, and
     * whether it counts them per identifier or per device is unknown. A placeholder until the
     * back-office troubleshooting mode gives the self check something safe to call.
     */
    ENDPOINT_LOGIN,
    ENDPOINT_SESSION,
    ENDPOINT_MEMBER_VERIFY,
    PERM_CAMERA,
    PERM_NOTIFICATIONS,
    PERM_INSTALL_APPS,
    NFC,
}

/** The network-and-server rows, in the order they run and are shown. */
val NETWORK_CHECKS: List<SelfCheckItem> = listOf(
    SelfCheckItem.LOCAL_NETWORK,
    SelfCheckItem.INTERNET,
    SelfCheckItem.API,
    SelfCheckItem.ENDPOINT_LOGIN,
    SelfCheckItem.ENDPOINT_SESSION,
    SelfCheckItem.ENDPOINT_MEMBER_VERIFY,
)

/** The device-permission rows, in display order. */
val PERMISSION_CHECKS: List<SelfCheckItem> = listOf(
    SelfCheckItem.PERM_CAMERA,
    SelfCheckItem.PERM_NOTIFICATIONS,
    SelfCheckItem.PERM_INSTALL_APPS,
    SelfCheckItem.NFC,
)

/** Where one self-check row stands. Every state is explicit so none can be mistaken for a pass. */
sealed interface CheckStatus {
    data object Pending : CheckStatus
    data object Running : CheckStatus

    /**
     * @param httpStatus the status a probed endpoint answered with — a number, never a server
     *   reason, so it is safe to show.
     * @param via the kind of network the device is on, for the local-network row.
     */
    data class Passed(val httpStatus: Int? = null, val via: NetworkTransport? = null) : CheckStatus
    data class Failed(val reason: CheckFailure, val httpStatus: Int? = null) : CheckStatus

    /** Working, but in a way the operator should fix when they can — so neither a pass nor a failure. */
    data class Warning(val reason: CheckWarning) : CheckStatus

    /** Not run because an earlier check already failed in a way that makes this one pointless. */
    data object Skipped : CheckStatus

    /** Deliberately not run yet — see [SelfCheckItem.ENDPOINT_LOGIN]. */
    data object NotYetAvailable : CheckStatus

    /** Does not exist on this device, e.g. the notifications permission below API 33. */
    data object NotApplicable : CheckStatus
}

enum class CheckFailure {
    NO_NETWORK,
    AIRPLANE_MODE,
    NOT_VALIDATED,
    UNREACHABLE,
    TIMEOUT,
    SERVER_ERROR,
    UNEXPECTED_STATUS,
    DENIED,
    DISABLED,
    UNSUPPORTED,
}

enum class CheckWarning {
    /** Connected over the SIM rather than Wi-Fi, which is the preferred link in a clinic. */
    MOBILE_DATA,

    /**
     * Connected through a VPN, treated like mobile data: the platform reports the VPN rather than
     * the link beneath it, and a VPN is itself a slower path to the back office.
     */
    VPN,
}

/** A back-office endpoint the self check calls to see whether it answers. */
enum class ProbeEndpoint {
    LATEST_RELEASE,
    SESSION,
    MEMBER_VERIFY,
}

/** What happened when an endpoint was probed. Any HTTP status at all means the server answered. */
sealed interface ProbeOutcome {
    data class Responded(val code: Int) : ProbeOutcome
    data object Unreachable : ProbeOutcome
    data object TimedOut : ProbeOutcome
}
