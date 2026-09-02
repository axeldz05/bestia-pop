package com.bestiapop.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Play stamps live off `songs` so last-played writes do not invalidate [com.bestiapop.android.data.db.MusicDao.getAllSongsFlow]. */
@Entity(tableName = "song_play_stats")
data class SongPlayStat(
    @PrimaryKey
    val songId: Long,
    val lastPlayedAt: Long
)
