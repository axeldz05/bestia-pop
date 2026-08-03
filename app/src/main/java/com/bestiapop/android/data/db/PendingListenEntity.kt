package com.bestiapop.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_listens",
    indices = [Index(value = ["listenedAt"])]
)
data class PendingListenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val listenedAt: Long,
    val trackName: String,
    val artistName: String,
    val releaseName: String? = null,
    val durationMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null
)
