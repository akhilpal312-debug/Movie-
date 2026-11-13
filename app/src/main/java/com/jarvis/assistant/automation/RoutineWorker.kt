package com.jarvis.assistant.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.assistant.database.HistoryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one scheduled routine. WorkManager guarantees the work survives process death
 * and is retried according to its own policy; failures are recorded locally so the
 * user can see what happened instead of wondering.
 */
class RoutineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val routineId = inputData.getLong(KEY_ROUTINE_ID, -1L)
        if (routineId <= 0L) return@withContext Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AutomationEntryPoint::class.java,
        )
        JarvisForegroundService.begin(applicationContext, inputData.getString(KEY_ROUTINE_NAME))
        return@withContext try {
            val result = entryPoint.routineRunner().runById(routineId)
            entryPoint.historyRepository().record(
                status = if (result is com.jarvis.assistant.core.model.ExecutionResult.Success) "ROUTINE_OK" else "ROUTINE_FAILED",
                actionType = "ROUTINE",
                source = "AUTOMATION",
                summary = inputData.getString(KEY_ROUTINE_NAME).orEmpty().take(120),
                at = System.currentTimeMillis(),
            )
            Result.success()
        } catch (error: Throwable) {
            // Do not retry forever on a broken routine; report instead.
            Result.failure()
        } finally {
            JarvisForegroundService.end(applicationContext)
        }
    }

    companion object {
        const val KEY_ROUTINE_ID = "routine_id"
        const val KEY_ROUTINE_NAME = "routine_name"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutomationEntryPoint {
    fun routineRunner(): RoutineRunner
    fun historyRepository(): HistoryRepository
    fun routineRepository(): RoutineRepository
}
