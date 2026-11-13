package com.jarvis.assistant.memory

import com.jarvis.assistant.database.TaskDao
import com.jarvis.assistant.database.TaskEntity
import com.jarvis.assistant.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class TaskItem(
    val id: Long,
    val title: String,
    val dueAtMillis: Long?,
    val completed: Boolean,
)

/**
 * Tasks live only on the device. Completing a task removes it from the active list
 * (the record is retained so history stays truthful) and this is also what the
 * automation engine reads for daily summaries.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val dao: TaskDao,
    private val cipher: SecretCipher,
) {
    val activeTasks: Flow<List<TaskItem>> = dao.observeActive().map { entities -> entities.toItems() }

    val completedTasks: Flow<List<TaskItem>> = dao.observeCompleted().map { entities -> entities.toItems() }

    suspend fun add(title: String, dueAtMillis: Long? = null): Long = dao.insert(
        TaskEntity(
            titleCipher = cipher.encrypt(title.trim()),
            dueAtMillis = dueAtMillis,
            createdAt = System.currentTimeMillis(),
        ),
    )

    /** Marks the first active task whose title matches [query] as done. */
    suspend fun complete(query: String): String? {
        val needle = query.trim().lowercase()
        val match = dao.activeSnapshot().firstOrNull { entity ->
            cipher.decrypt(entity.titleCipher)?.lowercase()?.contains(needle) == true
        } ?: return null
        dao.markComplete(match.id, System.currentTimeMillis())
        return cipher.decrypt(match.titleCipher)
    }

    suspend fun activeTitles(): List<String> =
        dao.activeSnapshot().mapNotNull { cipher.decrypt(it.titleCipher) }

    suspend fun clearCompleted() = dao.clearCompleted()

    suspend fun clearAll() {
        dao.clearCompleted()
        // Active tasks are intentionally left for the user to dismiss one by one.
    }

    private fun List<TaskEntity>.toItems(): List<TaskItem> = mapNotNull { entity ->
        val title = cipher.decrypt(entity.titleCipher) ?: return@mapNotNull null
        TaskItem(entity.id, title, entity.dueAtMillis, entity.completedAt != null)
    }
}
