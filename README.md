# TeleMusic 🎵

<p align="center">
  <strong>Turn your own private Telegram channel into a full music library</strong><br>
  Built with Jetpack Compose and Material Design 3
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=for-the-badge&logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-100%25-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="MIT License">
</p>

---

<p align="center">
  <img src="https://raw.githubusercontent.com/abn3li/TeleMusic/master/docs/banner.png" alt="TeleMusic banner" width="100%">
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/abn3li/TeleMusic/master/assets/screenshot-home.jpg" alt="Home" width="23%">
  <img src="https://raw.githubusercontent.com/abn3li/TeleMusic/master/assets/screenshot-library.jpg" alt="Library" width="23%">
  <img src="https://raw.githubusercontent.com/abn3li/TeleMusic/master/assets/screenshot-now-playing.jpg" alt="Now Playing" width="23%">
  <img src="https://raw.githubusercontent.com/abn3li/TeleMusic/master/assets/screenshot-lyrics.jpg" alt="Lyrics" width="23%">
</p>

---

## ✨ Features

- **🏠 Home** — shortcuts, pinned playlists, Recently Played, Made for You mixes, Top Artists and picks from YouTube Music
- **📱 Home-screen widgets** — small, medium and large, with a live progress bar, shuffle, repeat, Like and up next
- **🎵 Telegram-native library** — syncs audio from any channel/chat you already belong to, no `@username` guessing
- **📚 Full library UI** — Playlists, Tracks, Albums, Artists, sortable by name/artist/album/date added
- **🔍 YouTube Music Discovery** — real, browsable Home/New Releases/Genres feed for downloading
- **🟢 Spotify import** — paste a Spotify playlist or album link; songs are matched on YouTube Music and can be downloaded
- **📥 Smart playlists** — Liked Songs, Telegram Songs, and Downloaded Songs, built in and always up to date
- **🔄 Update checker** — Settings → About checks GitHub for a newer release
- **🏷️ Auto metadata & artwork** — fills in missing title/artist/album/art via iTunes → Deezer → MusicBrainz
- **🎤 Lyrics** — plain and line-synced, from LRCLIB, KuGou, and lyrics.ovh, with manual search as a fallback
- **▶️ Real playback** — Media3 session with working lock screen, notification, Bluetooth, and Android Auto controls
- **⬇️ Smart caching** — streams instantly, caches locally, with a separate explicit-download option and a size-limited auto-cache
- **🎨 Dynamic Now Playing** — backdrop generated from each track's own artwork colors, swipe to skip, swipe down to dismiss
- **🔒 Private by design** — your own Telegram API credentials, encrypted on-device, no third-party backend
- **🌐 Proxy & DNS options** — MTProto proxy support and a choice of DNS resolvers for restrictive networks

---

## 🛠️ Tech Stack

| Category | Technology |
|---|---|
| **Language** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **Playback** | Media3 (ExoPlayer + MediaSession) |
| **Telegram** | [TDLib](https://github.com/tdlib/td) |
| **Database** | Room |
| **Networking** | Retrofit + OkHttp |
| **Images** | Coil |
| **Async** | Coroutines + Flow |

---

## 🚀 Getting Started

1. Get a free `api_id`/`api_hash` from [my.telegram.org](https://my.telegram.org) → API Development Tools
2. Clone and open in Android Studio, let Gradle sync, then run
3. Enter your credentials on first launch, sign in, and pick the channel to sync

Requires Android 8.0 (API 26) or newer.

---
community support 
https://t.me/telemusicco
---


## 📄 License

MIT — see [LICENSE](LICENSE).
