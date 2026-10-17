package com.abn3li.telemusic.data.browse

import org.json.JSONArray
import org.json.JSONObject

/** Only inspect the requested track, never another song in its radio queue. */
internal fun parseYouTubeSongLiked(response: JSONObject, videoId: String): Boolean? {
    fun nodes(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> {
                yield(value)
                for (key in value.keys()) yieldAll(nodes(value.opt(key)))
            }
            is JSONArray -> for (index in 0 until value.length()) yieldAll(nodes(value.opt(index)))
        }
    }
    val track = nodes(response).mapNotNull { it.optJSONObject("playlistPanelVideoRenderer") }
        .firstOrNull { it.optString("videoId") == videoId } ?: return null
    for (node in nodes(track)) {
        node.optJSONObject("likeButtonRenderer")?.optString("likeStatus")?.let {
            when (it) { "LIKE" -> return true; "INDIFFERENT", "DISLIKE" -> return false }
        }
        val toggle = node.optJSONObject("toggleMenuServiceItemRenderer") ?: continue
        val endpoint = toggle.optJSONObject("defaultServiceEndpoint")?.optJSONObject("likeEndpoint") ?: continue
        val target = endpoint.optJSONObject("target")?.optString("videoId")
        if (!target.isNullOrBlank() && target != videoId) continue
        // The default endpoint describes the available action, not the current state.
        when (endpoint.optString("status")) { "INDIFFERENT" -> return true; "LIKE" -> return false }
    }
    return null
}
