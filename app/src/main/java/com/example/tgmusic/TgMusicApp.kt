package com.example.tgmusic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.example.tgmusic.data.local.AppDatabase
import com.example.tgmusic.data.settings.AppSettingsStore
import com.example.tgmusic.data.telegram.TdlibManager
import com.example.tgmusic.data.telegram.TelegramCredentialsStore
import com.example.tgmusic.playback.PlaybackController
import com.example.tgmusic.playback.PlaybackQueue
import com.example.tgmusic.repository.LyricsRepository
import com.example.tgmusic.repository.MetadataRepository
import com.example.tgmusic.repository.MusicRepository
import com.example.tgmusic.repository.ThumbnailGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TgMusicApp : Application(), ImageLoaderFactory {
    lateinit var tdlibManager: TdlibManager; private set
    lateinit var musicRepository: MusicRepository; private set
    lateinit var credentialsStore: TelegramCredentialsStore; private set
    lateinit var settingsStore: AppSettingsStore; private set
    lateinit var playbackQueue: PlaybackQueue; private set
    lateinit var playbackController: PlaybackController; private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        credentialsStore = TelegramCredentialsStore(this)
        settingsStore = AppSettingsStore(this)
        tdlibManager = TdlibManager(this)
        playbackQueue = PlaybackQueue()
        playbackController = PlaybackController(this)
        playbackController.connect(onReady = {})

        musicRepository = MusicRepository(
            songDao = db.songDao(), playlistDao = db.playlistDao(), tdlibManager = tdlibManager,
            lyricsRepository = LyricsRepository(), metadataRepository = MetadataRepository(),
            settingsStore = settingsStore, thumbnailGenerator = ThumbnailGenerator(this)
        )
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
        .allowRgb565(true)
        .build()
}