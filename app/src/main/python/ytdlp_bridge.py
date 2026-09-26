"""Thin bridge between Kotlin and real yt-dlp (Unlicense/public domain), run through Chaquopy's
bundled Python interpreter. Kept deliberately small - every actual decision (format selection,
throttling/cipher handling, retries) is yt-dlp's own, not reimplemented here. Every function
returns plain dicts/lists (str/int/float/None/dict/list only) since that's what crosses the
Chaquopy Kotlin<->Python boundary cleanly - no custom Python objects.
"""

import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote
import yt_dlp

_ARTIST_SPLIT = re.compile(r"\s*(?:,|&|/| feat\.?| ft\.?| x )\s*", re.IGNORECASE)


def _primary_artist(info):
    """The one artist a track groups under in the library - a collab ("The Weeknd, Daft Punk")
    would otherwise become its own separate artist bucket next to "The Weeknd"'s solo tracks,
    even though it's clearly still a Weeknd track to a listener. The featured artist isn't lost
    information: YouTube's own title almost always already spells it out ("Starboy (feat. Daft
    Punk)"), so trimming the credit down to just the primary name here doesn't hide anything.
    """
    artists = info.get("artists")
    if isinstance(artists, list) and artists:
        return artists[0]
    raw = info.get("artist") or info.get("channel") or info.get("uploader")
    if not raw:
        return "Unknown artist"
    return _ARTIST_SPLIT.split(raw, maxsplit=1)[0].strip() or raw


def _entry_from_info(info):
    return {
        "id": info.get("id"),
        "title": info.get("title") or "Unknown title",
        "artist": _primary_artist(info),
        "duration": int(info.get("duration") or 0),
        "thumbnail": info.get("thumbnail"),
    }


def search(query, limit=12):
    """Returns up to [limit] search results for [query] - metadata only, nothing downloaded.

    Searches YouTube Music's own "Songs" tab (music.youtube.com/search#songs), not plain
    youtube.com - a plain search mixes in official videos, live performances, lyric videos,
    fan uploads etc, where the Songs tab is YouTube Music's own curated "this is the actual
    track" list, matching what a user typing a song name actually wants back.

    Two real requests per search, not one: the Songs tab's own listing (flat-extracted, so this
    part is fast) only ever hands back a bare id/title per entry - no artist, thumbnail, or
    duration at all, regardless of flat mode - so each of those ids is then fetched in full,
    in parallel (these are separate independent network calls, not CPU work, so a thread pool
    here is a real speedup, not just busywork), for the real metadata a result row needs.
    """
    t0 = time.monotonic()
    video_ids = _search_song_ids(query, limit)
    t1 = time.monotonic()
    if not video_ids:
        print(f"[timing] search(): song id listing took {t1 - t0:.2f}s, 0 ids - nothing to fetch")
        return []

    def fetch(video_id):
        opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            # Only title/artist/duration/thumbnail are ever read from this result - never a
            # stream url/format (see resolve_stream_url for the one place that actually needs
            # those, using the real DownloadQuality selector). yt-dlp's default extraction tries
            # several internal YouTube "player clients" (web/android/ios/...) in turn for
            # robustness against format restrictions - real, useful work when a playable URL is
            # the goal, but pure overhead here. Pinning to just the android client (fast, and
            # metadata-complete for ordinary public videos) skips that fallback chain for a
            # request that was only ever going to discard the format list anyway.
            "extractor_args": {"youtube": {"player_client": ["android"]}},
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            return ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=False)

    # One worker per id (not capped at 8) - measured timing showed 15 ids capped at 8 workers
    # ran as two sequential waves of ~3.5-4s each (~7.5s total) instead of one, since the 9th+
    # id just waited for a free worker instead of firing immediately. These are all independent,
    # network-bound requests (not CPU work fighting each other) - nothing is gained by throttling
    # below "however many ids there are" for a one-off, user-initiated search action, and `limit`
    # already keeps this number small (15 by default).
    with ThreadPoolExecutor(max_workers=len(video_ids)) as pool:
        infos = list(pool.map(fetch, video_ids))
    t2 = time.monotonic()
    print(f"[timing] search(): id listing={t1 - t0:.2f}s, {len(video_ids)} full fetches={t2 - t1:.2f}s (total={t2 - t0:.2f}s)")
    return [_entry_from_info(info) for info in infos if info]


def _search_song_ids(query, limit):
    """Just the ids - the Songs tab's own flat listing also hands back a title per entry, but
    it's never used: [search] re-fetches every id in full anyway (see its own doc for why), and
    that full fetch's title is the one actually returned."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": "in_playlist",
        "skip_download": True,
        "noplaylist": True,
        "playlistend": int(limit),
    }
    url = f"https://music.youtube.com/search?q={quote(query)}#songs"
    with yt_dlp.YoutubeDL(opts) as ydl:
        result = ydl.extract_info(url, download=False)
    entries = (result or {}).get("entries") or []
    return [e["id"] for e in entries if e and e.get("id")][:int(limit)]


def _thumbnail_of(entry):
    thumb = entry.get("thumbnail")
    if thumb:
        return thumb
    thumbs = entry.get("thumbnails") or []
    return thumbs[-1]["url"] if thumbs else None


def _fetch_container_metadata(url):
    """A playlist/channel page's OWN title/uploader/thumbnail, still via flat extraction - that
    top-level metadata is resolved as part of loading the page itself, independent of how many
    of its own tracks get processed, so staying flat here (playlistend=1, never enumerating the
    whole track list) keeps this about as cheap as [_flat_search_entries] itself rather than
    paying [search]'s full per-video cost for something that isn't even a video."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": "in_playlist",
        "skip_download": True,
        "playlistend": 1,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        return ydl.extract_info(url, download=False)


