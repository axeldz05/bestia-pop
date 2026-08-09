package com.bestiapop.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bestiapop.android.data.network.ListenPayload

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

fun PendingListenEntity.toPayload() = ListenPayload(
    listenedAt = listenedAt,
    trackName = trackName,
    artistName = artistName,
    releaseName = releaseName,
    durationMs = durationMs
)

fun ListenPayload.toEntity(
    id: Long = 0,
    createdAt: Long = System.currentTimeMillis(),
    attempts: Int = 0,
    lastError: String? = null
) = PendingListenEntity(
    id = id,
    listenedAt = listenedAt,
    trackName = trackName,
    artistName = artistName,
    releaseName = releaseName,
    durationMs = durationMs,
    createdAt = createdAt,
    attempts = attempts,
    lastError = lastError
)
