"""Thin bridge between Kotlin and real yt-dlp (Unlicense/public domain), run through Chaquopy's
bundled Python interpreter. Kept deliberately small - every actual decision (format selection,
throttling/cipher handling, retries) is yt-dlp's own, not reimplemented here. Every function
returns plain dicts/lists (str/int/float/None/dict/list only) since that's what crosses the
Chaquopy Kotlin<->Python boundary cleanly - no custom Python objects.
"""

import os
import re
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


def search(query, limit=15):
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
    video_ids = _search_song_ids(query, limit)
    if not video_ids:
        return []

    def fetch(video_id):
        opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": True}
        with yt_dlp.YoutubeDL(opts) as ydl:
            return ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=False)

    with ThreadPoolExecutor(max_workers=min(8, len(video_ids))) as pool:
        infos = list(pool.map(fetch, video_ids))
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
