package com.abn3li.telemusic.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import com.abn3li.telemusic.data.telegram.TdlibManager

/**
 * Routes each DataSpec to the right underlying DataSource by URI scheme: tdlib:// and local
 * file:// go to [TdlibDataSource] (this app's own Telegram-streaming source), and plain
 * http(s):// - a resolved YouTube stream URL from the "Play" button (see
 * YouTubeDownloadViewModel.onPlayClick) - goes to a real [DefaultHttpDataSource].
 *
 * DefaultDataSource.Factory only ever takes ONE "base" factory for every scheme it doesn't
 * already special-case itself (file/asset/content/rawresource) - tdlib:// and http(s):// both
 * fell into that same "base" bucket, so setting TdlibDataSource as the base (needed for
 * tdlib://) silently broke http(s) streams too: TdlibDataSource.open() takes its "local file"
 * branch for any URI it can't parse an int fileId out of (a googlevideo.com URL never has one),
 * then tries to open a local file literally named after the URL's own path segment, which never
 * exists - so the stream failed on open() every time and the player just sat there retrying
 * (see PlaybackController's onPlayerError auto-retry) instead of ever actually playing.
 */
class ResolvingDataSource(
    private val tdlibDataSource: TdlibDataSource,
    httpDataSourceFactory: DataSource.Factory
) : DataSource {
    private val httpDataSource: DataSource by lazy { httpDataSourceFactory.createDataSource() }
    private var active: DataSource = tdlibDataSource

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == "http" || dataSpec.uri.scheme == "https") httpDataSource else tdlibDataSource
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri(): Uri? = active.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        // Registered on BOTH, not just whichever is active right now - a MediaSource can call
        // addTransferListener() before the first open() ever picks which one becomes active.
        tdlibDataSource.addTransferListener(transferListener)
        httpDataSource.addTransferListener(transferListener)
    }

    override fun close() = active.close()

    class Factory(private val tdlibManager: TdlibManager) : DataSource.Factory {
        // A real browser User-Agent, not ExoPlayer's own default identifier - a resolved
        // googlevideo.com URL is signed for the client that generated it (yt-dlp, impersonating
        // a browser), and YouTube's CDN can reject or throttle requests from an unrecognized
        // player User-Agent even though the URL's signature itself is still valid.
        private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        override fun createDataSource(): DataSource =
            ResolvingDataSource(TdlibDataSource(tdlibManager), httpDataSourceFactory)
    }
}
