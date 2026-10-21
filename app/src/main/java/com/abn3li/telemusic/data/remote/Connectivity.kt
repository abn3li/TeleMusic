package com.abn3li.telemusic.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What Android knows about reaching the internet right now. */
enum class Internet {
    /** No network, or one that can't carry internet traffic (airplane mode, Wi-Fi off). */
    NONE,
    /** Connected, but Android couldn't confirm the internet is reachable through it. */
    UNVERIFIED,
    OK
}

fun Context.internet(): Internet {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return Internet.OK
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return Internet.NONE
    return when {
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Internet.NONE
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Internet.OK
        else -> Internet.UNVERIFIED
    }
}
