package com.example.tgmusic.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * The app manages its own next/previous ordering in [PlaybackQueue] rather than handing
 * ExoPlayer a real multi-item timeline (each song swaps in as the player's only [MediaItem] via
 * PlaybackController.playUri) - so the underlying ExoPlayer instance always reports a
 * single-item timeline, which is exactly the case Media3's own [Player.getAvailableCommands]
 * uses to decide COMMAND_SEEK_TO_NEXT/PREVIOUS are unavailable. The system media notification
 * (and Bluetooth/Android Auto controls) read that availability directly off the player to decide
 * whether to draw a skip button at all - with a single-item timeline they never did.
 *
 * This wraps the real player and answers both halves of that from the app's own queue instead:
 * whether a next/previous song exists ([queueHasNext]/[queueHasPrevious]), forced into
 * [getAvailableCommands] regardless of what the underlying timeline reports, and what actually
 * happens when one of those commands is invoked ([onSeekToNext]/[onSeekToPrevious]) - replacing
 * ExoPlayer's own (here meaningless, single-item) seek-to-next/previous behaviour entirely.
 *
 * Named queueHasNext/queueHasPrevious rather than hasNext/hasPrevious deliberately - [Player]
 * (which this class implements via [ForwardingPlayer]) already declares deprecated member
 * functions with those exact names, and a same-named constructor property here silently
 * resolved to THOSE inherited methods instead of invoking this class's own lambda, which is not
 * an error Kotlin surfaces as anything louder than a deprecation warning on the call sites below.
 */
class QueueAwareForwardingPlayer(
    player: Player,
    private val queueHasNext: () -> Boolean,
    private val queueHasPrevious: () -> Boolean,
    private val onSeekToNext: () -> Unit,
    private val onSeekToPrevious: () -> Unit
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .addIf(Player.COMMAND_SEEK_TO_NEXT, queueHasNext())
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, queueHasNext())
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, queueHasPrevious())
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, queueHasPrevious())
            .build()
    }

    override fun seekToNext() = onSeekToNext()
    override fun seekToNextMediaItem() = onSeekToNext()
    override fun seekToPrevious() = onSeekToPrevious()
    override fun seekToPreviousMediaItem() = onSeekToPrevious()
}
