package com.jarvis.assistant.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A remembered preference or fact. The text is encrypted at rest. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cipherText: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleCipher: String,
    val dueAtMillis: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long,
)

/** A daily routine. Steps are stored encrypted, exactly as the user phrased them. */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hour: Int,
    val minute: Int,
    val stepsCipher: String,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val createdAt: Long,
)

/** Non-sensitive activity log, shown in Activity History. */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val actionType: String,
    val source: String,
    val status: String,
    val summary: String,
)

/** Every privacy-core decision, kept locally for the privacy dashboard. */
@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val actionType: String,
    val source: String,
    val decision: String,
    val detail: String,
)
