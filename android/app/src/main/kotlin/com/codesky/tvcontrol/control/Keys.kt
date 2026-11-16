package com.codesky.tvcontrol.control

/**
 * The remote's vocabulary. Everything the dashboard, a macro or the assistant can
 * ask the TV to do is one of these, so there is exactly one place that decides how
 * a logical button turns into an actual action.
 */
enum class MetricKey(val wire: String, val group: Group) {
    UP("up", Group.NAV),
    DOWN("down", Group.NAV),
    LEFT("left", Group.NAV),
    RIGHT("right", Group.NAV),
    OK("ok", Group.NAV),
    BACK("back", Group.GLOBAL),
    HOME("home", Group.GLOBAL),
    RECENTS("recents", Group.GLOBAL),
    NOTIFICATIONS("notifications", Group.GLOBAL),
    QUICK_SETTINGS("quick_settings", Group.GLOBAL),
    MENU("menu", Group.GLOBAL),

    SCROLL_UP("scroll_up", Group.NAV),
    SCROLL_DOWN("scroll_down", Group.NAV),
    SCROLL_LEFT("scroll_left", Group.NAV),
    SCROLL_RIGHT("scroll_right", Group.NAV),

    VOLUME_UP("volume_up", Group.MEDIA),
    VOLUME_DOWN("volume_down", Group.MEDIA),
    MUTE("mute", Group.MEDIA),
    PLAY_PAUSE("play_pause", Group.MEDIA),
    PLAY("play", Group.MEDIA),
    PAUSE("pause", Group.MEDIA),
    NEXT("next", Group.MEDIA),
    PREVIOUS("previous", Group.MEDIA),
    STOP("stop", Group.MEDIA),
    REWIND("rewind", Group.MEDIA),
    FAST_FORWARD("fast_forward", Group.MEDIA),
    ;

    enum class Group { NAV, GLOBAL, MEDIA }

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String?): MetricKey? = byWire[value?.trim()?.lowercase()]
    }
}
