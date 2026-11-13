package com.jarvis.assistant.automation

import com.jarvis.assistant.ai.LocalCommandParser
import com.jarvis.assistant.core.ActionRunner
import com.jarvis.assistant.core.model.ActionSource
import com.jarvis.assistant.core.model.ExecutionResult
import com.jarvis.assistant.database.HistoryRepository
import com.jarvis.assistant.security.ParseResult
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Executes a routine's steps.
 *
 * Steps are interpreted by the offline parser, never by the cloud model, so a routine
 * keeps working in airplane mode and cannot be rewritten remotely. Every step still
 * passes through the privacy core with [ActionSource.AUTOMATION], which forbids
 * high-risk work outright.
 */
@Singleton
class RoutineRunner @Inject constructor(
    private val routines: RoutineRepository,
    private val parser: LocalCommandParser,
    private val runner: Provider<ActionRunner>,
    private val history: HistoryRepository,
) {

    private val clock: () -> Long = System::currentTimeMillis
    suspend fun runByName(name: String, source: ActionSource = ActionSource.AUTOMATION): ExecutionResult {
        val routine = routines.findByName(name)
            ?: return ExecutionResult.Failed("No routine called \"$name\".")
        return run(routine, source)
    }

    suspend fun runById(id: Long): ExecutionResult {
        val routine = routines.routinesSnapshot().firstOrNull { it.id == id }
            ?: return ExecutionResult.Failed("That routine no longer exists.")
        return run(routine, ActionSource.AUTOMATION)
    }

    private suspend fun run(routine: Routine, source: ActionSource): ExecutionResult {
        if (routine.steps.isEmpty()) return ExecutionResult.Failed("That routine has no steps.")

        val outcomes = mutableListOf<String>()
        routine.steps.take(MAX_STEPS).forEach { step ->
            when (val parsed = parser.parse(step)) {
                is ParseResult.Parsed -> {
                    val result = runner.get().run(parsed.action, source)
                    outcomes += describe(parsed.action.type.wire, result)
                }
                is ParseResult.Rejected -> {
                    outcomes += "\"${step.take(40)}\" was skipped"
                    history.record(
                        status = "UNPARSED_STEP",
                        actionType = "ROUTINE_STEP",
                        source = source.name,
                        summary = step.take(120),
                        at = clock(),
                    )
                }
            }
        }

        routines.markRun(routine.id, clock())
        val succeeded = outcomes.count { it.startsWith("ok") }
        val message = "Routine \"${routine.name}\": $succeeded of ${outcomes.size} steps ran."
        return ExecutionResult.Success(message)
    }

    private fun describe(action: String, result: ExecutionResult): String = when (result) {
        is ExecutionResult.Success -> "ok $action"
        is ExecutionResult.Denied -> "blocked $action"
        is ExecutionResult.NeedsPermission -> "needs permission $action"
        is ExecutionResult.Unsupported -> "unsupported $action"
        is ExecutionResult.NeedsConfirmation -> "needs confirmation $action"
        is ExecutionResult.Failed -> "failed $action"
        is ExecutionResult.VoiceFailure -> "failed $action"
    }

    private companion object {
        const val MAX_STEPS = 10
    }
}
