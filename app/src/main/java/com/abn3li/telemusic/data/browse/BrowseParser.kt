package com.abn3li.telemusic.data.browse

import org.json.JSONArray
import org.json.JSONObject

/**
 * Innertube's browse JSON -> the plain models in BrowseModels.kt. Every lookup here is
 * defensive (returns null/skips on anything unexpected) rather than assuming a fixed shape,
 * matching data/youtube's own InnertubeParser precedent from the earlier prototype - a layout
 * change on Google's end should degrade to "this item is skipped", not a crash.
 */
object BrowseParser {

    fun relatedBrowseId(response: JSONObject): String? =
        collectRenderers(response, "browseEndpoint").firstOrNull { endpoint ->
            endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType") == "MUSIC_PAGE_TYPE_TRACK_RELATED"
        }?.optString("browseId")?.takeIf { it.isNotBlank() }

    /** Keep the song recommendations separate from performances and artist discographies. */
    fun parseRelatedSongs(response: JSONObject): List<BrowseTrack> {
        val shelf = collectRenderers(response, "musicCarouselShelfRenderer").firstOrNull {
            it.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?.optJSONObject("title")?.runs().equals("You might also like", ignoreCase = true)
        } ?: return emptyList()
        return parseSearchSongs(shelf)
    }

    fun parseHomeFeed(response: JSONObject): List<HomeSection> {
        val tabs = response.opt("contents").obj()?.opt("singleColumnBrowseResultsRenderer").obj()?.opt("tabs").arr()
        val shelves = tabs?.optJSONObject(0)?.opt("tabRenderer").obj()?.opt("content").obj()
            ?.opt("sectionListRenderer").obj()?.opt("contents").arr() ?: return emptyList()

        val sections = mutableListOf<HomeSection>()
        for (i in 0 until shelves.length()) {
            val carousel = shelves.optJSONObject(i)?.opt("musicCarouselShelfRenderer").obj() ?: continue
            val title = carousel.opt("header").obj()?.opt("musicCarouselShelfBasicHeaderRenderer").obj()
                ?.opt("title").obj()?.runs().orEmpty()
            val itemsJson = carousel.opt("contents").arr() ?: continue
            val items = mutableListOf<BrowseCollection>()
            for (j in 0 until itemsJson.length()) {
                val renderer = itemsJson.optJSONObject(j)?.opt("musicTwoRowItemRenderer").obj() ?: continue
                parseTwoRowCollection(renderer)?.let { items.add(it) }
            }
            if (items.isNotEmpty()) sections.add(HomeSection(title.ifBlank { "For you" }, items))
        }
        return sections
    }

