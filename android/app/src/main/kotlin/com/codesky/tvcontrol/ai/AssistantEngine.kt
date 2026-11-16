package com.codesky.tvcontrol.ai

import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.accessibility.TextMode
import com.codesky.tvcontrol.config.SettingsStore
import com.codesky.tvcontrol.control.MetricKey
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.server.AssistantRunDto
import com.codesky.tvcontrol.server.AssistantStepDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns "put something funny on for the kids" into button presses.
 *
 * Flow: read the screen -> ask the model for a JSON plan -> execute the plan step by
 * step, streaming progress -> if a step fails in agent mode, show the model what
 * happened and let it correct itself once. Every run is kept so a dashboard that
 * reconnects can still see what happened.
 */
class AssistantEngine(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = ConcurrentHashMap<String, AssistantRunDto>()

    /**
     * Built on first use, never at startup. Creating a Ktor client pulls in
     * kotlin-reflect and the whole plugin machinery, which on a slow TV takes long
     * enough to blow the accessibility-service bind timeout when the app cold
     * starts - and a process killed during that bind loses its accessibility entry,
     * which is exactly the "the switch turns itself back off" failure.
     */
    private val http by lazy { Llm.defaultHttpClient() }

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRunId: String? = null

    fun start(utterance: String, useContext: Boolean = true, dryRun: Boolean = false): String {
        activeJob?.cancel()

        val id = "run_" + UUID.randomUUID().toString().replace("-", "").take(10)
        runs[id] = AssistantRunDto(
            id = id,
            utterance = utterance,
            status = STATUS_RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        activeRunId = id
        activeJob = scope.launch {
            execute(id, utterance, useContext, dryRun)
        }
        return id
    }

    fun cancel() {
        activeRunId?.let { id ->
            update(id) { it.copy(status = STATUS_CANCELLED, finishedAt = System.currentTimeMillis()) }
        }
        activeJob?.cancel()
        activeJob = null
        activeRunId = null
    }

    fun get(id: String): AssistantRunDto? = runs[id]

    // ------------------------------------------------------------------ internals

    private suspend fun execute(id: String, utterance: String, useContext: Boolean, dryRun: Boolean) {
        val settings = container.settings
        try {
            val client = Llm.create(settings, http)
                ?: throw LlmException(NOT_CONFIGURED)

            val snapshot = if (useContext) container.control.snapshot() else null
            val installedApps = container.control.apps().map { it.label }

            var plan = requestPlan(
                client = client,
                system = Prompts.system(settings.maxSteps),
                user = Prompts.user(utterance, snapshot, installedApps),
            )

            update(id) { it.copy(say = plan.say) }

            if (dryRun) {
                update(id) {
                    it.copy(
                        status = STATUS_DONE,
                        steps = plan.plan.mapIndexed { index, action ->
                            AssistantStepDto(index + 1, action.action, action.describe(), STATUS_PLANNED)
                        },
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                return
            }

            var replans = if (settings.assistantMode == SettingsStore.MODE_AGENT) MAX_REPLANS else 0
            var stepNumber = 0
            val executed = mutableListOf<String>()

            while (true) {
                var aborted = false
                var retryWithNewPlan = false
                for (action in plan.plan.take(settings.maxSteps)) {
                    stepNumber++
                    val index = stepNumber
                    val detail = action.describe()

                    appendStep(id, AssistantStepDto(index, action.action, detail, STATUS_RUNNING))

                    val outcome = perform(action)
                    executed.add("${action.describe()} -> ${if (outcome.ok) "ok" else "failed: ${outcome.note}"}")

                    patchLastStep(
                        id = id,
                        status = if (outcome.ok) STATUS_OK else STATUS_FAILED,
                        note = outcome.note,
                    )

                    if (!outcome.ok) {
                        aborted = true
                        if (replans > 0) {
                            replans--
                            val refreshed = runCatching { container.control.snapshot() }.getOrNull()
                            plan = runCatching {
                                requestPlan(
                                    client = client,
                                    system = Prompts.system(settings.maxSteps),
                                    user = Prompts.retry(
                                        utterance = utterance,
                                        snapshot = refreshed,
                                        executed = executed,
                                        failure = outcome.note ?: "step failed",
                                    ),
                                )
                            }.getOrElse { original ->
                                Logging.w("Replan failed", original)
                                AssistantPlan(say = "I could not recover from that step.")
                            }
                            update(id) { it.copy(say = plan.say ?: it.say) }
                            retryWithNewPlan = plan.plan.isNotEmpty()
                        }
                        break
                    }

                    when (action.action) {
                        "ask" -> {
                            update(id) {
                                it.copy(
                                    status = STATUS_NEEDS_INPUT,
                                    say = action.question ?: action.text ?: it.say,
                                    finishedAt = System.currentTimeMillis(),
                                )
                            }
                            return
                        }

                        else -> delay(settings.stepDelayMs.toLong())
                    }
                }

                if (!aborted) break
                if (!retryWithNewPlan) break
            }

            val failed = runs[id]?.steps?.any { it.status == STATUS_FAILED } == true
            update(id) {
                it.copy(
                    status = if (failed) STATUS_PARTIAL else STATUS_DONE,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        } catch (cancellation: CancellationException) {
            update(id) { it.copy(status = STATUS_CANCELLED, finishedAt = System.currentTimeMillis()) }
        } catch (problem: Throwable) {
            Logging.w("Assistant run failed", problem)
            update(id) {
                it.copy(
                    status = STATUS_FAILED,
                    error = problem.message ?: "The assistant failed",
                    finishedAt = System.currentTimeMillis(),
                )
            }
        } finally {
            if (activeRunId == id) {
                activeJob = null
                activeRunId = null
            }
        }
    }

    private suspend fun requestPlan(client: LlmClient, system: String, user: String): AssistantPlan {
        val raw = client.complete(system, user, json = true)
        return Prompts.parsePlan(raw)
            ?: throw LlmException("The model did not return a usable plan. Try a model that supports JSON output.")
    }

    private suspend fun perform(action: TvAction): Outcome {
        val control = container.control
        return when (action.action.lowercase()) {
            "dpad" -> {
                val key = when (action.dir?.lowercase()) {
                    "up" -> MetricKey.UP
                    "down" -> MetricKey.DOWN
                    "left" -> MetricKey.LEFT
                    "right" -> MetricKey.RIGHT
                    else -> null
                } ?: return Outcome(false, "Unknown direction ${action.dir}")
                outcome(control.key(key, action.count ?: 1))
            }

            "ok" -> outcome(control.key(MetricKey.OK))
            "back" -> outcome(control.key(MetricKey.BACK))
            "home" -> outcome(control.key(MetricKey.HOME))
            "recents" -> outcome(control.key(MetricKey.RECENTS))

            "type" -> {
                val mode = when (action.mode?.lowercase()) {
                    "append" -> TextMode.APPEND
                    "clear" -> TextMode.CLEAR
                    else -> TextMode.REPLACE
                }
                val result = control.text(action.text.orEmpty(), mode, action.submit ?: false)
                Outcome(result.ok, result.note)
            }

            "open_app" -> {
                val target = action.name ?: action.text
                if (target.isNullOrBlank()) {
                    Outcome(false, "open_app without a name")
                } else {
                    val launched = control.launch(target)
                    Outcome(launched.ok, launched.note ?: launched.label)
                }
            }

            "search" -> {
                val query = action.query ?: action.text
                if (query.isNullOrBlank()) {
                    Outcome(false, "search without a query")
                } else {
                    val result = control.search(query, action.target ?: "auto")
                    Outcome(result.ok, result.note ?: result.via)
                }
            }

            "media" -> {
                val key = when (action.cmd?.lowercase()) {
                    "play_pause", "playpause" -> MetricKey.PLAY_PAUSE
                    "play" -> MetricKey.PLAY
                    "pause" -> MetricKey.PAUSE
                    "next" -> MetricKey.NEXT
                    "previous", "prev" -> MetricKey.PREVIOUS
                    "stop" -> MetricKey.STOP
                    "volume_up" -> MetricKey.VOLUME_UP
                    "volume_down" -> MetricKey.VOLUME_DOWN
                    "mute" -> MetricKey.MUTE
                    else -> null
                } ?: return Outcome(false, "Unknown media command ${action.cmd}")
                outcome(control.key(key))
            }

            "scroll" -> {
                val key = when (action.dir?.lowercase()) {
                    "down" -> MetricKey.SCROLL_DOWN
                    "up" -> MetricKey.SCROLL_UP
                    "left" -> MetricKey.SCROLL_LEFT
                    "right" -> MetricKey.SCROLL_RIGHT
                    else -> null
                } ?: return Outcome(false, "Unknown scroll direction ${action.dir}")
                outcome(control.key(key, action.count ?: 1))
            }

            "wait" -> {
                delay((action.ms ?: 800).coerceIn(0, 10_000).toLong())
                Outcome(true, "waited")
            }

            "say" -> Outcome(true, action.text)

            "observe" -> Outcome(true, "screen re-read next step")

            "ask" -> Outcome(true, action.question)

            else -> Outcome(false, "Unknown action \"${action.action}\"")
        }
    }

    private fun outcome(result: com.codesky.tvcontrol.accessibility.ActionResult): Outcome =
        Outcome(result.ok, result.note ?: result.via)

    private fun appendStep(id: String, step: AssistantStepDto) {
        update(id) { it.copy(steps = it.steps + step) }
    }

    private fun patchLastStep(id: String, status: String, note: String?) {
        update(id) { run ->
            val steps = run.steps.toMutableList()
            if (steps.isEmpty()) return@update run
            steps[steps.lastIndex] = steps.last().copy(status = status, note = note)
            run.copy(steps = steps)
        }
    }

    private fun update(id: String, transform: (AssistantRunDto) -> AssistantRunDto): AssistantRunDto? {
        val current = runs[id] ?: return null
        val next = transform(current)
        runs[id] = next
        container.events.emit("assistant.run", AppJson.encodeToJsonElement(next))
        return next
    }

    private data class Outcome(val ok: Boolean, val note: String?)

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_NEEDS_INPUT = "needs_input"
        const val STATUS_PLANNED = "planned"
        const val STATUS_OK = "ok"

        private const val MAX_REPLANS = 1

        private const val NOT_CONFIGURED =
            "No assistant is configured yet. Open Settings in the dashboard and add a provider, model and API key."
    }
}
