package com.abn3li.telemusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.abn3li.telemusic.data.download.MediaFolderExporter
import com.abn3li.telemusic.data.download.YtDlpRepository
import com.abn3li.telemusic.data.local.AppDatabase
import com.abn3li.telemusic.data.settings.AppSettingsStore
import com.abn3li.telemusic.data.telegram.TdlibManager
import com.abn3li.telemusic.data.telegram.TelegramCredentialsStore
import com.abn3li.telemusic.playback.PlaybackController
import com.abn3li.telemusic.playback.PlaybackQueue
import com.abn3li.telemusic.repository.LocalAudioImporter
import com.abn3li.telemusic.repository.LyricsRepository
import com.abn3li.telemusic.repository.MetadataRepository
import com.abn3li.telemusic.repository.MusicRepository
import com.abn3li.telemusic.repository.ThumbnailGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TgMusicApp : Application(), ImageLoaderFactory {
    lateinit var tdlibManager: TdlibManager; private set
    lateinit var musicRepository: MusicRepository; private set
    lateinit var spotifyImporter: com.abn3li.telemusic.repository.SpotifyImporter; private set
    lateinit var downloadGate: com.abn3li.telemusic.data.settings.DownloadLocationGate; private set
    lateinit var credentialsStore: TelegramCredentialsStore; private set
    lateinit var settingsStore: AppSettingsStore; private set
    lateinit var playbackQueue: PlaybackQueue; private set
    lateinit var playbackController: PlaybackController; private set
    lateinit var ytDlpRepository: YtDlpRepository; private set
    lateinit var discoveryRepository: com.abn3li.telemusic.repository.DiscoveryRepository; private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        credentialsStore = TelegramCredentialsStore(this)
        settingsStore = AppSettingsStore(this)
        downloadGate = com.abn3li.telemusic.data.settings.DownloadLocationGate(settingsStore)
        tdlibManager = TdlibManager(this)
        playbackQueue = PlaybackQueue()
        playbackController = PlaybackController(this)
        playbackController.connect(onReady = {})
        ytDlpRepository = YtDlpRepository(this)
        discoveryRepository = com.abn3li.telemusic.repository.DiscoveryRepository(db.importedPlaylistDao())

        musicRepository = MusicRepository(
            songDao = db.songDao(), playlistDao = db.playlistDao(), tdlibManager = tdlibManager,
            lyricsRepository = LyricsRepository(), metadataRepository = MetadataRepository(),
            settingsStore = settingsStore, thumbnailGenerator = ThumbnailGenerator(this),
            localAudioImporter = LocalAudioImporter(this),
            mediaFolderExporter = MediaFolderExporter(this),
            ytDlpRepository = ytDlpRepository,
            context = this
        )
        spotifyImporter = com.abn3li.telemusic.repository.SpotifyImporter(musicRepository, appScope)
        if (credentialsStore.hasCredentials()) {
            tdlibManager.start(
                apiId = credentialsStore.getApiId(),
                apiHash = credentialsStore.getApiHash(),
                initialProxy = settingsStore.proxySettings,
                dnsResolver = settingsStore.dnsResolver,
                customDnsIps = settingsStore.customDnsIps
            )
        }

        // One-shot catch-up for songs enriched before the thumbnail cache existed - runs once
        // in the background at startup rather than blocking the Library screen's first load.
        appScope.launch { musicRepository.backfillThumbnails() }
        appScope.launch { musicRepository.normalizeArtistCredits() }
        appScope.launch { musicRepository.reconcileOrphanedTdlibFiles() }
        appScope.launch { musicRepository.migrateDownloadsToSingleCopy() }
        appScope.launch { musicRepository.upgradeYouTubeArtwork() }
    }

    /**
     * Runs enrichMissingMetadata() on this Application-scoped coroutine rather than whatever
     * screen triggered it - it makes several sequential network calls per unenriched song, and
     * a plain rememberCoroutineScope() (tied to that screen's own composition) would get
     * cancelled the moment the user navigates away before it finishes, silently leaving newly
     * imported songs without artwork. This is exactly the failure mode SyncService's own doc
     * describes for channel sync, just for local-import artwork instead.
     */
    fun enrichLibraryInBackground() {
        appScope.launch { musicRepository.enrichMissingMetadata() }
    }

    /**
     * Runs importLocalSongs() on this Application-scoped coroutine for the exact same reason
     * enrichLibraryInBackground() above does - it copies every selected file's bytes into the
     * app's own storage one at a time (see LocalAudioImporter.importToPrivateStorage's own doc),
     * which for a large folder is easily slow enough to still be running after the user taps
     * Import and moves on. This was previously launched on the Settings screen's own
     * rememberCoroutineScope(), which gets cancelled the moment that screen leaves composition
     * (e.g. switching bottom-nav tabs) - the import silently stopped wherever it happened to be,
     * even though the "Import (N)" button's own count was always correct. That mismatch (a
     * correct count, an incomplete result) is exactly what made it look like some fixed built-in
     * limit, when the real cause was navigating away mid-copy.
     */
    fun importLocalSongsInBackground(files: List<com.abn3li.telemusic.repository.LocalAudioFile>, onImported: () -> Unit) {
        appScope.launch {
            musicRepository.importLocalSongs(files)
            withContext(Dispatchers.Main) { onImported() }
        }
    }

    /**
     * Every album-art thumbnail in the Tracks/Albums lists goes through Coil's default
     * ImageLoader, sized at Coil's default 25% of app memory - with libraries running into the
     * thousands of songs, that's not enough to keep more than a screenful or two of thumbnails
     * cached, so a fast fling keeps re-decoding from disk instead of hitting memory cache.
     * allowRgb565 halves the per-pixel cost of decoding these (opaque) thumbnails, so more of
     * them fit in the same cache budget.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.35).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .build()
}