def _normalize_playlist_id(raw_id):
    if raw_id.startswith(("PL", "OLAK5uy_", "RD", "VL", "UC", "FL")):
        return raw_id
    if len(raw_id) == 11:  # bare video id
        return f"RD{raw_id}"
    return raw_id


def fetch_playlist_metadata(url):
    """Resolves a pasted YouTube/YouTube Music playlist URL into its id, title, uploader and
    thumbnail - backs Discovery's "Import playlist by URL" feature. Same flat, cheap container
    fetch this module always used for a playlist/channel's own page (see
    _fetch_container_metadata) - yt-dlp handles parsing whatever URL shape the user pasted
    (youtube.com or music.youtube.com, ?list=... or a bare playlist link) into a real id itself.
    """
    try:
        info = _fetch_container_metadata(url)
    except Exception:
        return None
    if not info:
        return None
    raw_id = info.get("id") or ""
    playlist_id = _normalize_playlist_id(raw_id) if raw_id else ""
    if not playlist_id:
        return None
    return {
        "id": playlist_id,
        "title": info.get("title") or "Imported playlist",
        "subtitle": info.get("uploader") or info.get("channel"),
        "thumbnail": _thumbnail_of(info),
    }


def download(video_id, dest_dir, dest_filename_stem, format_selector="bestaudio/best"):
    """Downloads [video_id]'s audio-only stream matching [format_selector] (yt-dlp's own
    selector syntax - see DownloadQuality.kt for the presets this app actually offers) into
    [dest_dir] as [dest_filename_stem].<real extension> - no transcoding/merging (no ffmpeg
    involved), so the file on disk is whatever container YouTube actually served (m4a/webm/opus),
    all of which Media3 plays natively. Returns the real metadata plus the file's actual path.
    """
    os.makedirs(dest_dir, exist_ok=True)
    out_template = os.path.join(dest_dir, dest_filename_stem + ".%(ext)s")
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": format_selector,
        "outtmpl": out_template,
        "noplaylist": True,
        "noprogress": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=True)
    filepath = ydl.prepare_filename(info)
    filepath = _fix_audio_only_extension(filepath)
    entry = _entry_from_info(info)
    entry["path"] = filepath
    return entry


def resolve_stream_url(video_id, format_selector="bestaudio/best"):
    """Resolves [video_id]'s direct, playable audio stream URL for [format_selector] WITHOUT
    downloading anything to disk - backs the "Play" button (stream it, keep nothing) as opposed
    to [download] above (saved permanently to [dest_dir]). Uses android/ios player clients
    to obtain unthrottled streaming URLs (10+ MB/s) instead of web/tv clients which suffer from
    YouTube CDN n-parameter throttling (30 KB/s).
    """
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": format_selector,
        "noplaylist": True,
        "skip_download": True,
        "extractor_args": {"youtube": {"player_client": ["android", "ios"]}},
    }
    # Light first: one client, and no watch page / configs / player JS - the android client's
    # URLs need no deciphering, so those are pure overhead, and parsing them in Python was most
    # of the ~8 s of CPU each song start cost. Anything that fails light falls back to the full
    # extraction above, so nothing that played before stops playing.
    light = dict(opts)
    light["extractor_args"] = {"youtube": {"player_client": ["android"], "player_skip": ["webpage", "configs", "js"]}}
    url = f"https://music.youtube.com/watch?v={video_id}"
    t0 = time.monotonic()
    mode = "light"
    try:
        with yt_dlp.YoutubeDL(light) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info or not info.get("url"):
            raise ValueError("no stream url")
    except Exception:
        mode = "full"
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    print(f"[timing] resolve_stream_url({video_id}) [{mode}]: {time.monotonic() - t0:.2f}s")
    entry = _entry_from_info(info)
    entry["url"] = info.get("url")
    return entry


def _fix_audio_only_extension(filepath):
    """YouTube's Opus audio-only stream is packaged in a WebM container - a real container, just
    the same one video uses, and Android's default extension->MIME mapping treats a bare .webm
    as video (it's historically a video format that can ALSO hold audio-only), so a downloaded
    track showed up in Gallery/MediaStore as a video despite having no video track at all.
    ".weba" is the real, standard extension for "WebM Audio" that Android maps to audio/webm
    specifically - renaming to it fixes the classification with zero re-encoding (same bytes,
    no ffmpeg involved), and playback is unaffected either way: Media3's extractors sniff the
    actual container format from its bytes, not the file's extension.
    """
    if not filepath.lower().endswith(".webm"):
        return filepath
    new_path = filepath[: -len(".webm")] + ".weba"
    os.replace(filepath, new_path)
    return new_path
