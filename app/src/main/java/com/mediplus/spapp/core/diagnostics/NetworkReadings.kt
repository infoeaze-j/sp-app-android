package com.mediplus.spapp.core.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/*
 * Platform network reads shared by the diagnostics snapshot and the operator self check, so both
 * describe the same network the same way.
 */

internal fun Context.activeNetworkCapabilities(): NetworkCapabilities? {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return cm.getNetworkCapabilities(cm.activeNetwork)
}

internal fun Context.isAirplaneModeOn(): Boolean =
    Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

internal fun transportOf(caps: NetworkCapabilities?): NetworkTransport = when {
    caps == null -> NetworkTransport.NONE
    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
    else -> NetworkTransport.NONE
}
