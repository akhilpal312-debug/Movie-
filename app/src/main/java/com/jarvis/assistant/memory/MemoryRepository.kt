package com.jarvis.assistant.memory

import com.jarvis.assistant.database.MemoryDao
import com.jarvis.assistant.database.MemoryEntity
import com.jarvis.assistant.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryItem(val id: Long, val text: String, val createdAt: Long)

/**
 * Local memory. Everything is encrypted with a Keystore-backed key before it is
 * written, is never included in a Gemini request, and can be removed by the user.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: MemoryDao,
    private val cipher: SecretCipher,
) {
    val memories: Flow<List<MemoryItem>> = dao.observeAll().map { entities ->
        entities.mapNotNull { entity ->
            cipher.decrypt(entity.cipherText)?.let { MemoryItem(entity.id, it, entity.createdAt) }
        }
    }

    suspend fun remember(text: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            MemoryEntity(cipherText = cipher.encrypt(text.trim()), createdAt = now, updatedAt = now),
        )
    }

    suspend fun search(query: String): List<MemoryItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return dao.snapshot().mapNotNull { entity ->
            val text = cipher.decrypt(entity.cipherText) ?: return@mapNotNull null
            if (text.lowercase().contains(needle)) MemoryItem(entity.id, text, entity.createdAt) else null
        }
    }

    suspend fun update(id: Long, text: String) {
        val existing = dao.snapshot().firstOrNull { it.id == id } ?: return
        dao.update(
            existing.copy(
                cipherText = cipher.encrypt(text.trim()),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clear()
}
