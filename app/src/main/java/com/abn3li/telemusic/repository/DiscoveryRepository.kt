package com.abn3li.telemusic.repository

import com.abn3li.telemusic.data.browse.BrowseKind
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import com.abn3li.telemusic.data.browse.BrowseCollection
import com.abn3li.telemusic.data.browse.BrowseContent
import com.abn3li.telemusic.data.browse.BrowseParser
import com.abn3li.telemusic.data.browse.BrowseTrack
import com.abn3li.telemusic.data.browse.SearchFilter
import com.abn3li.telemusic.data.browse.HomeSection
import com.abn3li.telemusic.data.browse.InnertubeBrowseClient
import com.abn3li.telemusic.data.local.ImportedPlaylistDao
import com.abn3li.telemusic.data.local.ImportedPlaylistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Coroutine-friendly front for [InnertubeBrowseClient] - every call in there blocks on a real
 * network request, so this is the only place that's ever touched from a ViewModel. This class
 * itself is a single app-wide instance (constructed once in TgMusicApp), which is what makes
 * the discovery caches app-wide rather than per-screen. Also owns the imported-playlists
 * table (a user-pinned Discovery entry stays until they remove it - see
 * DiscoveryViewModel.importPlaylist's own doc), since it's the same "things shown in
 * Discovery" concern as the home feed above. */
class DiscoveryRepository(
    private val importedPlaylistDao: ImportedPlaylistDao,
    // Where the Search tab's categories are kept between runs (see [genres]).
    private val genresFile: File,
    private val client: InnertubeBrowseClient = InnertubeBrowseClient()
) {
    @Volatile private var cachedNewReleases: HomeSection? = null
    @Volatile private var cachedGenres: List<BrowseCollection>? = null
    @Volatile private var cachedCommunity: HomeSection? = null

    /** YouTube Music's new releases, for Home. Of the anonymous home feed itself only the
     * community playlists are used (see [communityPlaylists]); its other shelves aren't shown. */
    suspend fun newReleases(): HomeSection? {
        cachedNewReleases?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                BrowseParser.parseGridAsSection(
                    client.browse(InnertubeBrowseClient.NEW_RELEASES_BROWSE_ID),
                    "New releases"
                )
            }.onFailure { e -> android.util.Log.e("DiscoveryRepo", "newReleases(): fetch/parse failed", e) }
                .getOrNull()
                ?.also { if (it.items.isNotEmpty()) cachedNewReleases = it }
        }
    }

    /** Listener-made playlists YouTube Music is showing for this region, for Home. They sit in
     * the home feed - on its first page or one of the next few - so this reads at most
     * [COMMUNITY_FEED_PAGES] pages, once; the result (even an empty one, where a region has no
     * such shelf) is kept for the rest of the run. Null only when the feed couldn't be read, so
     * the next visit to Home tries again. */
    suspend fun communityPlaylists(): HomeSection? {
        cachedCommunity?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val found = LinkedHashMap<String, BrowseCollection>()
                var page = client.browse(InnertubeBrowseClient.HOME_BROWSE_ID)
                var pages = 1
                while (true) {
                    BrowseParser.parseCommunityPlaylists(page).forEach { found.putIfAbsent(it.browseId, it) }
                    if (found.isNotEmpty() || pages >= COMMUNITY_FEED_PAGES) break
                    val token = BrowseParser.findContinuationToken(page) ?: break
                    page = client.browseContinuation(token)
                    pages++
                }
                HomeSection(COMMUNITY_TITLE, found.values.toList())
            }.onFailure { e -> android.util.Log.e("DiscoveryRepo", "communityPlaylists(): fetch/parse failed", e) }
                .getOrNull()
                ?.also { cachedCommunity = it }
        }
    }

    /** The Search tab's categories if they're already in memory - lets the page start with them
     * instead of a spinner. */
    fun genresIfLoaded(): List<BrowseCollection>? = cachedGenres

    /** The real Genres chips (see BrowseParser.parseGenreChips) - each one opens a real page of
     * playlists for that genre through [browse] like any other card. Kept in memory, and saved
     * on the phone: after the first time they show at once, offline too. Only a phone that has
     * never loaded them waits for the network here. */
    suspend fun genres(): List<BrowseCollection> {
        cachedGenres?.let { return it }
        return withContext(Dispatchers.IO) {
            readSavedGenres()?.also { cachedGenres = it } ?: fetchGenres()
        }
    }

    /** Called once when the app starts, in the background: puts the saved categories in memory
     * right away, then refreshes them from YouTube Music for next time. One request per run. */
    suspend fun preloadGenres() = withContext(Dispatchers.IO) {
        if (cachedGenres == null) readSavedGenres()?.let { cachedGenres = it }
        if (genresRefreshed) return@withContext
        genresRefreshed = true
        fetchGenres()
    }

    @Volatile private var genresRefreshed = false

    /** From the network; a good answer replaces what's in memory and on the phone, a failed one
     * (offline) leaves both as they were. */
    private fun fetchGenres(): List<BrowseCollection> =
        runCatching { BrowseParser.parseGenreChips(client.browse(InnertubeBrowseClient.GENRES_BROWSE_ID)) }
            .onFailure { e -> android.util.Log.e("DiscoveryRepo", "genres(): fetch/parse failed", e) }
            .getOrElse { emptyList() }
            .also { if (it.isNotEmpty()) { cachedGenres = it; saveGenres(it) } }
            .ifEmpty { cachedGenres.orEmpty() }

    private fun readSavedGenres(): List<BrowseCollection>? = runCatching {
        if (!genresFile.exists()) return null
        val array = JSONArray(genresFile.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val browseId = o.optString("browseId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BrowseCollection(browseId, o.optString("params").takeIf { it.isNotBlank() }, title, null, null, BrowseKind.OTHER)
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun saveGenres(genres: List<BrowseCollection>) {
        runCatching {
            val array = JSONArray()
            genres.forEach { array.put(JSONObject().put("browseId", it.browseId).put("params", it.params ?: "").put("title", it.title)) }
            genresFile.writeText(array.toString())
        }.onFailure { e -> android.util.Log.w("DiscoveryRepo", "genres not saved", e) }
    }

    /** Songs for a search, from YouTube Music's own Songs tab - one request. */
    suspend fun searchSongs(query: String): List<BrowseTrack> = withContext(Dispatchers.IO) {
        runCatching { BrowseParser.parseSearchSongs(client.search(query, SearchFilter.SONGS)) }
            .onFailure { e -> android.util.Log.e("DiscoveryRepo", "searchSongs(\"$query\") failed", e) }
            .getOrElse { emptyList() }
    }

    /** Albums, artists or playlists for a search ([filter]). Logged with its count, so an
     * empty tab on some phone (YouTube shapes results by region) can be traced. */
    suspend fun searchCollections(query: String, filter: SearchFilter): List<BrowseCollection> = withContext(Dispatchers.IO) {
        runCatching { BrowseParser.parseSearchCollections(client.search(query, filter)) }
            .onFailure { e -> android.util.Log.e("DiscoveryRepo", "search(\"$query\", $filter) failed", e) }
            .getOrElse { emptyList() }
            .also { android.util.Log.d("DiscoveryRepo", "search(\"$query\", $filter): ${it.size} results") }
    }

    suspend fun browse(browseId: String, params: String?): BrowseContent = withContext(Dispatchers.IO) {
        // Home's "See All" for community playlists: the list already fetched, shown as a grid.
        if (browseId == COMMUNITY_BROWSE_ID) {
            return@withContext BrowseContent(collections = communityPlaylists()?.items.orEmpty())
        }
        runCatching {
            val raw = client.browse(browseId, params)
            // An artist's page is its own shape: top songs plus shelves, no track list to page.
            BrowseParser.parseArtistPage(raw)?.let { artist -> return@runCatching BrowseContent(artist = artist) }
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
                    page = client.browseContinuation(token)
                    val pageContent = BrowseParser.parseBrowseContent(page)
                    if (pageContent.tracks.isEmpty()) break
                    pageContent.tracks.forEach { allTracks[it.videoId] = it }
                    pages++
                }
                // An album's rows carry no artist or artwork of their own - both are the album's.
                val header = content.header
                content = content.copy(tracks = allTracks.values.map { t ->
                    t.copy(
                        artist = t.artist.ifBlank { header?.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist" },
                        thumbnailUrl = t.thumbnailUrl ?: header?.thumbnailUrl
                    )
                })
            }
            content
        }
            .onFailure { e -> android.util.Log.e("DiscoveryRepo", "browse(browseId=$browseId, params=$params) failed", e) }
            .getOrElse { BrowseContent() }
            .also { android.util.Log.d("DiscoveryRepo", "browse(browseId=$browseId): ${it.tracks.size} tracks, ${it.collections.size} collections, artist=${it.artist != null}") }
    }

    fun observeImportedPlaylists(): Flow<List<ImportedPlaylistEntity>> = importedPlaylistDao.observeAll()

    suspend fun saveImportedPlaylist(playlist: ImportedPlaylistEntity) = withContext(Dispatchers.IO) {
        importedPlaylistDao.upsert(playlist)
    }

    suspend fun removeImportedPlaylist(browseId: String) = withContext(Dispatchers.IO) {
        importedPlaylistDao.delete(browseId)
    }

    companion object {
        const val COMMUNITY_TITLE = "Community Playlists"
        /** Not a YouTube id: opens the community playlists already loaded for Home (see [browse]). */
        const val COMMUNITY_BROWSE_ID = "telemusic_community_playlists"
        private const val COMMUNITY_FEED_PAGES = 4
    }
}
