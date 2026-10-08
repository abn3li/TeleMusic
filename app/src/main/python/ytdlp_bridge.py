"""Thin bridge between Kotlin and real yt-dlp (Unlicense/public domain), run through Chaquopy's
bundled Python interpreter. yt-dlp owns extraction and format selection; ranged downloads
verify their responses and retry interrupted requests here. Every function
returns plain dicts/lists (str/int/float/None/dict/list only) since that's what crosses the
Chaquopy Kotlin<->Python boundary cleanly - no custom Python objects.
"""

import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote
import yt_dlp

# What a download saves: YouTube's audio-only Opus stream (48 kHz), else another audio-only
# stream - never a file with video in it (no "/best"), whatever the caller's selector allows.
_DOWNLOAD_AUDIO = "bestaudio[acodec=opus]/bestaudio[vcodec=none]"

# Separators that always mean a collab credit. "&" and "/" are left to the app, which splits on
# them only for an artist already in the library ("AC/DC" and "Simon & Garfunkel" are bands).
_ARTIST_SPLIT = re.compile(r"\s*(?:,| feat\.?| ft\.?| x )\s*", re.IGNORECASE)


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
        # One result that can't be read (age-restricted, blocked in this region, removed) is
        # left out - it must not throw away every other result of the search.
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                return ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=False)
        except Exception as e:
            print(f"[search] skipped {video_id}: {e}")
            return None

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
    """Downloads [video_id]'s stream matching [format_selector] (see DownloadQuality.kt) into
    [dest_dir] as [dest_filename_stem].<real extension> - no transcoding/merging (no ffmpeg
    involved), so the file on disk is whatever container YouTube actually served (m4a/webm/opus),
    all of which Media3 plays natively. Returns the real metadata plus the file's actual path.

    Always audio only (see _DOWNLOAD_AUDIO): a client that answers with only a video file is
    skipped, and if none gives audio the download fails rather than saving a video. The file
    is fetched here in ranges: yt-dlp's own downloader was answered "HTTP 403: Forbidden" for
    links the player streams without trouble.
    """
    os.makedirs(dest_dir, exist_ok=True)
    t0 = time.monotonic()
    format_selector = _DOWNLOAD_AUDIO
    info, mode = _resolve_info(video_id, format_selector, audio_first=True)
    ext = info.get("ext") or "m4a"
    filepath = os.path.join(dest_dir, f"{dest_filename_stem}.{ext}")
    def refresh_link():
        refreshed, _ = _resolve_info(video_id, format_selector, audio_first=True)
        return refreshed

    _fetch_to_file(info, filepath, refresh=refresh_link)
    print(f"[timing] download({video_id}) [{mode}]: {time.monotonic() - t0:.2f}s")
    filepath = _fix_audio_only_extension(filepath)
    entry = _entry_from_info(info)
    entry["path"] = filepath
    return entry


def _fetch_to_file(info, filepath, chunk=4 * 1024 * 1024, refresh=None):
    """Verify each range and resume interrupted requests at the last committed offset.
    Three requests per chunk, plus one if its last try refreshes the link. Refresh only once
    per download; failed files are never published."""
    import http.client
    import urllib.error
    import urllib.request
    temp = filepath + ".part"
    total = int(info.get("filesize") or 0) or None
    done = 0
    refreshed = False
    identity = (info.get("format_id"), info.get("ext"))
    validator = None
    try:
        with open(temp, "wb") as out:
            while total is None or done < total:
                # Commit only a verified chunk. A disconnected request retries this offset,
                # preserving earlier chunks without duplicating the failed response's bytes.
                attempt_limit = 3
                for attempt in range(4):
                    headers = dict(info.get("http_headers") or {})
                    end = min(done + chunk - 1, total - 1) if total else done + chunk - 1
                    headers["Range"] = f"bytes={done}-{end}"
                    headers["Accept-Encoding"] = "identity"
                    try:
                        request = urllib.request.Request(info["url"], headers=headers)
                        with urllib.request.urlopen(request, timeout=30) as response:
                            if response.status == 206:
                                match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)",
                                    response.headers.get("Content-Range", ""))
                                if not match:
                                    raise ValueError("Missing or invalid download range")
                                start, last, size = map(int, match.groups())
                                if start != done or last < start or last > end or size <= last:
                                    raise ValueError("Server returned the wrong download range")
                                if total is not None and total != size:
                                    raise ValueError("Download size changed")
                                total = size
                                etag = response.headers.get("ETag")
                                if validator and etag and validator != etag:
                                    raise ValueError("Download content changed")
                                validator = validator or etag
                                expected = last - start + 1
                                length = response.headers.get("Content-Length")
                                if length is not None and int(length) != expected:
                                    raise ValueError("Download range length disagrees")
                                data = response.read(expected + 1)
                                if len(data) != expected:
                                    raise OSError("Download chunk ended early")
                                out.write(data)
                                done += len(data)
                            elif response.status == 200 and done == 0:
                                # Some hosts ignore Range. Accept a complete, sized response
                                # only at offset zero, streaming it without loading it all in RAM.
                                size = int(response.headers.get("Content-Length") or 0)
                                if size <= 0 or total is not None and total != size:
                                    raise ValueError("Full download has no reliable size")
                                total = size
                                while done < total:
                                    data = response.read(min(chunk, total - done))
                                    if not data:
                                        raise OSError("Full download ended early")
                                    out.write(data)
                                    done += len(data)
                            else:
                                raise ValueError("Server did not honor the download range")
                        break
                    except urllib.error.HTTPError as error:
                        code = error.code
                        error.close()
                        if code in (401, 403, 410) and refresh is not None and not refreshed:
                            refreshed = True
                            fresh = refresh()
                            # An audio prefix cannot be joined to another format or recording.
                            if (fresh.get("format_id"), fresh.get("ext")) != identity or (
                                    done > 0 and not identity[0]):
                                raise ValueError("Refreshed download uses a different format")
                            fresh_size = int(fresh.get("filesize") or 0) or None
                            if fresh_size and total and fresh_size != total:
                                raise ValueError("Refreshed download size changed")
                            info = fresh
                            # The final retry must actually use the link we just resolved.
                            if attempt == 2:
                                attempt_limit = 4
                        elif code not in (408, 429, 500, 502, 503, 504):
                            raise
                        if attempt + 1 >= attempt_limit:
                            raise OSError("Download retry limit reached") from error
                        time.sleep(0.25 * (attempt + 1))
                    except (OSError, http.client.HTTPException) as error:
                        if attempt + 1 >= attempt_limit:
                            raise OSError("Download retry limit reached") from error
                        time.sleep(0.25 * (attempt + 1))
            if total is None or done != total:
                raise OSError("Download is incomplete")
        os.replace(temp, filepath)
    except Exception:
        if os.path.exists(temp):
            os.remove(temp)
        raise


