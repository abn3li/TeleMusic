package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseContent
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Coroutine-friendly front for [InnertubeBrowseClient] - every call in there blocks on a real
 * network request, so this is the only place that's ever touched from a ViewModel. This class
 * itself is a single app-wide instance (constructed once in TgMusicApp), which is what makes
 * [cachedHome] a real cache and not just a per-screen one. */
class DiscoveryRepository(
    private val settingsStore: AppSettingsStore,
    private val client: InnertubeBrowseClient = InnertubeBrowseClient()
) {
    // The Home feed screen (YouTubeDownloadScreen) builds a fresh ViewModel via a plain
    // remember{} every time it's entered - not tied to the NavBackStackEntry, so it re-fetches
    // on every visit with no caching of its own. Without this, that meant a real ~670KB network
    // request every single time the user opened the YouTube download screen, even just to
    // search - this cache turns every visit after the first into an instant, free return.
    @Volatile private var cachedHome: List<HomeSection>? = null
    @Volatile private var cachedGenres: List<BrowseCollection>? = null
    // Which region the above were fetched for - a Settings change to youtubeRegion has to bust
    // both caches, or "changing region" would silently keep showing the old region's content
    // until the process restarted.
    @Volatile private var cachedRegion: String? = null

    private fun currentRegion(): String {
        val region = settingsStore.youtubeRegion
        android.util.Log.d("DiscoveryRepo", "currentRegion() region=$region cachedRegion=$cachedRegion cachedHomeSize=${cachedHome?.size}")
        if (region != cachedRegion) {
            cachedHome = null
            cachedGenres = null
            cachedRegion = region
        }
        return region
    }

    /** Home + New releases, merged into one feed - an anonymous (no sign-in) request only ever
     * gets 1-2 sparse sections from the Home page alone, where the real app's Home tab also
     * surfaces its New releases page inline. Fetched in parallel (two independent requests, not
     * two round trips back to back) and merged in that order. */
    suspend fun homeFeed(): List<HomeSection> {
        val region = currentRegion()
        cachedHome?.let { return it }
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val home = async {
                    runCatching { BrowseParser.parseHomeFeed(client.browse(InnertubeBrowseClient.HOME_BROWSE_ID, region = region)) }.getOrElse { emptyList() }
                }
                val newReleases = async {
                    runCatching {
                        BrowseParser.parseGridAsSection(client.browse(InnertubeBrowseClient.NEW_RELEASES_BROWSE_ID, region = region), "New releases")
                    }.getOrNull()
                }
                val merged = home.await() + listOfNotNull(newReleases.await())
                merged.also { if (it.isNotEmpty()) cachedHome = it }
            }
        }
    }

    /** The real Genres chips (see BrowseParser.parseGenreChips) - each one opens a real page of
     * playlists for that genre through [browse] like any other card. Cached the same way
     * [homeFeed] is, for the same reason. */
    suspend fun genres(): List<BrowseCollection> {
        val region = currentRegion()
        cachedGenres?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { BrowseParser.parseGenreChips(client.browse(InnertubeBrowseClient.GENRES_BROWSE_ID, region = region)) }
                .getOrElse { emptyList() }
                .also { if (it.isNotEmpty()) cachedGenres = it }
        }
    }

    suspend fun browse(browseId: String, params: String?): BrowseContent = withContext(Dispatchers.IO) {
        runCatching { BrowseParser.parseBrowseContent(client.browse(browseId, params, region = settingsStore.youtubeRegion)) }
            .getOrElse { BrowseContent() }
    }
}
