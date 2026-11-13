package com.jarvis.assistant.automation

import com.jarvis.assistant.database.RoutineDao
import com.jarvis.assistant.database.RoutineEntity
import com.jarvis.assistant.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class Routine(
    val id: Long,
    val name: String,
    val hour: Int,
    val minute: Int,
    val steps: List<String>,
    val enabled: Boolean,
    val lastRunAt: Long?,
)

@Singleton
class RoutineRepository @Inject constructor(
    private val dao: RoutineDao,
    private val cipher: SecretCipher,
) {
    val routines: Flow<List<Routine>> = dao.observeAll().map { entities -> entities.mapNotNull(::toRoutine) }

    suspend fun create(name: String, hour: Int, minute: Int, steps: List<String>): Long = dao.insert(
        RoutineEntity(
            name = name.trim().take(60),
            hour = hour,
            minute = minute,
            stepsCipher = cipher.encrypt(steps.joinToString("\u001F")),
            createdAt = System.currentTimeMillis(),
        ),
    )

    suspend fun findByName(name: String): Routine? = dao.findByName(name.trim())?.let(::toRoutine)

    suspend fun enabled(): List<Routine> = dao.enabled().mapNotNull(::toRoutine)

    suspend fun routinesSnapshot(): List<Routine> = dao.snapshot().mapNotNull(::toRoutine)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun markRun(id: Long, at: Long) = dao.markRun(id, at)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clearAll()

    private fun toRoutine(entity: RoutineEntity): Routine? {
        val decrypted = cipher.decrypt(entity.stepsCipher) ?: return null
        val steps = decrypted.split('\u001F').filter { it.isNotBlank() }
        return Routine(entity.id, entity.name, entity.hour, entity.minute, steps, entity.enabled, entity.lastRunAt)
    }
}
