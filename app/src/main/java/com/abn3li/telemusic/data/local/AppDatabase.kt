package com.abn3li.telemusic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tgmusic.db")
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration() // fine during active development, for any version this migration chain doesn't cover
                    .build().also { INSTANCE = it }
            }
    }
}
