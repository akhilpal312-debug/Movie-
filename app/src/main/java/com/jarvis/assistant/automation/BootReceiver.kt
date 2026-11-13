package com.jarvis.assistant.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.device.JarvisNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores automation after a reboot or an app update. WorkManager also persists its
 * own queue, but routines are re-enqueued explicitly so a disabled routine stays
 * disabled and an enabled one is never silently lost.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RESCHEDULE_ACTIONS) return

        JarvisNotifications.ensureChannels(context)
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val routines = entryPoint.routineRepository().enabled()
                entryPoint.scheduler().scheduleAll(routines)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun routineRepository(): RoutineRepository
    fun scheduler(): AutomationScheduler
}
