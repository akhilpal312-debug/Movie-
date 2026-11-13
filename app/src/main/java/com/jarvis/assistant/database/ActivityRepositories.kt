package com.jarvis.assistant.database

import com.jarvis.assistant.security.AuditRecord
import com.jarvis.assistant.security.AuditSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryItem(
    val id: Long,
    val at: Long,
    val actionType: String,
    val source: String,
    val status: String,
    val summary: String,
)

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    val recent: Flow<List<HistoryItem>> = dao.observeRecent().map { entities ->
        entities.map { HistoryItem(it.id, it.at, it.actionType, it.source, it.status, it.summary) }
    }

    suspend fun record(status: String, actionType: String, source: String, summary: String, at: Long) {
        dao.insert(
            HistoryEntity(
                at = at,
                actionType = actionType,
                source = source,
                status = status,
                summary = summary.take(200),
            ),
        )
    }

    suspend fun clear() = dao.clear()
}

/**
 * Persists every privacy-core decision. This is the evidence behind the privacy
 * dashboard and is intentionally kept on device only.
 */
data class AuditItem(
    val id: Long,
    val at: Long,
    val actionType: String,
    val source: String,
    val decision: String,
    val detail: String,
)

@Singleton
class AuditRepository @Inject constructor(private val dao: AuditDao) {
    val recent: Flow<List<AuditItem>> = dao.observeRecent().map { entities ->
        entities.map { AuditItem(it.id, it.at, it.actionType, it.source, it.decision, it.detail) }
    }

    val denialCount: Flow<Int> = dao.observeDenialCount()

    suspend fun clear() = dao.clear()
}

@Singleton
class LocalAuditSink @Inject constructor(private val dao: AuditDao) : AuditSink {
    override suspend fun record(record: AuditRecord) {
        dao.insert(
            AuditEntity(
                at = record.at,
                actionType = record.actionType.wire,
                source = record.source.name,
                decision = record.decision,
                detail = record.detail.take(300),
            ),
        )
    }
}
