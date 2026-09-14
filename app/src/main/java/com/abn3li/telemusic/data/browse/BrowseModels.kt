package com.abn3li.telemusic.data.browse

/** One playable track found while browsing (a playlist/chart/artist's own track list) - the
 * only kind of item this whole file's client hands back that's actually downloadable. Mirrors
 * data/download/YtDlpSearchResult's shape since both eventually feed the same download flow. */
data class BrowseTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?
)

/** A card that links to another browse page (a playlist, an artist's channel, an album, a
 * chart) - not playable itself, only ever a destination for [InnertubeBrowseClient.browse]. */
data class BrowseCollection(
    val browseId: String,
    val params: String?,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val kind: BrowseKind
)

enum class BrowseKind { PLAYLIST, ARTIST, ALBUM, OTHER }

/** One shelf of the home feed ("Today's hits", "New releases", etc) - the title comes straight
 * from YouTube Music's own feed, this app doesn't invent section names. */
data class HomeSection(val title: String, val items: List<BrowseCollection>)

/** One browse page's worth of content: either a track list (a playlist/chart/artist's own
 * songs) or more collection cards (a chart page linking to sub-charts, say) - never
 * meaningfully both at once in practice, same tradeoff the search-side parser makes. */
data class BrowseContent(
    val tracks: List<BrowseTrack> = emptyList(),
    val collections: List<BrowseCollection> = emptyList()
)
