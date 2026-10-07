package com.abn3li.telemusic.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeFeedCacheTest {
    @Test fun visitsReuseTheFeedUntilThirtyMinutesAndThenFetchAgain() = runBlocking {
        var time = 0L
        var requests = 0
        val cache = HomeFeedCache<String> { time }
        suspend fun visit() = cache.getOrLoad { "feed-${++requests}" }
        assertEquals("feed-1", visit())
        time = HomeFeedCache.MAX_AGE_MS - 1
        assertEquals("feed-1", visit())
        assertEquals(1, requests)
        time++
        assertTrue(cache.isStale())
        assertEquals("feed-2", visit())
        assertEquals(2, requests)
        assertFalse(cache.isStale())
    }

    @Test fun manualRefreshBypassesFreshCacheWithoutClearingVisibleContent() = runBlocking {
        val cache = HomeFeedCache<String> { 0L }
        cache.getOrLoad { "old" }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val refresh = async {
            cache.getOrLoad(force = true) { started.complete(Unit); response.await() }
        }
        started.await()
        assertEquals("old", cache.value)
        response.complete("new")
        assertEquals("new", refresh.await())
        assertEquals("new", cache.value)
    }

    @Test fun failedRefreshKeepsOldContentAndAgeSoNextVisitRetries() = runBlocking {
        var time = 0L
        val cache = HomeFeedCache<String> { time }
        cache.getOrLoad { "old" }
        time = HomeFeedCache.MAX_AGE_MS
        assertEquals("old", cache.getOrLoad { null })
        assertTrue(cache.isStale())
        assertEquals("recovered", cache.getOrLoad { "recovered" })
        assertFalse(cache.isStale())
    }

    @Test fun switchingAccountsDuringRequestDiscardsOldResponse() = runBlocking {
        val cache = HomeFeedCache<String> { 0L }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        val oldAccount = async { cache.getOrLoad { started.complete(Unit); response.await() } }
        started.await()
        cache.clear()
        response.complete("wrong-account")
        assertNull(oldAccount.await())
        assertNull(cache.value)
        assertTrue(cache.isStale())
        assertEquals("new-account", cache.getOrLoad { "new-account" })
    }

    @Test fun cancelledRefreshDoesNotReplaceTheFeedOrPreventRetry() = runBlocking {
        var time = 0L
        val cache = HomeFeedCache<String> { time }
        cache.getOrLoad { "old" }
        time = HomeFeedCache.MAX_AGE_MS
        val started = CompletableDeferred<Unit>()
        val refresh = launch { cache.getOrLoad { started.complete(Unit); CompletableDeferred<String>().await() } }
        started.await()
        refresh.cancelAndJoin()
        assertEquals("old", cache.value)
        assertTrue(cache.isStale())
        assertEquals("new", cache.getOrLoad { "new" })
    }

    @Test fun simultaneousVisitsShareOneRequest() = runBlocking {
        val cache = HomeFeedCache<String> { 0L }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        var requests = 0
        suspend fun visit() = cache.getOrLoad {
            requests++
            started.complete(Unit)
            response.await()
        }
        val first = async { visit() }
        started.await()
        val second = async { visit() }
        response.complete("feed")
        assertEquals("feed", first.await())
        assertEquals("feed", second.await())
        assertEquals(1, requests)
    }
}
