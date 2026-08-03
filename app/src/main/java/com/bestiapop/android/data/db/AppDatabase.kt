package com.bestiapop.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bestiapop_music_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
