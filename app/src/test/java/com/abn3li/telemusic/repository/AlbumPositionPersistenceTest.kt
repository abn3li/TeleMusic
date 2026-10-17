package com.abn3li.telemusic.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abn3li.telemusic.data.local.AppDatabase
import com.abn3li.telemusic.data.local.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlbumPositionPersistenceTest {
    @Test fun positionsSurviveReopenWithoutChangingFavorites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "album-position-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            db.songDao().upsert(SongEntity(42, 0, "Song", "Artist", isFavorite = true))
            db.songDao().setAlbumPosition(42, 2, 7)
            db.close()
            db = open()
            val song = db.songDao().getById(42)!!
            assertEquals(7, song.trackNumber)
            assertEquals(2, song.discNumber)
            assertTrue(song.isFavorite)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationRetainsExistingSongsAndLeavesNewTagsNullable() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE songs (telegramMessageId INTEGER PRIMARY KEY, title TEXT, isFavorite INTEGER)")
                        db.execSQL("INSERT INTO songs VALUES (42, 'Original', 1)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_11_12.migrate(db)
            db.query("SELECT title, isFavorite, trackNumber, discNumber FROM songs WHERE telegramMessageId = 42").use {
                assertTrue(it.moveToFirst())
                assertEquals("Original", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
            }
        } finally { helper.close() }
    }
}
