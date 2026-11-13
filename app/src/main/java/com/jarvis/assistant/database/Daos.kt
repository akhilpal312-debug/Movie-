package com.jarvis.assistant.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories")
    suspend fun snapshot(): List<MemoryEntity>

    @Insert
    suspend fun insert(entity: MemoryEntity): Long

    @Update
    suspend fun update(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks WHERE completedAt IS NULL " +
            "ORDER BY (dueAtMillis IS NULL), dueAtMillis ASC, createdAt DESC",
    )
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE completedAt IS NULL")
    suspend fun activeSnapshot(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT :limit")
    fun observeCompleted(limit: Int = 20): Flow<List<TaskEntity>>

    @Insert
    suspend fun insert(entity: TaskEntity): Long

    @Query("UPDATE tasks SET completedAt = :at WHERE id = :id")
    suspend fun markComplete(id: Long, at: Long)

    @Query("DELETE FROM tasks WHERE completedAt IS NOT NULL")
    suspend fun clearCompleted()

    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY hour ASC, minute ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE enabled = 1")
    suspend fun enabled(): List<RoutineEntity>

    @Query("SELECT * FROM routines")
    suspend fun snapshot(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): RoutineEntity?

    @Insert
    suspend fun insert(entity: RoutineEntity): Long

    @Query("UPDATE routines SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE routines SET lastRunAt = :at WHERE id = :id")
    suspend fun markRun(id: Long, at: Long)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM routines")
    suspend fun clearAll()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditEntity>>

    @Query("SELECT COUNT(*) FROM audit WHERE decision LIKE 'DENY%'")
    fun observeDenialCount(): Flow<Int>

    @Insert
    suspend fun insert(entity: AuditEntity)

    @Query("DELETE FROM audit")
    suspend fun clear()
}
