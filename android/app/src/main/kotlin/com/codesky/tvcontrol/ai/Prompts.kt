package com.codesky.tvcontrol.ai

import com.codesky.tvcontrol.accessibility.ScreenSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** One step the assistant wants the TV to perform. */
@Serializable
data class TvAction(
    val action: String,
    val dir: String? = null,
    val count: Int? = null,
    val text: String? = null,
    val submit: Boolean? = null,
    val mode: String? = null,
    val name: String? = null,
    val query: String? = null,
    val target: String? = null,
    val cmd: String? = null,
    val ms: Int? = null,
    val why: String? = null,
    val question: String? = null,
) {
    fun describe(): String = buildString {
        append(action)
        dir?.let { append(" $it") }
        count?.let { if (it > 1) append(" x$it") }
        name?.let { append(" $it") }
        query?.let { append(" \"$it\"") }
        cmd?.let { append(" $it") }
        text?.let { append(" \"${it.take(40)}${if (it.length > 40) "..." else ""}\"") }
        ms?.let { append(" ${it}ms") }
    }
}

@Serializable
data class AssistantPlan(
    val say: String? = null,
    val confidence: Double? = null,
    val plan: List<TvAction> = emptyList(),
)

object Prompts {

    fun system(maxSteps: Int): String = """
        You are the control brain of an Android TV. You drive the TV using a small
        action vocabulary and nothing else.

        Answer with one JSON object exactly in this shape:
        {"say": "<one short sentence for the user>", "confidence": 0.0-1.0, "plan": [ ...actions... ]}

        Available actions (no others exist):
        - {"action":"dpad","dir":"up|down|left|right","count":1}   move the selection
        - {"action":"ok"}                                          press OK on the selected item
        - {"action":"back"} | {"action":"home"} | {"action":"recents"}
        - {"action":"type","text":"...","submit":true}             writes into the focused text field, submit presses the keyboard's search key
        - {"action":"open_app","name":"Netflix"}                   opens an installed app by its name
        - {"action":"search","query":"dune","target":"auto|youtube|play"}  opens search results directly
        - {"action":"media","cmd":"play_pause|next|previous|volume_up|volume_down|mute"}
        - {"action":"scroll","dir":"down|up|left|right"}           scrolls a list without moving the selection
        - {"action":"wait","ms":800}                               let the TV catch up
        - {"action":"observe","why":"..."}                          stop and look at the screen again before continuing
        - {"action":"ask","question":"..."}                        ask the user something and stop
        - {"action":"say","text":"..."}                            tell the user something, no TV action

        Rules:
        1. At most $maxSteps actions. Few and certain beats many and guessed.
        2. Prefer {"action":"search"} over typing inside an app: it is more reliable.
        3. To search inside an app that has no deep link, open the app first, then
           navigate to its search box (dpad), then type. Never assume a field is
           focused - use the screen state you are given.
        4. Only open apps from the installed list you are given. Never invent names.
        5. "say" must be one short sentence in the language the user wrote in.
        6. If the request cannot be done, return a plan with only a "say" and an "ask"
           action explaining what you need from the user.
        7. Output JSON only. No markdown, no commentary outside the JSON object.
    """.trimIndent()

    fun user(
        utterance: String,
        snapshot: ScreenSnapshot?,
        installedApps: List<String>,
    ): String = buildString {
        append("The user said: \"")
        append(utterance.trim())
        append("\"\n\n")
        if (snapshot != null) {
            append("What is on the TV right now:\n")
            append(snapshot.compact())
            append("\n\n")
        } else {
            append("What is on the TV right now: (not requested)\n\n")
        }
        append("Installed apps: ")
        append(installedApps.take(60).joinToString(", "))
        append("\n")
    }

    fun retry(
        utterance: String,
        snapshot: ScreenSnapshot?,
        executed: List<String>,
        failure: String,
    ): String = buildString {
        append("The user asked: \"")
        append(utterance.trim())
        append("\"\n\n")
        append("Steps already executed: ")
        append(executed.joinToString("; ").ifEmpty { "none" })
        append("\nThe last step failed: ")
        append(failure)
        append("\n\n")
        if (snapshot != null) {
            append("Screen after the failure:\n")
            append(snapshot.compact())
            append("\n\n")
        }
        append("Return a corrected JSON plan for the remaining work only. ")
        append("If it cannot be completed, use \"say\" plus \"ask\".")
    }

    /** Tolerant parsing: models like to wrap JSON in prose or code fences. */
    fun parsePlan(raw: String): AssistantPlan? {
        val root = extractJsonObject(raw) ?: return null
        val decoded = runCatching {
            Llm.json.decodeFromJsonElement(AssistantPlan.serializer(), root)
        }.getOrNull()
        if (decoded != null && decoded.plan.isNotEmpty()) return decoded

        // Some models answer with {"actions": [...]} instead; accept that too.
        val alternative = root["actions"] ?: root["steps"]
        if (alternative != null) {
            val plan = runCatching {
                Llm.json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(TvAction.serializer()),
                    alternative,
                )
            }.getOrNull()
            if (plan != null) {
                val say = decoded?.say ?: root["say"]?.let { element ->
                    runCatching {
                        Llm.json.decodeFromJsonElement(JsonPrimitive.serializer(), element)
                    }.getOrNull()?.content
                }
                return AssistantPlan(say = say, plan = plan)
            }
        }
        return decoded?.takeIf { it.say != null }
    }
}
