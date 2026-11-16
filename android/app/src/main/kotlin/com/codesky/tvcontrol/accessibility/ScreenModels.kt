package com.codesky.tvcontrol.accessibility

import kotlinx.serialization.Serializable

@Serializable
data class NodeSummary(
    val text: String? = null,
    val desc: String? = null,
    val cls: String? = null,
    val focused: Boolean = false,
    val focusable: Boolean = false,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val bounds: String? = null,
    val viewId: String? = null,
) {
    val label: String?
        get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
}

@Serializable
data class FieldInfo(
    val text: String? = null,
    val hint: String? = null,
    val pkg: String? = null,
    val canSubmit: Boolean = false,
)

/**
 * A deliberately small, serialisable picture of what is on the TV right now.
 * Both the dashboard and the LLM work from this, never from raw node trees.
 */
@Serializable
data class ScreenSnapshot(
    val pkg: String? = null,
    val app: String? = null,
    val activity: String? = null,
    val focus: NodeSummary? = null,
    val field: FieldInfo? = null,
    val labels: List<String> = emptyList(),
    val scrollable: Boolean = false,
    val imeVisible: Boolean = false,
    val takenAt: Long = 0L,
) {

    /** Token-frugal rendering used in LLM prompts. */
    fun compact(maxLabels: Int = 70): String = buildString {
        append("app: ")
        append(app ?: "unknown")
        pkg?.let { append(" (").append(it).append(")") }
        append('\n')
        append("focused: ")
        append(focus?.label ?: "-")
        focus?.let { node ->
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (!node.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
        }
        append('\n')
        append("text field: ")
        field?.let { field ->
            append("hint=\"").append(field.hint ?: "-").append("\" value=\"")
            append(field.text?.take(120) ?: "").append("\"")
            if (field.canSubmit) append(" (can simulate IME enter)")
        } ?: append("none focused")
        append('\n')
        append("soft keyboard visible: ").append(if (imeVisible) "yes" else "no").append('\n')
        if (labels.isNotEmpty()) {
            append("visible items: ")
            append(labels.take(maxLabels).joinToString(" | "))
        }
    }
}

enum class TextMode { REPLACE, APPEND, CLEAR, ENTER, BACKSPACE }

@Serializable
data class ActionResult(
    val ok: Boolean,
    val via: String? = null,
    val moved: Boolean? = null,
    val note: String? = null,
) {
    companion object {
        fun ok(via: String, moved: Boolean? = null, note: String? = null) = ActionResult(true, via, moved, note)
        fun fail(note: String) = ActionResult(false, null, null, note)
        fun unsupported(note: String) = ActionResult(false, "unsupported", null, note)
    }
}

@Serializable
data class TextResult(
    val ok: Boolean,
    val via: String? = null,
    val submitted: Boolean? = null,
    val target: FieldInfo? = null,
    val note: String? = null,
)
