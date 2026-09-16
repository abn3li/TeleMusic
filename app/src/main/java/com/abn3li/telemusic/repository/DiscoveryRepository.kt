package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseContent
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.local.ImportedPlaylistDao
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import com.abn3li.telemusic.data.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Coroutine-friendly front for [InnertubeBrowseClient] - every call in there blocks on a real
 * network request, so this is the only place that's ever touched from a ViewModel. This class
 * itself is a single app-wide instance (constructed once in TgMusicApp), which is what makes
 * [cachedHome] a real cache and not just a per-screen one. Also owns the imported-playlists
 * table (a user-pinned Discovery entry stays until they remove it - see
 * YouTubeDownloadViewModel.importPlaylist's own doc), since it's the same "things shown in
 * Discovery" concern as the home feed above. */
class DiscoveryRepository(
    private val settingsStore: AppSettingsStore,
    private val importedPlaylistDao: ImportedPlaylistDao,
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
                    runCatching { BrowseParser.parseHomeFeed(client.browse(InnertubeBrowseClient.HOME_BROWSE_ID, region = region)) }
                        .onFailure { e -> android.util.Log.e("DiscoveryRepo", "homeFeed(): home page fetch/parse failed", e) }
                        .getOrElse { emptyList() }
                }
                val newReleases = async {
                    runCatching {
                        BrowseParser.parseGridAsSection(client.browse(InnertubeBrowseClient.NEW_RELEASES_BROWSE_ID, region = region), "New releases")
                    }.onFailure { e -> android.util.Log.e("DiscoveryRepo", "homeFeed(): new releases fetch/parse failed", e) }
                        .getOrNull()
                }
                val merged = home.await() + listOfNotNull(newReleases.await())
                android.util.Log.d("DiscoveryRepo", "homeFeed(): resolved ${merged.size} sections for region=$region")
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
                .onFailure { e -> android.util.Log.e("DiscoveryRepo", "genres(): fetch/parse failed", e) }
                .getOrElse { emptyList() }
                .also { if (it.isNotEmpty()) cachedGenres = it }
        }
    }

    suspend fun browse(browseId: String, params: String?): BrowseContent = withContext(Dispatchers.IO) {
        runCatching {
            val region = settingsStore.youtubeRegion
            val raw = client.browse(browseId, params, region = region)
            var content = BrowseParser.parseBrowseContent(raw)
            if (content.tracks.isEmpty() && content.collections.isEmpty()) {
                // A real HTTP 200 with a page shape the parser doesn't recognize looks identical
                // to a genuinely empty page from the outside - this cheap summary (booleans only,
                // never the raw response body) is what turned "check your connection" reports
                // into actually diagnosable ones (see this repo's own investigation history).
                val rawText = raw.toString()
                android.util.Log.w(
                    "DiscoveryRepo",
                    "browse(browseId=$browseId, params=$params): parsed empty. " +
                        "topLevelKeys=${raw.keys().asSequence().toList()} " +
                        "hasMusicShelf=${rawText.contains("musicShelfRenderer")} " +
                        "hasResponsiveListItem=${rawText.contains("musicResponsiveListItemRenderer")} " +
                        "responseLength=${rawText.length}"
                )
            }

            // A track list longer than one page (a playlist/album past roughly its first 100
            // tracks) comes back with a continuation token instead of the rest inline - without
            // following it, a long playlist silently only ever showed its first page. Capped at
            // 25 extra pages (~2500+ tracks) as a sanity limit against a malformed response
            // looping forever, not a real-world playlist length concern.
            if (content.tracks.isNotEmpty()) {
                val allTracks = LinkedHashMap<String, com.abn3li.telemusic.data.browse.BrowseTrack>()
                content.tracks.forEach { allTracks[it.videoId] = it }
                var page = raw
                var pages = 0
                while (pages < 25) {
                    val token = BrowseParser.findContinuationToken(page) ?: break
                    page = client.browseContinuation(token, region = region)
                    val pageContent = BrowseParser.parseBrowseContent(page)
                    if (pageContent.tracks.isEmpty()) break
                    pageContent.tracks.forEach { allTracks[it.videoId] = it }
                    pages++
                }
                content = content.copy(tracks = allTracks.values.toList())
            }
            content
        }
            .onFailure { e -> android.util.Log.e("DiscoveryRepo", "browse(browseId=$browseId, params=$params) failed", e) }
            .getOrElse { BrowseContent() }
            .also { android.util.Log.d("DiscoveryRepo", "browse(browseId=$browseId): ${it.tracks.size} tracks, ${it.collections.size} collections") }
    }

    fun observeImportedPlaylists(): Flow<List<ImportedPlaylistEntity>> = importedPlaylistDao.observeAll()

    suspend fun saveImportedPlaylist(playlist: ImportedPlaylistEntity) = withContext(Dispatchers.IO) {
        importedPlaylistDao.upsert(playlist)
    }

    suspend fun removeImportedPlaylist(browseId: String) = withContext(Dispatchers.IO) {
        importedPlaylistDao.delete(browseId)
    }
}