def _resolve_info(video_id, format_selector, audio_first=False):
    """[video_id]'s stream for [format_selector], from the first client that gives a link that
    works, and which client it was. Play tries the light android client first (fastest to
    start) and accepts a video file (it plays as audio). Download ([audio_first]) tries
    yt-dlp's default clients first and accepts only an audio-only stream."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": format_selector,
        "noplaylist": True,
        "skip_download": True,
        "extractor_args": {"youtube": {"player_client": ["android", "ios"]}},
    }
    # Light: one client, and no watch page / configs / player JS - the android client's URLs
    # need no deciphering, so those are pure overhead, and parsing them in Python was most of
    # the ~8 s of CPU each song start cost.
    light = dict(opts)
    light["extractor_args"] = {"youtube": {"player_client": ["android"], "player_skip": ["webpage", "configs", "js"]}}
    # yt-dlp's own default client choice: real audio-only streams. YouTube now answers the
    # android/ios clients with only a video file (format 18), so "full" (android + ios) comes
    # last - it fails the same way "light" does.
    defaults = {k: v for k, v in opts.items() if k != "extractor_args"}
    order = (("default", defaults), ("light", light), ("full", opts)) if audio_first \
        else (("light", light), ("default", defaults), ("full", opts))
    url = f"https://music.youtube.com/watch?v={video_id}"
    last_error = None
    failures = []
    for mode, attempt in order:
        try:
            with yt_dlp.YoutubeDL(attempt) as ydl:
                info = ydl.extract_info(url, download=False)
            if info and info.get("url"):
                audio_only = info.get("vcodec") in (None, "none")
                if audio_only:
                    return info, mode
                if audio_first:
                    last_error = ValueError("only a video file offered")
                # A video file's link from those clients is sometimes throttled to a few KB/s (the
                # player then sat buffering at 0:00): its speed is tried once, briefly, first.
                elif _link_is_fast(info):
                    return info, mode
                else:
                    last_error = ValueError("video link too slow")
            else:
                last_error = ValueError("no stream url")
        except Exception as e:
            last_error = e
        failures.append(f"{mode}: {str(last_error).replace('ERROR: ', '').strip()[:220]}")
    raise Exception(" | ".join(failures)) from last_error


def resolve_stream_url(video_id, format_selector="bestaudio/best"):
    """Resolves [video_id]'s direct, playable audio stream URL for [format_selector] WITHOUT
    downloading anything to disk - backs the "Play" button (stream it, keep nothing) as opposed
    to [download] above (saved permanently to [dest_dir])."""
    t0 = time.monotonic()
    info, mode = _resolve_info(video_id, format_selector)
    print(f"[timing] resolve_stream_url({video_id}) [{mode}]: {time.monotonic() - t0:.2f}s")
    entry = _entry_from_info(info)
    entry["url"] = info.get("url")
    return entry


def _link_is_fast(info, sample=256 * 1024, within=1.5):
    """True when [info]'s stream link delivers its first [sample] bytes within [within] seconds.
    Some of YouTube's video links (android/ios clients) are throttled to a few KB/s - far too
    slow to ever start playing - while a good one does this in a few hundredths of a second."""
    import urllib.request
    headers = dict(info.get("http_headers") or {})
    headers["Range"] = f"bytes=0-{sample - 1}"
    start = time.monotonic()
    received = 0
    try:
        with urllib.request.urlopen(urllib.request.Request(info["url"], headers=headers), timeout=within) as response:
            while received < sample and time.monotonic() - start < within:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                received += len(chunk)
    except Exception:
        return False
    return received >= sample or (0 < received and time.monotonic() - start < within)


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
