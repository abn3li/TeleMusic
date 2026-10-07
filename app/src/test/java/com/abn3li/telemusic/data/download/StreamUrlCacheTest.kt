package com.abn3li.telemusic.data.download

import org.junit.Assert.*
import org.junit.Test

class StreamUrlCacheTest {
    private fun stream(url: String) = YtDlpStreamResult("Song", "Artist", 200, null, url)

    @Test fun qualityChangeDoesNotReuseThePreviousQualityUrl() {
        val cache = StreamUrlCache { 0L }
        cache.put("song", "low", stream("low-url"))
        assertNull(cache.get("song", "best"))
        cache.put("song", "best", stream("best-url"))
        assertEquals("best-url", cache.get("song", "best")?.streamUrl)
        assertEquals("low-url", cache.get("song", "low")?.streamUrl)
    }

    @Test fun expiredUrlIsUnavailableAtTheDeadline() {
        var elapsed = 50L
        val cache = StreamUrlCache { elapsed }
        cache.put("song", "best", stream("url"))
        elapsed += StreamUrlCache.MAX_AGE_MS - 1
        assertNotNull(cache.get("song", "best"))
        elapsed++
        assertNull(cache.get("song", "best"))
    }

    @Test fun expiredStreamRetryInvalidatesEveryQualityForThatVideo() {
        val cache = StreamUrlCache { 0L }
        cache.put("song", "low", stream("old-low"))
        cache.put("song", "best", stream("old-best"))
        cache.put("other", "best", stream("other-url"))
        cache.invalidate("song")
        assertNull(cache.get("song", "low"))
        assertNull(cache.get("song", "best"))
        assertEquals("other-url", cache.get("other", "best")?.streamUrl)
    }

    @Test fun clearingStreamingCacheRemovesAllResolvedUrls() {
        val cache = StreamUrlCache { 0L }
        cache.put("one", "best", stream("one-url"))
        cache.put("two", "low", stream("two-url"))
        cache.clear()
        assertNull(cache.get("one", "best"))
        assertNull(cache.get("two", "low"))
    }
}
