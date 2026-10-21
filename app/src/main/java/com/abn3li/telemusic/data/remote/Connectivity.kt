package com.abn3li.telemusic.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What Android knows about reaching the internet right now. */
enum class Internet {
    /** A network that can't carry internet traffic at all. */
    NONE,
    /** No network right now (airplane mode, or between Wi-Fi and mobile data), or one Android
     * couldn't confirm reaches the internet. */
    UNVERIFIED,
    OK
}

fun Context.internet(): Internet {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return Internet.OK
    // No default network is also the moment of a hand-over (Wi-Fi to mobile data): don't call
    // that offline. Asking anyway costs little when it is - the lookup fails at once.
    val network = manager.activeNetwork ?: return Internet.UNVERIFIED
    val capabilities = manager.getNetworkCapabilities(network) ?: return Internet.UNVERIFIED
    return when {
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Internet.NONE
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Internet.OK
        else -> Internet.UNVERIFIED
    }
}
