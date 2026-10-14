package com.abn3li.telemusic.ui.library

import android.app.Application
import android.content.Context
import com.abn3li.telemusic.data.local.SongEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryEditingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val songs = (1L..4L).map { SongEntity(it, 0, "Song $it", "Artist") }
    @Before fun clearOrders() { context.getSharedPreferences("library_orders", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun savedOrderSurvivesReopeningAndIsIsolatedPerCollection() {
        val telegram = LibraryEditing(context, "smart:TELEGRAM")
        telegram.beginReorder(songs)
        telegram.move(3, 1)
        telegram.finish()
        assertEquals(listOf(3L, 1L, 2L, 4L), LibraryEditing(context, "smart:TELEGRAM").ordered(songs).map { it.telegramMessageId })
        assertEquals(songs, LibraryEditing(context, "smart:LIKED").ordered(songs))
        assertEquals(songs, LibraryEditing(context, "playlist:12").ordered(songs))
    }

    @Test fun cancelAndInvalidMovesLeaveSavedOrderUntouched() {
        val edit = LibraryEditing(context, "playlist:1")
        edit.beginReorder(songs); edit.move(4, 1); edit.finish()
        edit.beginReorder(songs); edit.move(2, 4); edit.move(99, 1); edit.cancel()
        assertEquals(listOf(4L, 1L, 2L, 3L), edit.ordered(songs).map { it.telegramMessageId })
        assertEquals(edit.ordered(songs), LibraryEditing(context, "playlist:1").ordered(songs))
    }

    @Test fun libraryChangesKeepSurvivorsAndAppendNewSongsWithoutRevivingDeletedOnes() {
        val changed = listOf(songs[1], SongEntity(5, 0, "New", "Artist"), songs[3])
        assertEquals(listOf(4L, 2L, 5L), applyLibraryOrder(changed, listOf(3L, 1L, 4L, 2L)).map { it.telegramMessageId })
        assertEquals(emptyList<SongEntity>(), applyLibraryOrder(emptyList(), listOf(1)))
    }

    @Test fun changingSortKeepsTheCustomOrderAvailableForLater() {
        val edit = LibraryEditing(context, "smart:DOWNLOADED")
        edit.beginReorder(songs); edit.move(4, 1); edit.finish(); edit.resetOrder()
        assertEquals(songs, LibraryEditing(context, "smart:DOWNLOADED").ordered(songs))
        val reopened = LibraryEditing(context, "smart:DOWNLOADED")
        assertFalse(reopened.isCustom)
        reopened.useCustomOrder()
        assertEquals(listOf(4L, 1L, 2L, 3L), reopened.ordered(songs).map { it.telegramMessageId })
        assertTrue(LibraryEditing(context, "smart:DOWNLOADED").isCustom)
    }

    @Test fun liveMembershipChangesKeepUserMovesAndCancelLeavesSavedOrderIntact() {
        val edit = LibraryEditing(context, "playlist:live")
        edit.beginReorder(songs); edit.move(4, 1); edit.finish()
        edit.beginReorder(songs); edit.move(3, 1)
        edit.draggingId = 3L
        val changed = listOf(songs[0], songs[2], songs[3], SongEntity(5, 0, "New", "Artist"))
        assertTrue(edit.reconcileReorder(changed))
        assertEquals(listOf(4L, 3L, 1L, 5L), edit.draft)
        assertEquals(3L, edit.draggingId)
        assertFalse(edit.reconcileReorder(changed.reversed()))
        edit.cancel()
        assertEquals(listOf(4L, 1L, 2L, 3L), edit.order)
    }
}
