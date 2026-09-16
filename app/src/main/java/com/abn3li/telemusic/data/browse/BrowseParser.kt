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
        val subtitle = renderer.opt("subtitle").obj()?.runs()

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
        if (tracks.isNotEmpty()) return BrowseContent(tracks = tracks.values.toList())

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
        return BrowseContent(collections = collections.values.toList())
    }

    /** One playable row - null for a video-shaped one (see this file's own top-level doc: this
     * app only ever surfaces real audio tracks, never a music video standing in for one). */
    private fun parseTrackRow(renderer: JSONObject): BrowseTrack? {
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
        if (thumbnails.isWidescreen()) return null

        return BrowseTrack(
            videoId = videoId,
            title = title,
            artist = artist.ifBlank { "Unknown artist" },
            thumbnailUrl = thumbnails.best()
        )
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

    /** Real, non-empty thumbnails only - the same list of increasingly larger sizes Innertube
     * always returns, so the last one is the highest resolution available. */
    private fun JSONArray?.best(): String? {
        if (this == null || length() == 0) return null
        return optJSONObject(length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
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
}
