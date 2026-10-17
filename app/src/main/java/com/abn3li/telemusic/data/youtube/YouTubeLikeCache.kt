package com.abn3li.telemusic.data.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep last-known UI state while refreshing; serialize writes and roll back failed taps. */
internal class YouTubeLikeCache(private val clock: () -> Long, private val ttlMillis: Long = 120_000L) {
    private data class Entry(val liked: Boolean, val time: Long)
    internal data class Change(val session: String, val videoId: String, val liked: Boolean, val token: Long)
    private val entries = LinkedHashMap<Pair<String, String>, Entry>()
    private val known = LinkedHashMap<Pair<String, String>, Boolean>()
    private val pending = mutableMapOf<Pair<String, String>, Long>()
    private val mutableStates = MutableStateFlow<Map<Pair<String, String>, Boolean>>(emptyMap())
    val states = mutableStates.asStateFlow()
    private var revision = 0L
    private val requests = Mutex()

    @Synchronized fun peek(session: String, videoId: String): Boolean? {
        val key = session to videoId
        if (key in pending) return known[key]
        val entry = entries[key] ?: return null
        return entry.liked.takeIf { clock() - entry.time < ttlMillis }
    }

    private fun publish() {
        while (known.size > 256) {
            val key = known.keys.firstOrNull { it !in pending } ?: break
            known.remove(key); entries.remove(key)
        }
        mutableStates.value = known.toMap()
    }

    @Synchronized fun seedLikes(session: String, videoIds: List<String>) {
        for (id in videoIds) {
            val key = session to id
            if (key !in known) { entries[key] = Entry(true, clock()); known[key] = true }
        }
        revision++
        publish()
    }

    suspend fun get(session: String, videoId: String, fetch: suspend () -> Boolean): Boolean {
        peek(session, videoId)?.let { return it }
        return requests.withLock {
            peek(session, videoId)?.let { return@withLock it }
            val before = synchronized(this) { revision }
            val fetched = fetch()
            synchronized(this) {
                val key = session to videoId
                if (before == revision) {
                    entries[key] = Entry(fetched, clock()); known[key] = fetched; publish()
                }
                known[key] ?: fetched
            }
        }
    }

    @Synchronized fun beginUpdate(session: String, videoId: String, liked: Boolean): Change {
        val token = ++revision
        val key = session to videoId
        pending[key] = token; known[key] = liked; publish()
        return Change(session, videoId, liked, token)
    }

    suspend fun finishUpdate(change: Change, mutate: suspend () -> Unit) {
        requests.withLock {
            val key = change.session to change.videoId
            try {
                mutate()
                synchronized(this) {
                    entries[key] = Entry(change.liked, clock())
                    if (pending[key] == change.token) {
                        pending.remove(key); known[key] = change.liked; publish()
                    }
                }
            } catch (failure: Throwable) {
                synchronized(this) {
                    if (pending[key] == change.token) {
                        pending.remove(key)
                        entries[key]?.let { known[key] = it.liked } ?: known.remove(key)
                        publish()
                    }
                }
                throw failure
            }
        }
    }

    suspend fun update(session: String, videoId: String, liked: Boolean, mutate: suspend () -> Unit) =
        finishUpdate(beginUpdate(session, videoId, liked), mutate)
}
