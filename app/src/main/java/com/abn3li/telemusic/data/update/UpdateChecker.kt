package com.abn3li.telemusic.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class UpdateAvailable(val version: String, val releaseUrl: String, val notes: String?) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/** Checks GitHub Releases for a newer TeleMusic build than the one installed. The app has no
 * auto-updater of its own (it's sideloaded, not on a store) - this only ever tells the user a
 * newer release exists and hands them the release page to grab it from themselves, same as
 * checking by hand. Release tags on this repo are named "Stable<version>" (e.g. "Stable1.3"),
 * not a bare version string, so the version number is pulled out of the tag rather than assumed
 * to be the whole thing. */
class UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/abn3li/TeleMusic/releases/latest")
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use UpdateCheckResult.Error("GitHub returned HTTP ${response.code}")
                }
                val body = JSONObject(response.body?.string().orEmpty())
                val latestVersion = VERSION_REGEX.find(body.optString("tag_name"))?.value
                if (latestVersion == null) {
                    UpdateCheckResult.Error("Couldn't read the release version")
                } else if (isNewer(latestVersion, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(
                        version = latestVersion,
                        releaseUrl = body.optString("html_url").ifBlank { "https://github.com/abn3li/TeleMusic/releases" },
                        notes = body.optString("body").takeIf { it.isNotBlank() }
                    )
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        }.getOrElse { e -> UpdateCheckResult.Error(e.message ?: "Couldn't check for updates") }
    }

    /** Plain component-wise comparison ("1.10" > "1.9") - a simple > on the raw strings would get
     * that backwards the moment either side reaches a double-digit component. */
    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private companion object {
        val VERSION_REGEX = Regex("""\d+(\.\d+)*""")
    }
}
