package com.jarvis.assistant.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryEntity::class,
        TaskEntity::class,
        RoutineEntity::class,
        HistoryEntity::class,
        AuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun taskDao(): TaskDao
    abstract fun routineDao(): RoutineDao
    abstract fun historyDao(): HistoryDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "jarvis.db"
    }
}