    /** Playlists made by listeners, from the home feed's own community shelves ("Trending
     * community playlists", "From the community"). Works on the feed's first page and on its
     * continuation pages alike, since it looks for the shelves wherever they sit. */
    fun parseCommunityPlaylists(response: JSONObject): List<BrowseCollection> {
        val playlists = LinkedHashMap<String, BrowseCollection>()
        collectRenderers(response, "musicCarouselShelfRenderer").forEach { carousel ->
            val title = carousel.opt("header").obj()?.opt("musicCarouselShelfBasicHeaderRenderer").obj()
                ?.opt("title").obj()?.runs().orEmpty()
            if (!title.contains("community", ignoreCase = true)) return@forEach
            val cards = carousel.opt("contents").arr() ?: return@forEach
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i)?.opt("musicTwoRowItemRenderer").obj()?.let { parseTwoRowCollection(it) }
                if (card != null && card.kind == BrowseKind.PLAYLIST) playlists.putIfAbsent(card.browseId, card)
            }
        }
        return playlists.values.toList()
    }

    /** A whole browse page whose content is one (or more) plain grids rather than carousels -
     * "New releases" is shaped this way (a single gridRenderer, no carousel, no title of its
     * own), unlike the Home feed's carousels. [fallbackTitle] stands in since the page itself
     * doesn't carry a section title to read. [limit] keeps this to a normal shelf's worth of
     * cards rather than every one of the (sometimes 90+) items the page actually has - this is
     * shown as one horizontally-scrolling row, not a full grid screen. */
    fun parseGridAsSection(response: JSONObject, fallbackTitle: String, limit: Int = 25): HomeSection? {
        val tabs = response.opt("contents").obj()?.opt("singleColumnBrowseResultsRenderer").obj()?.opt("tabs").arr()
        val shelves = tabs?.optJSONObject(0)?.opt("tabRenderer").obj()?.opt("content").obj()
            ?.opt("sectionListRenderer").obj()?.opt("contents").arr() ?: return null

        val items = mutableListOf<BrowseCollection>()
        for (i in 0 until shelves.length()) {
            if (items.size >= limit) break
            val grid = shelves.optJSONObject(i)?.opt("gridRenderer").obj() ?: continue
            val gridItems = grid.opt("items").arr() ?: continue
            for (j in 0 until gridItems.length()) {
                if (items.size >= limit) break
                val renderer = gridItems.optJSONObject(j)?.opt("musicTwoRowItemRenderer").obj() ?: continue
                parseTwoRowCollection(renderer)?.let { items.add(it) }
            }
        }
        return if (items.isNotEmpty()) HomeSection(fallbackTitle, items) else null
    }

    /** The "Genres" grid from FEmusic_moods_and_genres - these are real navigation chips
     * (musicNavigationButtonRenderer), not playlist cards themselves: each one's own
     * browseId+params opens a real FEmusic_moods_and_genres_category page full of playlists for
     * that genre, verified to parse fine through parseBrowseContent's own generic
     * musicTwoRowItemRenderer walk - so a chip is modeled as a plain BrowseCollection (no
     * artwork/subtitle of its own) and opened through the exact same BrowseCollectionScreen every
     * other card uses, not a separate screen. Matched by the grid's own header title rather than
     * position, since the page also has an unrelated "Moods & moments" grid ahead of it. */
    fun parseGenreChips(response: JSONObject): List<BrowseCollection> {
        val tabs = response.opt("contents").obj()?.opt("singleColumnBrowseResultsRenderer").obj()?.opt("tabs").arr()
        val shelves = tabs?.optJSONObject(0)?.opt("tabRenderer").obj()?.opt("content").obj()
            ?.opt("sectionListRenderer").obj()?.opt("contents").arr() ?: return emptyList()

        for (i in 0 until shelves.length()) {
            val grid = shelves.optJSONObject(i)?.opt("gridRenderer").obj() ?: continue
            val headerTitle = grid.opt("header").obj()?.opt("gridHeaderRenderer").obj()?.opt("title").obj()?.runs().orEmpty()
            if (!headerTitle.equals("Genres", ignoreCase = true)) continue

            val gridItems = grid.opt("items").arr() ?: continue
            val chips = mutableListOf<BrowseCollection>()
            for (j in 0 until gridItems.length()) {
                val renderer = gridItems.optJSONObject(j)?.opt("musicNavigationButtonRenderer").obj() ?: continue
                val title = renderer.opt("buttonText").obj()?.runs().orEmpty()
                if (title.isBlank()) continue
                val endpoint = renderer.opt("clickCommand").obj()?.opt("browseEndpoint").obj() ?: continue
                val browseId = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: continue
                val params = endpoint.optString("params").takeIf { it.isNotBlank() }
                chips.add(BrowseCollection(browseId = browseId, params = params, title = title, subtitle = null, thumbnailUrl = null, kind = BrowseKind.OTHER))
            }
            return chips
        }
        return emptyList()
    }

    /** A collection card from a carousel (home feed, or a playlist/artist page's "similar"
     * shelves) - only kept when its own art is real square album/playlist art, not a video's
     * widescreen thumbnail, per this app's own "audio only" rule (see InnertubeBrowseClient's
     * own doc and the user's own request this was built for). */
    private fun parseTwoRowCollection(renderer: JSONObject): BrowseCollection? {
        val endpoint = renderer.opt("navigationEndpoint").obj()?.opt("browseEndpoint").obj()
            ?: renderer.opt("title").obj()?.runsArr()?.optJSONObject(0)?.opt("navigationEndpoint").obj()
                ?.opt("browseEndpoint").obj()
            ?: return null
        val browseId = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return null
        val pageType = endpoint.opt("browseEndpointContextSupportedConfigs").obj()
            ?.opt("browseEndpointContextMusicConfig").obj()?.optString("pageType").orEmpty()
        val kind = when {
            "ARTIST" in pageType -> BrowseKind.ARTIST
            "ALBUM" in pageType -> BrowseKind.ALBUM
            "PLAYLIST" in pageType -> BrowseKind.PLAYLIST
            else -> BrowseKind.OTHER
        }
        val title = renderer.opt("title").obj()?.runs().orEmpty()
        if (title.isBlank()) return null
        val subtitle = renderer.opt("subtitle").obj()?.runs()?.let(::isolateParts)

        val aspectRatio = renderer.optString("aspectRatio")
        // An explicit VIDEO-shaped aspect ratio is a hard skip; anything else falls through to
        // the same thumbnail-shape check parseTrackRow uses, since not every response bothers
        // setting this field on every card.
        if ("VIDEO" in aspectRatio) return null
        val thumbnails = renderer.opt("thumbnailRenderer").obj()?.opt("musicThumbnailRenderer").obj()
            ?.opt("thumbnail").obj()?.opt("thumbnails").arr()
        if (thumbnails.isWidescreen()) return null

        return BrowseCollection(
            browseId = browseId,
            params = endpoint.optString("params").takeIf { it.isNotBlank() },
            title = title,
            subtitle = subtitle?.takeIf { it.isNotBlank() },
            thumbnailUrl = thumbnails.best(),
            kind = kind
        )
    }

    /**
     * A browse page's own content: a track list (playlist/chart/artist page) if it has one, else
     * the collection cards it links to instead (an artist's "Albums" shelf, say). Walks the
     * whole response rather than a fixed path - a playlist's two-column layout, an artist page,
     * and a chart page all differ enough that hard-coding one path per type isn't worth it, same
     * tradeoff the search-side parser makes for the same reason.
     */
    fun parseBrowseContent(response: JSONObject): BrowseContent {
        val trackRenderers = collectRenderers(response, "musicResponsiveListItemRenderer")
        val tracks = LinkedHashMap<String, BrowseTrack>()
        trackRenderers.forEach { renderer ->
            parseTrackRow(renderer)?.let { tracks[it.videoId] = it }
        }
        // A listener's playlist made of music videos (most "PL" playlists on an artist page) has
        // only widescreen rows: rather than an empty page, its songs are kept - they play as audio
        // like any other. A list of real songs still leaves out the odd video among them.
        if (tracks.isEmpty()) trackRenderers.forEach { renderer ->
            parseTrackRow(renderer, allowWidescreen = true)?.let { tracks[it.videoId] = it }
        }
        if (tracks.isNotEmpty()) return BrowseContent(tracks = tracks.values.toList(), header = parseCollectionHeader(response))

        if (trackRenderers.isNotEmpty()) {
            // Real renderers were found (an actual JSONObject walk, not a text search) but none
            // produced a usable track - cheap enough to always log, and was exactly what traced
            // the OMV/UGC filter bug above (see parseTrackRow's own doc) to a real cause instead
            // of the misleading "check your connection" this used to surface as.
            android.util.Log.w(
                "BrowseParser",
                "parseBrowseContent(): ${trackRenderers.size} musicResponsiveListItemRenderer found but 0 produced a usable track"
            )
        }

        val collections = LinkedHashMap<String, BrowseCollection>()
        collectRenderers(response, "musicTwoRowItemRenderer").forEach { renderer ->
            parseTwoRowCollection(renderer)?.let { collections.putIfAbsent(it.browseId, it) }
        }
        // A page of moods and genres (a "Show all" can open one): buttons that each open a page
        // of playlists, like the Search tab's categories.
        collectRenderers(response, "musicNavigationButtonRenderer").forEach { renderer ->
            val title = renderer.opt("buttonText").obj()?.runs().orEmpty().ifBlank { return@forEach }
            val endpoint = renderer.opt("clickCommand").obj()?.opt("browseEndpoint").obj() ?: return@forEach
            val browseId = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return@forEach
            val params = endpoint.optString("params").takeIf { it.isNotBlank() }
            collections.putIfAbsent(browseId + params.orEmpty(), BrowseCollection(browseId, params, title, null, null, BrowseKind.OTHER))
        }
        return BrowseContent(collections = collections.values.toList(), header = parseCollectionHeader(response))
    }

    /**
     * Albums, artists or playlists from a filtered search (see SearchFilter): each result row
     * that opens a page rather than playing. Podcasts, episodes and profiles are left out -
     * only what this app can open and play.
     */
    fun parseSearchCollections(response: JSONObject): List<BrowseCollection> {
        val out = LinkedHashMap<String, BrowseCollection>()
        collectRenderers(response, "musicResponsiveListItemRenderer").forEach { renderer ->
            val endpoint = renderer.opt("navigationEndpoint").obj()?.opt("browseEndpoint").obj() ?: return@forEach
            val browseId = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return@forEach
            val kind = kindOf(endpoint) ?: return@forEach
            val flexColumns = renderer.opt("flexColumns").arr()
            val title = flexColumns?.optJSONObject(0)?.opt("musicResponsiveListItemFlexColumnRenderer").obj()
                ?.opt("text").obj()?.runs().orEmpty()
            if (title.isBlank()) return@forEach
            val subtitle = flexColumns?.optJSONObject(1)?.opt("musicResponsiveListItemFlexColumnRenderer").obj()
                ?.opt("text").obj()?.runs()?.trim()?.takeIf { it.isNotBlank() }?.let(::isolateParts)
            val thumbnails = renderer.opt("thumbnail").obj()?.opt("musicThumbnailRenderer").obj()
                ?.opt("thumbnail").obj()?.opt("thumbnails").arr()
            out.putIfAbsent(
                browseId,
                BrowseCollection(
                    browseId = browseId,
                    params = endpoint.optString("params").takeIf { it.isNotBlank() },
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnails.best(),
                    kind = kind
                )
            )
        }
        return out.values.toList()
    }

    /** Songs from a filtered "Songs" search: the same rows an album's track list has. */
    fun parseSearchSongs(response: JSONObject): List<BrowseTrack> {
        val tracks = LinkedHashMap<String, BrowseTrack>()
        collectRenderers(response, "musicResponsiveListItemRenderer").forEach { renderer ->
            parseTrackRow(renderer)?.let { tracks.putIfAbsent(it.videoId, it.copy(artist = it.artist.ifBlank { "Unknown artist" })) }
        }
        return tracks.values.toList()
    }

    private fun kindOf(endpoint: JSONObject): BrowseKind? {
        val pageType = endpoint.opt("browseEndpointContextSupportedConfigs").obj()
            ?.opt("browseEndpointContextMusicConfig").obj()?.optString("pageType").orEmpty()
        return when {
            "USER_CHANNEL" in pageType || "PODCAST" in pageType || "EPISODE" in pageType -> null
            "ARTIST" in pageType -> BrowseKind.ARTIST
            "ALBUM" in pageType -> BrowseKind.ALBUM
            "PLAYLIST" in pageType -> BrowseKind.PLAYLIST
            else -> null
        }
    }

    /**
     * An album's or playlist's own title block. Newer pages use musicResponsiveHeaderRenderer
     * (title, "Album • 2024", the artist in straplineTextOne, "10 songs • 38 minutes"); older
     * ones musicDetailHeaderRenderer, with the artist inside the subtitle ("Album • Artist •
     * 2024"). Null when the page has neither.
     */
    fun parseCollectionHeader(response: JSONObject): CollectionHeader? {
        val header = collectRenderers(response, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: collectRenderers(response, "musicDetailHeaderRenderer").firstOrNull()
            ?: return null
        val title = header.opt("title").obj()?.runs().orEmpty()
        if (title.isBlank()) return null
        val subtitleRuns = header.opt("subtitle").obj()?.runsArr()
        val strapline = header.opt("straplineTextOne").obj()
        // The artist: the strapline (newer pages), else the subtitle part that links to one.
        var artist = strapline?.runs()?.takeIf { it.isNotBlank() }
        var artistBrowseId = strapline?.runsArr()?.let { firstArtistBrowseId(it) }
        if (artist == null && subtitleRuns != null) {
            for (i in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(i) ?: continue
                val endpoint = run.opt("navigationEndpoint").obj()?.opt("browseEndpoint").obj() ?: continue
                if (kindOf(endpoint) == BrowseKind.ARTIST) {
                    artist = run.optString("text").takeIf { it.isNotBlank() }
                    artistBrowseId = endpoint.optString("browseId").takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        // "Album • Kaya Moon • 2024" -> "Album • 2024": the artist has a line of its own.
        val subtitle = header.opt("subtitle").obj()?.runs()?.split(" • ")
            ?.map { it.trim() }?.filter { it.isNotBlank() && it != artist }?.joinToString(" • ")
            ?.takeIf { it.isNotBlank() }
        val thumbnails = header.opt("thumbnail").obj()?.let { collectArrays(it, "thumbnails").firstOrNull() }
        return CollectionHeader(
            title = title,
            subtitle = subtitle,
            artist = artist,
            artistBrowseId = artistBrowseId,
            detail = header.opt("secondSubtitle").obj()?.runs()?.takeIf { it.isNotBlank() },
            thumbnailUrl = googleArtworkAtSize(thumbnails.largestUrl(), FULL_ARTWORK_SIZE)
        )
    }

    private fun firstArtistBrowseId(runs: JSONArray): String? {
        for (i in 0 until runs.length()) {
            val endpoint = runs.optJSONObject(i)?.opt("navigationEndpoint").obj()?.opt("browseEndpoint").obj() ?: continue
            if (kindOf(endpoint) == BrowseKind.ARTIST) return endpoint.optString("browseId").takeIf { it.isNotBlank() }
        }
        return null
    }

    /**
     * An artist's page, or null when [response] isn't one. Top songs come from its song shelf
     * (whose title links to the full list); every carousel after it (Albums, Singles & EPs,
     * Playlists, Fans might also like) becomes a shelf of cards - video carousels drop out on
     * their own, since video-shaped cards are skipped (see parseTwoRowCollection).
     */
    fun parseArtistPage(response: JSONObject): ArtistPage? {
        val header = collectRenderers(response, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: collectRenderers(response, "musicVisualHeaderRenderer").firstOrNull()
            ?: return null
        val name = header.opt("title").obj()?.runs().orEmpty()
        if (name.isBlank()) return null
        val subtitle = header.opt("monthlyListenerCount").obj()?.runs()?.takeIf { it.isNotBlank() }
            ?: header.opt("subscriptionButton").obj()?.opt("subscribeButtonRenderer").obj()
                ?.opt("subscriberCountText").obj()?.runs()?.takeIf { it.isNotBlank() }?.let { "$it subscribers" }
        val picture = header.opt("thumbnail").obj()?.let { collectArrays(it, "thumbnails").firstOrNull() }.largestUrl()

        var topSongs = emptyList<BrowseTrack>()
        var allSongsBrowseId: String? = null
        var allSongsParams: String? = null
        collectRenderers(response, "musicShelfRenderer").firstOrNull()?.let { shelf ->
            val songs = LinkedHashMap<String, BrowseTrack>()
            shelf.opt("contents").arr()?.let { rows ->
                for (i in 0 until rows.length()) {
                    rows.optJSONObject(i)?.opt("musicResponsiveListItemRenderer").obj()
                        ?.let { parseTrackRow(it) }?.let { songs.putIfAbsent(it.videoId, it.copy(artist = it.artist.ifBlank { name.ifBlank { "Unknown artist" } })) }
                }
            }
            topSongs = songs.values.toList()
            val more = shelf.opt("title").obj()?.runsArr()?.optJSONObject(0)?.opt("navigationEndpoint").obj()
                ?.opt("browseEndpoint").obj()
                ?: shelf.opt("bottomEndpoint").obj()?.opt("browseEndpoint").obj()
            allSongsBrowseId = more?.optString("browseId")?.takeIf { it.isNotBlank() }
            allSongsParams = more?.optString("params")?.takeIf { it.isNotBlank() }
        }

        val shelves = mutableListOf<HomeSection>()
        collectRenderers(response, "musicCarouselShelfRenderer").forEach { carousel ->
            val title = carousel.opt("header").obj()?.opt("musicCarouselShelfBasicHeaderRenderer").obj()
                ?.opt("title").obj()?.runs().orEmpty()
            val items = mutableListOf<BrowseCollection>()
            carousel.opt("contents").arr()?.let { cards ->
                for (i in 0 until cards.length()) {
                    cards.optJSONObject(i)?.opt("musicTwoRowItemRenderer").obj()
                        ?.let { parseTwoRowCollection(it) }?.let { items.add(it) }
                }
            }
            if (title.isNotBlank() && items.isNotEmpty()) shelves.add(HomeSection(title, items))
        }
        return ArtistPage(name, subtitle, picture, topSongs, allSongsBrowseId, allSongsParams, shelves)
    }

    /** One playable row - null for a video-shaped one (see this file's own top-level doc: this
     * app only ever surfaces real audio tracks, never a music video standing in for one). */
    private fun parseTrackRow(renderer: JSONObject, allowWidescreen: Boolean = false): BrowseTrack? {
        val overlayEndpoint = renderer.opt("overlay").obj()?.opt("musicItemThumbnailOverlayRenderer").obj()
            ?.opt("content").obj()?.opt("musicPlayButtonRenderer").obj()?.opt("playNavigationEndpoint").obj()
            ?.opt("watchEndpoint").obj()
        val videoId = overlayEndpoint?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: renderer.opt("playlistItemData").obj()?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: renderer.opt("navigationEndpoint").obj()?.opt("watchEndpoint").obj()?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null

        // No musicVideoType (OMV/UGC) filter here, on purpose - traced via logcat to a real
        // album ("Pop Motivation") whose tracks were all correctly found (65
        // musicResponsiveListItemRenderer entries, real videoId/title present) but then every
        // single one got silently dropped by that filter, producing 0 tracks with no error at
        // all. A track being tagged "this also has an official music video" doesn't mean it
        // isn't a real, playable song - parseTrackRow's only caller is album/playlist browsing
        // (see parseBrowseContent), never a raw video search, so there's no "real video result
        // mixed into song results" case here to filter out in the first place.
        val flexColumns = renderer.opt("flexColumns").arr()
        val title = flexColumns?.optJSONObject(0)?.opt("musicResponsiveListItemFlexColumnRenderer").obj()
            ?.opt("text").obj()?.runs().orEmpty().ifBlank { renderer.opt("title").obj()?.runs().orEmpty() }
        if (title.isBlank()) return null
        val artist = flexColumns?.optJSONObject(1)?.opt("musicResponsiveListItemFlexColumnRenderer").obj()
            ?.opt("text").obj()?.runs()?.substringBefore(" • ")?.trim().orEmpty()

        val thumbnails = renderer.opt("thumbnail").obj()?.opt("musicThumbnailRenderer").obj()
            ?.opt("thumbnail").obj()?.opt("thumbnails").arr()
        if (!allowWidescreen && thumbnails.isWidescreen()) return null

        var durationSecs = 0
        val fixedColumns = renderer.opt("fixedColumns").arr()
        if (fixedColumns != null) {
            for (i in 0 until fixedColumns.length()) {
                val txt = fixedColumns.optJSONObject(i)?.opt("musicResponsiveListItemFixedColumnRenderer").obj()
                    ?.opt("text").obj()?.runs().orEmpty()
                if (txt.contains(":") && txt.all { it.isDigit() || it == ':' }) {
                    durationSecs = parseDurationTextSeconds(txt)
                    if (durationSecs > 0) break
                }
            }
        }
        if (durationSecs == 0 && flexColumns != null) {
            for (i in 0 until flexColumns.length()) {
                val txt = flexColumns.optJSONObject(i)?.opt("musicResponsiveListItemFlexColumnRenderer").obj()
                    ?.opt("text").obj()?.runs().orEmpty()
                val match = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?)\b""").find(txt)
                if (match != null) {
                    durationSecs = parseDurationTextSeconds(match.groupValues[1])
                    if (durationSecs > 0) break
                }
            }
        }

        return BrowseTrack(
            videoId = videoId,
            title = title,
            // Blank on an album's own track list: those rows leave the artist to the album
            // header, and the caller fills it from there (see DiscoveryRepository.browse).
            artist = artist,
            thumbnailUrl = thumbnails.best(),
            durationSeconds = durationSecs
        )
    }

    private fun parseDurationTextSeconds(text: String): Int {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** The token for a browse page's NEXT page of results, if it has one - a playlist/album
     * longer than one page (verified: YouTube Music pages a playlist's track list past roughly
     * its first 100 tracks) carries this so [InnertubeBrowseClient.browseContinuation] can fetch
     * the rest. Covers both shapes Innertube uses for this depending on the response: an older
     * `continuations: [{ nextContinuationData: { continuation: "..." } }]` array sitting next to
     * a shelf's own `contents`, and a newer `continuationItemRenderer` entry trailing the content
     * list itself (`continuationEndpoint.continuationCommand.token`). Walks the whole response
     * rather than a fixed path, same tradeoff [collectRenderers] makes - the exact shelf type
     * (musicPlaylistShelfRenderer/musicShelfRenderer/musicPlaylistShelfContinuation/...) isn't
     * worth hard-coding when a plain recursive search finds the token regardless. */
    fun findContinuationToken(response: JSONObject): String? {
        var found: String? = null
        fun walk(node: Any?) {
            if (found != null) return
            when (node) {
                is JSONObject -> {
                    node.opt("continuation")?.let { if (it is String && it.isNotBlank()) found = it }
                    if (found != null) return
                    node.opt("continuationCommand").obj()?.optString("token")?.takeIf { it.isNotBlank() }?.let { found = it }
                    if (found != null) return
                    node.keys().forEach { key -> if (found == null) walk(node.opt(key)) }
                }
                is JSONArray -> for (i in 0 until node.length()) {
                    if (found != null) break
                    walk(node.opt(i))
                }
            }
        }
        walk(response)
        return found
    }

    private fun collectRenderers(root: Any?, name: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.opt(name)?.let { (it as? JSONObject)?.let(out::add) }
                    node.keys().forEach { key -> walk(node.opt(key)) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out
    }

    // ---- Tiny JSON navigation helpers (null-safe, never throw) -------------

    private fun Any?.obj(): JSONObject? = this as? JSONObject
    private fun Any?.arr(): JSONArray? = this as? JSONArray

    private fun JSONObject.runs(): String {
        val runs = opt("runs").arr() ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return builder.toString()
    }

    private fun JSONObject.runsArr(): JSONArray? = opt("runs").arr()

    /** Every array named [name] under [root], in document order. */
    private fun collectArrays(root: Any?, name: String): List<JSONArray> {
        val out = mutableListOf<JSONArray>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    (node.opt(name) as? JSONArray)?.let(out::add)
                    node.keys().forEach { key -> walk(node.opt(key)) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out
    }

    /** The largest thumbnail's own link, as served - for a hero picture, which may be wide
     * (an artist's banner) and must keep its shape, unlike [best]'s square cover size. */
    private fun JSONArray?.largestUrl(): String? {
        if (this == null || length() == 0) return null
        return optJSONObject(length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
    }

    /** Real, non-empty thumbnails only - the same list of increasingly larger sizes Innertube
     * always returns, so the last one is the highest resolution available. */
    private fun JSONArray?.best(): String? {
        if (this == null || length() == 0) return null
        return googleArtworkAtSize(optJSONObject(length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }, SAVED_ARTWORK_SIZE)
    }

    /** True when the largest thumbnail's own width/height ratio reads as a video frame (16:9-
     * ish) rather than album art (square-ish) - real music-video uploads always report their
     * actual widescreen frame size here, where a song's own art never does. */
    private fun JSONArray?.isWidescreen(): Boolean {
        if (this == null || length() == 0) return false
        val largest = optJSONObject(length() - 1) ?: return false
        val width = largest.optInt("width", 0)
        val height = largest.optInt("height", 0)
        if (width <= 0 || height <= 0) return false
        val ratio = width.toDouble() / height.toDouble()
        return ratio > 1.2
    }

    /** "كرار • 5M views" drawn as written. Unwrapped, the bidi algorithm pulls the "5" into the
     * Arabic name before it and the line reads "5 • كرارM views"; isolating each " • " part (FSI
     * ... PDI, invisible) keeps every part in its own direction and the parts in their order. */
    private fun isolateParts(text: String): String =
        if (text.none { Character.getDirectionality(it).let { d -> d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } }) text
        else text.split(" • ").joinToString(" • ") { "\u2068$it\u2069" }
}
