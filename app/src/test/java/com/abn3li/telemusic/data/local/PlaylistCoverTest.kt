package com.abn3li.telemusic.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abn3li.telemusic.ui.library.autoPlaylistCovers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaylistCoverTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    private suspend fun song(id: Long, art: String?, thumb: String? = null) =
        db.songDao().upsert(SongEntity(id, 0, "Song $id", "Artist", albumArtUrl = art, thumbnailPath = thumb))

    private suspend fun summary(id: Long) = db.playlistDao().observeAllWithArt().first().single { it.id == id }

    @Test fun fourNewestDifferentArtworksMakeTheGrid() = runBlocking {
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "Mix"))
        song(1, "a"); song(2, "b"); song(3, "b"); song(4, "c", thumb = "c-small"); song(5, "d"); song(6, null); song(7, "e")
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L).forEachIndexed { i, id -> db.playlistDao().addSong(PlaylistSongCrossRef(playlist, id, 100L + i)) }
        // Newest first; the repeated "b" counts once, the song without art is skipped, rows prefer the thumbnail.
        assertEquals(listOf("e", "d", "c-small", "b"), summary(playlist).covers)
        // Android Auto gets the newest link, never a file only this app can read.
        assertEquals("e", summary(playlist).artworkUrl)
    }

    @Test fun chosenSongFollowsMergesAndLeavesWithTheSong() = runBlocking {
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "Mine"))
        song(1, "a"); song(2, "b")
        db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 1, 1)); db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 2, 2))
        db.playlistDao().setCover(playlist, 1, null)
        db.playlistDao().moveCoverSong(1, 2)
        assertEquals(listOf("b"), summary(playlist).covers)
        db.playlistDao().clearCoverSong(2)
        assertEquals(null, db.playlistDao().observeById(playlist).first()?.coverSongId)
    }

    @Test fun migrationKeepsPlaylistsWithAutomaticCovers() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, hiddenFromTracks INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO playlists VALUES (7, 'Old', 5, 1)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_12_13.migrate(db)
            db.query("SELECT name, hiddenFromTracks, coverSongId, coverImagePath FROM playlists WHERE id = 7").use {
                assertTrue(it.moveToFirst())
                assertEquals("Old", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
            }
        } finally { helper.close() }
    }

    @Test fun fewerThanFourShowOnlyTheNewest() = runBlocking {
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "Short"))
        song(1, "a"); song(2, "b")
        db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 1, 1)); db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 2, 2))
        assertEquals(listOf("b"), summary(playlist).covers)
        assertTrue(summary(db.playlistDao().insert(PlaylistEntity(name = "Empty"))).covers.isEmpty())
    }

    @Test fun pickedSongThenPhotoReplaceTheAutomaticCover() = runBlocking {
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "Mine"))
        song(1, "a"); song(2, "b")
        db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 1, 1)); db.playlistDao().addSong(PlaylistSongCrossRef(playlist, 2, 2))
        db.playlistDao().setCover(playlist, 1, null)
        assertEquals(listOf("a"), summary(playlist).covers)
        db.playlistDao().setCover(playlist, null, "/covers/7-1.jpg")
        assertEquals(listOf("/covers/7-1-thumb.jpg"), summary(playlist).covers)
        assertEquals(null, summary(playlist).artworkUrl)
        db.playlistDao().setCover(playlist, null, null)
        assertEquals(listOf("b"), summary(playlist).covers)
    }

    @Test fun pageCoverFollowsTheSameRule() {
        fun s(id: Long, art: String?) = SongEntity(id, 0, "Song $id", "Artist", albumArtUrl = art)
        assertEquals(listOf("e", "d", "c", "b"), autoPlaylistCovers(listOf(s(7, "e"), s(5, "d"), s(4, "c"), s(3, "b"), s(2, "b"), s(1, "a"))))
        assertEquals(listOf("b"), autoPlaylistCovers(listOf(s(2, "b"), s(1, null), s(0, "a"))))
    }
}
