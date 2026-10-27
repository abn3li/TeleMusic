package com.abn3li.telemusic.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class YouTubeLyricsParserTest {
    private fun next(vararg browseIds: String?) = JSONObject("""{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":
        {"watchNextTabbedResultsRenderer":{"tabs":[${browseIds.joinToString(",") { id ->
            if (id == null) """{"tabRenderer":{"title":"Up next"}}"""
            else """{"tabRenderer":{"endpoint":{"browseEndpoint":{"browseId":"$id"}}}}"""
        }}]}}}}}""")

    @Test fun findsTheLyricsTabNotRelated() {
        assertEquals("MPLYt_abc", youTubeLyricsBrowseId(next(null, "MPLYt_abc", "MPTRt_abc")))
    }

    @Test fun noLyricsTabMeansNone() {
        assertNull(youTubeLyricsBrowseId(next(null, "MPTRt_abc")))
        assertNull(youTubeLyricsBrowseId(JSONObject("{}")))
    }

    @Test fun readsTheLyricsText() {
        val browse = JSONObject("""{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":
            {"description":{"runs":[{"text":"Is this the real life?\nIs this just fantasy?"}]},
             "footer":{"runs":[{"text":"Source: Musixmatch"}]}}}]}}}""")
        assertEquals("Is this the real life?\nIs this just fantasy?", youTubeLyricsText(browse))
        assertNull(youTubeLyricsText(JSONObject("""{"contents":{"sectionListRenderer":{"contents":[]}}}""")))
    }
}
