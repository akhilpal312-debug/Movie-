package com.jarvis.assistant.automation

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routines are scheduled with WorkManager, which persists across process death and
 * reboot and respects Android's background limits. A daily periodic request is
 * created per enabled routine, with the first run aligned to the next occurrence of
 * the requested time.
 */
@Singleton
class AutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(routine: Routine) {
        if (!routine.enabled) {
            cancel(routine.id)
            return
        }
        val request = PeriodicWorkRequestBuilder<RoutineWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(minutesUntilNext(routine.hour, routine.minute), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    RoutineWorker.KEY_ROUTINE_ID to routine.id,
                    RoutineWorker.KEY_ROUTINE_NAME to routine.name,
                ),
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(routine.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(routineId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(routineId))
    }

    /** Re-enqueues every routine; safe to call on boot or after a data change. */
    fun scheduleAll(routines: List<Routine>) {
        routines.forEach(::schedule)
    }

    /** Minutes from now until the next [hour]:[minute], never more than 24 hours. */
    fun minutesUntilNext(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return (calendar.timeInMillis - now).coerceAtLeast(1_000L) / 60_000L
    }

    private fun workName(routineId: Long) = "jarvis_routine_$routineId"

    companion object {
        const val TAG = "jarvis_routine"
    }
}
