package com.abn3li.telemusic.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the last successful feed visible while refreshing, with no background timer. */
internal class HomeFeedCache<T>(private val now: () -> Long) {
    private val loadMutex = Mutex()
    private var generation = 0L
    private var cached: T? = null
    private var loadedAt: Long? = null

    val value: T? get() = synchronized(this) { cached }

    fun isStale(): Boolean = synchronized(this) {
        loadedAt?.let { now() - it >= MAX_AGE_MS } ?: true
    }

    fun clear() = synchronized(this) {
        generation++
        cached = null
        loadedAt = null
    }

    suspend fun getOrLoad(force: Boolean = false, load: suspend () -> T?): T? = loadMutex.withLock {
        val version = synchronized(this) { generation }
        if (!force && !isStale()) value?.let { return@withLock it }
        val fresh = load()
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            // A response for an account that signed out during the request must never return.
            if (generation != version) null
            else {
                if (fresh != null) {
                    cached = fresh
                    loadedAt = now()
                }
                cached
            }
        }
    }

    companion object {
        const val MAX_AGE_MS = 30 * 60 * 1000L
    }
}
