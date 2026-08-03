package com.bestiapop.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingListenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(listen: PendingListenEntity): Long

    @Query("SELECT * FROM pending_listens ORDER BY listenedAt ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<PendingListenEntity>

    @Query("DELETE FROM pending_listens WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_listens")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_listens")
    suspend fun count(): Int

    @Query(
        """
        UPDATE pending_listens
        SET attempts = attempts + 1, lastError = :error
        WHERE id IN (:ids)
        """
    )
    suspend fun incrementAttempts(ids: List<Long>, error: String?)

    @Query("DELETE FROM pending_listens WHERE attempts >= :maxAttempts")
    suspend fun deleteExhausted(maxAttempts: Int)
}
