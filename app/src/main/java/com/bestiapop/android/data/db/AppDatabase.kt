package com.bestiapop.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bestiapop.android.data.model.AlbumOverride
import com.bestiapop.android.data.model.Song

@Database(
    entities = [
        Song::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlaylistPendingTrackEntity::class,
        PendingListenEntity::class,
        AlbumOverride::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun pendingListenDao(): PendingListenDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM songs WHERE id NOT IN (SELECT MIN(id) FROM songs GROUP BY uriString)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_uriString` ON `songs` (`uriString`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `description` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `coverUri` TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_listens` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `listenedAt` INTEGER NOT NULL,
                        `trackName` TEXT NOT NULL,
                        `artistName` TEXT NOT NULL,
                        `releaseName` TEXT,
                        `durationMs` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastError` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_listens_listenedAt` ON `pending_listens` (`listenedAt`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_pending_tracks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `releaseName` TEXT,
                        `recordingMbid` TEXT,
                        `position` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_pending_tracks_playlistId` ON `playlist_pending_tracks` (`playlistId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_pending_tracks_playlistId_artist_title` ON `playlist_pending_tracks` (`playlistId`, `artist`, `title`)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_overrides` (
                        `albumKey` TEXT NOT NULL PRIMARY KEY,
                        `displayName` TEXT NOT NULL,
                        `artist` TEXT,
                        `genre` TEXT,
                        `year` INTEGER NOT NULL,
                        `artworkUri` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_song_cross_ref_songId` ON `playlist_song_cross_ref` (`songId`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bestiapop_music_db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
