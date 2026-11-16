package com.codesky.tvcontrol.accessibility

/** Shared "the engine is off" answer so every caller says the same thing. */
object AccessibilityActionResult {
    fun offline(what: String): ActionResult = ActionResult.fail(
        "$what needs the accessibility engine. Enable \"TV Control remote\" in the TV's Settings > Accessibility.",
    )
}
