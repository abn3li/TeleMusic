package com.abn3li.telemusic.data.local

data class AlbumSummary(val album: String, val artist: String, val songCount: Int, val albumArtUrl: String?)
// albumArtUrl here is one of the artist's own songs' art (there's no separate "artist photo"
// concept anywhere in this app's data - Telegram/local imports/YouTube downloads only ever carry
// per-track cover art), same MIN()-over-the-group trick observeAlbums() already uses.
data class ArtistSummary(val artist: String, val songCount: Int, val albumArtUrl: String?)

// Same idea as ArtistSummary's albumArtUrl - a playlist has no cover art of its own, so this is
// just one of its own songs' art (the most recently added one), picked in PlaylistDao's query.
data class PlaylistSummary(val id: Long, val name: String, val createdAtMillis: Long, val albumArtUrl: String?)
