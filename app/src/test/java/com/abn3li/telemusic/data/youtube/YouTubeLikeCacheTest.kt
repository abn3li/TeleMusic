package com.abn3li.telemusic.data.youtube

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeLikeCacheTest {
    @Test fun repeatedMenusReuseResultUntilExpiryAndSessionsStaySeparate() = runBlocking {
        var time = 0L
        var calls = 0
        val cache = YouTubeLikeCache({ time }, 100)
        assertTrue(cache.get("accountA", "song") { calls++; true })
        assertTrue(cache.get("accountA", "song") { error("Must reuse cached result") })
        assertNull(cache.peek("accountB", "song"))
        time = 100
        assertNull(cache.peek("accountA", "song"))
        assertFalse(cache.get("accountA", "song") { calls++; false })
        assertEquals(2, calls)
    }

    @Test fun successfulRemoveUpdatesCacheAndFailedMutationDoesNotLie() = runBlocking {
        val cache = YouTubeLikeCache({ 0 })
        cache.get("account", "song") { true }
        cache.update("account", "song", false) {}
        assertEquals(false, cache.peek("account", "song"))
        assertTrue(runCatching { cache.update("account", "song", true) { error("Network failed") } }.isFailure)
        assertEquals(false, cache.peek("account", "song"))
    }

    @Test fun simultaneousMenusShareOneRequestAndFailuresCanRetry() = runBlocking {
        val cache = YouTubeLikeCache({ 0 })
        var calls = 0
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { cache.get("account", "song") { calls++; start.complete(Unit); release.await(); true } }
        start.await()
        val second = async { cache.get("account", "song") { calls++; false } }
        yield(); release.complete(Unit)
        assertTrue(first.await()); assertTrue(second.await())
        assertEquals(1, calls)
        assertTrue(runCatching { cache.get("account", "other") { error("Network failed") } }.isFailure)
        assertNull(cache.peek("account", "other"))
        assertFalse(cache.get("account", "other") { false })
    }
    @Test fun tapIsImmediateAndFailureRollsBackToConfirmedState() = runBlocking {
        val cache = YouTubeLikeCache({ 0 })
        cache.get("account", "song") { true }
        val change = cache.beginUpdate("account", "song", false)
        assertEquals(false, cache.states.value["account" to "song"])
        assertTrue(runCatching { cache.finishUpdate(change) { error("Network failed") } }.isFailure)
        assertEquals(true, cache.states.value["account" to "song"])
    }

    @Test fun expiryKeepsKnownStateAndPlaylistSeedingRespectsLocalChanges() = runBlocking {
        var time = 0L
        val cache = YouTubeLikeCache({ time }, 100)
        cache.seedLikes("account", listOf("song", "other"))
        time = 100
        assertNull(cache.peek("account", "song"))
        assertEquals(true, cache.states.value["account" to "song"])
        cache.update("account", "song", false) {}
        cache.seedLikes("account", listOf("song"))
        assertEquals(false, cache.states.value["account" to "song"])
        assertNull(cache.states.value["differentAccount" to "song"])
    }

    @Test fun slowRefreshCannotOverwriteAnOptimisticTap() = runBlocking {
        val cache = YouTubeLikeCache({ 0 })
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val query = async { cache.get("account", "song") { start.complete(Unit); release.await(); false } }
        start.await()
        val change = cache.beginUpdate("account", "song", true)
        release.complete(Unit)
        assertTrue(query.await())
        assertEquals(true, cache.states.value["account" to "song"])
        cache.finishUpdate(change) {}
        assertEquals(true, cache.peek("account", "song"))
    }

    @Test fun failedLaterTapRollsBackToEarlierSuccessfulWrite() = runBlocking {
        val cache = YouTubeLikeCache({ 0 })
        cache.get("account", "song") { false }
        val first = cache.beginUpdate("account", "song", true)
        val second = cache.beginUpdate("account", "song", false)
        cache.finishUpdate(first) {}
        assertEquals(false, cache.states.value["account" to "song"])
        assertTrue(runCatching { cache.finishUpdate(second) { error("Network failed") } }.isFailure)
        assertEquals(true, cache.states.value["account" to "song"])
    }
}
