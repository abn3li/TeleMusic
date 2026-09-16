package com.abn3li.telemusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class, ImportedPlaylistEntity::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun importedPlaylistDao(): ImportedPlaylistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Adds the list-thumbnail cache column - a plain nullable TEXT column needs no data
        // backfill here, ThumbnailGenerator populates it lazily after this runs, so users don't
        // lose their synced library (downloads, favorites, playlists) just for this.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN thumbnailPath TEXT")
            }
        }

        // Adds the local-import flag for the "import from local storage" feature - defaults to
        // 0 (false) for every existing row, which is correct: nothing synced from Telegram
        // before this existed is a local import.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN isLocalImport INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Adds the exported-copy Uri column for "Delete download" to also remove the shared-
        // storage copy, not just the app-private one - null for every existing row, correct
        // since nothing downloaded before this existed has a tracked export to clean up.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN exportedFileUri TEXT")
            }
        }

        // Adds the imported-playlists table for Discovery's "Import playlist by URL" feature -
        // a brand new table, so there's no existing data to backfill.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS imported_playlists (
                        browseId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        subtitle TEXT,
                        thumbnailUrl TEXT,
                        addedAtMillis INTEGER NOT NULL
                    )
                    """
                )
            }
        }

        // Adds the YouTube video id column - lets "Import to Library" add a real, lightweight
        // library row (streams on demand, downloads separately) instead of forcing a real
        // download for every track just to have something to add to a playlist. Null for every
        // existing row, correct since nothing before this existed is YouTube-sourced-but-not-
        // downloaded.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN youtubeVideoId TEXT")
            }
        }

        // Adds the per-playlist "Hide from tracks" flag - 0 (false, still shown in Tracks) for
        // every existing playlist, correct since nothing before this existed had that toggled.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN hiddenFromTracks INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Adds the resolved-chat-id cache column - lets a song's fresh-file-id lookup go
        // straight to the right chat on every later play instead of re-sweeping every candidate
        // chat each time (see SongEntity.resolvedChatId's own doc). Null for every existing row,
        // correct since nothing has been resolved yet - the very next play of each song runs one
        // sweep same as before, then remembers it from there on.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN resolvedChatId INTEGER")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tgmusic.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration() // fine during active development, for any version this migration chain doesn't cover
                    .build().also { INSTANCE = it }
            }
    }
}
