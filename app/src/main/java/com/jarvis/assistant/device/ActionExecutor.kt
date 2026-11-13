package com.jarvis.assistant.device

import com.jarvis.assistant.ai.LocalCommandParser
import com.jarvis.assistant.automation.AutomationScheduler
import com.jarvis.assistant.automation.RoutineRepository
import com.jarvis.assistant.automation.RoutineRunner
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ActionType
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.PermissionKey
import com.jarvis.assistant.core.model.UserPreferences
import com.jarvis.assistant.database.HistoryRepository
import com.jarvis.assistant.memory.MemoryRepository
import com.jarvis.assistant.memory.TaskRepository
import com.jarvis.assistant.security.ActionRequest
import com.jarvis.assistant.security.ActionSummary
import com.jarvis.assistant.security.JarvisPrivacyCore
import com.jarvis.assistant.security.ParseResult
import com.jarvis.assistant.security.PermissionGate
import com.jarvis.assistant.security.PolicyDecision
import com.jarvis.assistant.settings.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place where a validated action becomes an Android call.
 *
 * Order is fixed and non-negotiable:
 * validate → privacy core → permission → execute → record locally → result.
 */
@Singleton
class ActionExecutor @Inject constructor(
    private val privacyCore: JarvisPrivacyCore,
    private val preferences: PreferencesRepository,
    private val permissionGate: PermissionGate,
    private val appResolver: AppResolver,
    private val settingsController: SettingsController,
    private val audioController: AudioController,
    private val brightnessController: BrightnessController,
    private val systemIntents: SystemIntentsController,
    private val reminderScheduler: ReminderScheduler,
    private val deviceInfo: DeviceInfoProvider,
    private val contacts: ContactsController,
    private val notificationVault: NotificationVault,
    private val tasks: TaskRepository,
    private val memories: MemoryRepository,
    private val routines: RoutineRepository,
    private val routineRunner: RoutineRunner,
    private val automationScheduler: AutomationScheduler,
    private val localParser: LocalCommandParser,
    private val history: HistoryRepository,
) : ActionRunner {

    private val clock: () -> Long = System::currentTimeMillis

    override suspend fun run(
        action: JarvisAction,
        source: ActionSource,
        confirmedFingerprint: String?,
    ): ExecutionResult {
        val prefs = preferences.current()
        val decision = privacyCore.authorize(
            request = ActionRequest(action, source, confirmedFingerprint),
            preferences = prefs,
            gate = permissionGate,
        )

        val result = when (decision) {
            is PolicyDecision.Allow -> execute(action, prefs)
            is PolicyDecision.Confirm -> ExecutionResult.NeedsConfirmation(
                reason = decision.reason,
                preview = decision.preview,
                action = action,
            )
            is PolicyDecision.Deny -> ExecutionResult.Denied(decision.reason, decision.code.name)
        }

        record(action, source, result)
        return result
    }

    private suspend fun execute(action: JarvisAction, prefs: UserPreferences): ExecutionResult =
        when (action) {
            is JarvisAction.OpenApp -> {
                val match = appResolver.resolve(action.appQuery)
                if (match == null) {
                    ExecutionResult.Failed("I could not find an app called \"${action.appQuery}\" on this device.")
                } else if (appResolver.launch(match.packageName)) {
                    ExecutionResult.Success("Opened ${match.label}.")
                } else {
                    ExecutionResult.Failed("${match.label} would not open.")
                }
            }

            is JarvisAction.OpenSettings -> settingsController.open(action.panel)

            is JarvisAction.SetVolume -> audioController.setVolume(action.stream, action.percent)

            is JarvisAction.AdjustVolume -> audioController.adjustVolume(action.stream, action.stepPercent)

            is JarvisAction.SetBrightness -> brightnessController.setBrightness(action.percent)

            is JarvisAction.SetDoNotDisturb -> audioController.setDoNotDisturb(action.enabled)

            is JarvisAction.CreateAlarm -> systemIntents.createAlarm(action.hour, action.minute, action.label)

            is JarvisAction.StartTimer -> systemIntents.startTimer(action.durationSeconds, action.label)

            is JarvisAction.CreateReminder -> when (
                val outcome = reminderScheduler.schedule(action.message, action.triggerAtMillis)
            ) {
                is ScheduleOutcome.Scheduled -> ExecutionResult.Success(
                    "Reminder saved" + if (outcome.exact) "." else ", and Android may deliver it a few minutes late.",
                )
                ScheduleOutcome.InThePast -> ExecutionResult.Denied(
                    "That time has already passed.",
                    "INVALID_TIME",
                )
            }

            is JarvisAction.AddTask -> {
                tasks.add(action.title, action.dueAtMillis)
                ExecutionResult.Success("Added to your task list.")
            }

            is JarvisAction.CompleteTask -> tasks.complete(action.query)?.let { completed ->
                ExecutionResult.Success("Marked \"$completed\" as done.")
            } ?: ExecutionResult.Failed("No active task matches \"${action.query}\".")

            JarvisAction.ListTasks -> {
                val titles = tasks.activeTitles()
                if (titles.isEmpty()) {
                    ExecutionResult.Success("Your task list is empty.")
                } else {
                    ExecutionResult.Success(
                        buildString {
                            append("You have ${titles.size} active ")
                            append(if (titles.size == 1) "task: " else "tasks: ")
                            append(titles.take(10).joinToString("; "))
                        },
                    )
                }
            }

            is JarvisAction.DeviceInfo -> deviceInfo.read(action.field)

            is JarvisAction.MediaControl -> audioController.media(action.command)

            is JarvisAction.ReadNotifications -> {
                val recent = notificationVault.recent(action.limit)
                if (recent.isEmpty()) {
                    ExecutionResult.Success("There are no recent notifications to read.")
                } else {
                    ExecutionResult.Success(
                        recent.joinToString(" | ") { item ->
                            buildString {
                                append(item.appLabel)
                                item.title?.let { append(": ").append(it) }
                                item.text?.let { append(" — ").append(it) }
                            }
                        }.take(600),
                    )
                }
            }

            is JarvisAction.FindContact -> {
                val matches = contacts.search(action.query)
                if (matches.isEmpty()) {
                    ExecutionResult.Failed("No contact matching \"${action.query}\".")
                } else {
                    ExecutionResult.Success(
                        matches.joinToString("; ") { "${it.displayName} ${it.number}" }.take(400),
                    )
                }
            }

            is JarvisAction.CallContact -> {
                val target = resolveNumber(action.contactQuery)
                    ?: return ExecutionResult.Failed("No contact matching \"${action.contactQuery}\".")
                systemIntents.dial(target.second, target.first)
            }

            is JarvisAction.ComposeMessage -> {
                val target = resolveNumber(action.contactQuery)
                    ?: return ExecutionResult.Failed("No contact matching \"${action.contactQuery}\".")
                systemIntents.composeMessage(target.second, action.body, target.first)
            }

            is JarvisAction.CreateRoutine -> {
                if (action.steps.isEmpty()) {
                    ExecutionResult.Failed("A routine needs at least one step.")
                } else {
                    routines.create(action.name, action.triggerHour, action.triggerMinute, action.steps)
                    routines.findByName(action.name)?.let(automationScheduler::schedule)
                    ExecutionResult.Success(
                        "Routine \"${action.name}\" saved for %02d:%02d daily.".format(action.triggerHour, action.triggerMinute),
                    )
                }
            }

            is JarvisAction.RunRoutine -> routineRunner.runByName(action.name, ActionSource.AUTOMATION)

            is JarvisAction.Remember -> {
                memories.remember(action.text)
                ExecutionResult.Success("I will remember that.")
            }

            is JarvisAction.Recall -> {
                val found = memories.search(action.query)
                if (found.isEmpty()) {
                    ExecutionResult.Success("I do not have anything stored about that.")
                } else {
                    ExecutionResult.Success(found.take(5).joinToString("; ") { it.text }.take(500))
                }
            }
        }

    /** Contacts are resolved on device only, and only at the moment of use. */
    private fun resolveNumber(query: String): Pair<String, String>? {
        if (!contacts.hasPermission()) return null
        if (contacts.looksLikeNumber(query)) return query.trim() to query.trim()
        return contacts.bestMatch(query)?.let { it.displayName to it.number }
    }

    private suspend fun record(action: JarvisAction, source: ActionSource, result: ExecutionResult) {
        val status = when (result) {
            is ExecutionResult.Success -> "SUCCESS"
            is ExecutionResult.NeedsConfirmation -> "CONFIRM"
            is ExecutionResult.NeedsPermission -> "PERMISSION:${result.permission.name}"
            is ExecutionResult.Denied -> "DENIED"
            is ExecutionResult.Unsupported -> "UNSUPPORTED"
            is ExecutionResult.Failed -> "FAILED"
            is ExecutionResult.VoiceFailure -> "VOICE_ERROR"
        }
        history.record(
            status = status,
            actionType = action.type.wire,
            source = source.name,
            summary = safeSummary(action),
            at = clock(),
        )
    }

    /**
     * History keeps what happened, not the content of a private message: a drafted
     * message body must not end up in a log the user might show someone.
     */
    private fun safeSummary(action: JarvisAction): String = when (action) {
        is JarvisAction.ComposeMessage -> "Draft a message to ${action.contactQuery}"
        is JarvisAction.Remember -> "Saved a memory"
        is JarvisAction.Recall -> "Searched memory"
        else -> ActionSummary.describe(action)
    }

    /** Parses one routine step with the offline parser, so routines never call the cloud. */
    suspend fun parseRoutineStep(step: String): ParseResult = localParser.parse(step)

    /** Exposed for the permission dashboard to explain what a blocked action needs. */
    fun requiredPermissionFor(type: ActionType): PermissionKey? = when (type) {
        ActionType.SET_BRIGHTNESS -> PermissionKey.WRITE_SETTINGS
        ActionType.SET_DND -> PermissionKey.DO_NOT_DISTURB
        ActionType.READ_NOTIFICATIONS -> PermissionKey.NOTIFICATION_LISTENER
        else -> null
    }
}
