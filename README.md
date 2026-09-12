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

## ✨ Features

- **🎵 Telegram-native library** — syncs audio from any channel/chat you already belong to, no `@username` guessing
- **📚 Full library UI** — Tracks, Favourites, Playlists, Albums, Artists, sortable by name/artist/album/date added
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

## 📄 License

MIT — see [LICENSE](LICENSE).
