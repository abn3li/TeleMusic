package com.abn3li.telemusic.data.download

import java.util.concurrent.ConcurrentHashMap

/** A resolved URL belongs to its requested quality and expires by elapsed time. */
internal class StreamUrlCache(private val now: () -> Long) {
    private data class Key(val videoId: String, val format: String)
    private data class Entry(val stream: YtDlpStreamResult, val loadedAt: Long)
    private val entries = ConcurrentHashMap<Key, Entry>()

    fun get(videoId: String, format: String): YtDlpStreamResult? {
        val key = Key(videoId, format)
        val entry = entries[key] ?: return null
        if (now() - entry.loadedAt >= MAX_AGE_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.stream
    }

    fun put(videoId: String, format: String, stream: YtDlpStreamResult) {
        entries[Key(videoId, format)] = Entry(stream, now())
    }

    fun invalidate(videoId: String) { entries.keys.removeAll { it.videoId == videoId } }
    fun clear() { entries.clear() }

    companion object { const val MAX_AGE_MS = 3 * 60 * 60 * 1000L }
}
