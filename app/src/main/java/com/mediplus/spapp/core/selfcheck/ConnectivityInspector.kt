package com.mediplus.spapp.core.selfcheck

import android.content.Context
import android.net.NetworkCapabilities
import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.core.diagnostics.activeNetworkCapabilities
import com.mediplus.spapp.core.diagnostics.isAirplaneModeOn
import com.mediplus.spapp.core.diagnostics.transportOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What the platform says about the device's network right now, for the self check's first two
 * rows.
 *
 * @param connected whether there is an active network at all. Kept apart from [transport] because
 *   a link of a kind [NetworkTransport] does not name (USB or Bluetooth tethering) is still a link.
 * @param isValidated Android's own verdict that the network reaches the internet
 *   (`NET_CAPABILITY_VALIDATED`), so the self check contacts no third-party host of its own.
 */
data class ConnectivityReading(
    val connected: Boolean,
    val transport: NetworkTransport,
    val isValidated: Boolean,
    val airplaneMode: Boolean,
)

/** Behind an interface so no `android.net` type reaches a use case or ViewModel. */
interface ConnectivityInspector {
    fun current(): ConnectivityReading
}

/** The real reader. Device-gated: exercised on hardware or an emulator, not in the JVM suite. */
class AndroidConnectivityInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityInspector {

    override fun current(): ConnectivityReading {
        val caps = context.activeNetworkCapabilities()
        return ConnectivityReading(
            connected = caps != null,
            transport = transportOf(caps),
            isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            airplaneMode = context.isAirplaneModeOn(),
        )
    }
}
