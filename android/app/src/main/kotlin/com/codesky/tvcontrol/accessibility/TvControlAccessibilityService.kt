package com.codesky.tvcontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.codesky.tvcontrol.AppContainer
import com.codesky.tvcontrol.TvControlApp
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.Logging
import com.codesky.tvcontrol.service.ControlServerService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * The engine behind the remote.
 *
 * ## What accessibility can and cannot do on Android TV
 *
 * A normal app cannot inject key events system wide (that needs INJECT_EVENTS, root
 * or an ADB connection). What an accessibility service *can* do is drive the focus
 * system, which is how every Android TV launcher and app is built:
 *
 *  - directional movement: [AccessibilityNodeInfo.focusSearch] finds the node that
 *    *would* take focus in a direction; asking that node to take focus moves the
 *    selection exactly like a D-pad press in the vast majority of TV UIs;
 *  - OK: the focused node is asked to click, falling back to a touch tap at its
 *    centre (apps on TV are touch based underneath);
 *  - back/home/recents: real global actions, these behave like the remote;
 *  - typing: ACTION_SET_TEXT writes straight into the focused field, and on Android
 *    11+ ACTION_IME_ENTER submits it. This is the "autotyping" feature.
 *
 * When an app refuses to move focus (a few custom players do), the failure is
 * reported instead of guessed at: honest limits beat silently wrong behaviour.
 */
class TvControlAccessibilityService : AccessibilityService() {

    // Anything that escapes a coroutine of this service would reach the uncaught
    // exception handler and take the process down - which the system reads as "the
    // accessibility service is broken" and answers by switching the user's toggle
    // back off. Log instead of dying.
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Logging.e("Accessibility coroutine failed", error)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    private var publishJob: Job? = null

    private var lastSignature: String? = null
    private var lastAppPackage: String? = null
    private var lastFieldSignature: String? = null

    /**
     * The app container, or null if this process somehow did not run [TvControlApp]. The
     * engine must keep running (and keep typing/navigating) even then.
     */
    private val container: AppContainer? get() = (applicationContext as? TvControlApp)?.container

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Nothing in here may throw: an exception from this callback makes the system
        // treat the service as broken, which is seen by the user as the accessibility
        // switch flipping itself back off.
        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            val bus = container
            bus?.bridge?.attach(this)
            Logging.i("Accessibility engine connected")
            bus?.events?.emit(EVENT_ENGINE_STATE, buildJsonObject { put("connected", true) })
            publish(force = true)

            // Android binds this service itself shortly after boot, which makes it a
            // dependable moment to bring the dashboard server up - BOOT_COMPLETED can
            // be delayed or its foreground-service start refused on TV builds.
            if (bus != null && bus.settings.autoStart && !ControlServerService.running()) {
                Logging.i("Engine connected at boot, starting the control server")
                ControlServerService.start(this)
            }
        }.onFailure { Logging.e("Accessibility engine failed to start cleanly", it) }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        runCatching {
            val bus = container
            bus?.bridge?.detach(this)
            bus?.events?.emit(EVENT_ENGINE_STATE, buildJsonObject { put("connected", false) })
        }.onFailure { Logging.e("Accessibility engine failed to detach cleanly", it) }
        Logging.i("Accessibility engine detached")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        publishJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> schedulePublish()

            else -> Unit
        }
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(DEBOUNCE_MS)
            publish(force = false)
        }
    }

    // ------------------------------------------------------------ state mirror

    /**
     * Rebuilds the snapshot, keeps it for readers that arrive later, and tells the
     * dashboards what changed. Sky UIs redraw constantly, so identical snapshots are
     * dropped instead of spamming the websocket.
     */
    private fun publish(force: Boolean) {
        val bus = container ?: return

        val snapshot = runCatching { buildSnapshot() }
            .onFailure { Logging.w("Snapshot failed", it) }
            .getOrNull()
            ?: return

        bus.bridge.publish(snapshot)

        val signature = snapshot.pkg + "|" + snapshot.focus?.label + "|" + snapshot.field?.text +
            "|" + snapshot.labels.size + "|" + snapshot.labels.hashCode() + "|" + snapshot.imeVisible
        if (!force && signature == lastSignature) return
        lastSignature = signature

        if (snapshot.pkg != lastAppPackage) {
            lastAppPackage = snapshot.pkg
            bus.events.emit(
                EVENT_APP_CHANGED,
                buildJsonObject {
                    put("pkg", snapshot.pkg)
                    put("app", snapshot.app)
                },
            )
        }

        val fieldSignature = snapshot.pkg + "|" + snapshot.field?.hint + "|" + snapshot.field?.text + "|" + snapshot.imeVisible
        if (fieldSignature != lastFieldSignature) {
            lastFieldSignature = fieldSignature
            bus.events.emit(EVENT_INPUT_FIELD, encode(snapshot))
        }

        bus.events.emit(EVENT_SCREEN, encode(snapshot))
    }

    private fun encode(snapshot: ScreenSnapshot) = AppJson.encodeToJsonElement(snapshot)

    // -------------------------------------------------------------- public api

    /** FOCUS_UP / FOCUS_DOWN / FOCUS_LEFT / FOCUS_RIGHT. */
    suspend fun navigate(direction: Int): ActionResult = main {
        val root = appRoot() ?: return@main ActionResult.fail("No window is showing anything to navigate")
        val anchor = focusedNode(root) ?: firstFocusable(root)
        ?: return@main ActionResult.fail("Nothing on this screen can take focus")

        if (!anchor.isFocused) {
            val grabbed = anchor.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                anchor.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (grabbed) {
                publish(force = true)
                return@main ActionResult.ok(via = "initial-focus", moved = true)
            }
        }

        var cursor: AccessibilityNodeInfo? = anchor
        var hops = 0
        while (cursor != null && hops < MAX_FOCUS_HOPS) {
            val next = cursor.focusSearch(direction) ?: break
            if (sameNode(next, cursor)) break

            if (next.isVisibleToUser && next.isEnabled && (next.isFocusable || next.isClickable)) {
                val taken = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                    next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                if (taken) {
                    publish(force = true)
                    return@main ActionResult.ok(via = "focus", moved = true)
                }
            }
            cursor = next
            hops++
        }

        publish(force = true)
        ActionResult(
            ok = false,
            via = "focus",
            moved = false,
            note = "Focus did not move ${directionName(direction)}. This app does not expose focus targets to accessibility; try scrolling or the app's own search.",
        )
    }

    suspend fun activate(): ActionResult = main {
        val root = appRoot() ?: return@main ActionResult.fail("No window to press OK on")
        val start = focusedNode(root) ?: firstFocusable(root)
        ?: return@main ActionResult.fail("Nothing is focused")

        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth < MAX_PARENT_WALK) {
            if (node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                publish(force = true)
                return@main ActionResult.ok(via = "click", moved = true)
            }
            if (node.isEnabled && node.isCheckable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                publish(force = true)
                return@main ActionResult.ok(via = "click-checkable", moved = true)
            }
            node = node.parent
            depth++
        }

        val bounds = Rect().also { start.getBoundsInScreen(it) }
        if (performTap(bounds)) {
            publish(force = true)
            return@main ActionResult.ok(
                via = "tap",
                moved = true,
                note = "Clicked by simulating a touch on the focused item",
            )
        }

        ActionResult.fail("Nothing under the focus accepted a click")
    }

    /** ACTION_SCROLL_FORWARD / BACKWARD / LEFT / RIGHT on the nearest scrollable container. */
    suspend fun scroll(direction: Int): ActionResult = main {
        val root = appRoot() ?: return@main ActionResult.fail("No window to scroll")

        var node: AccessibilityNodeInfo? = focusedNode(root)
        var depth = 0
        while (node != null && depth < MAX_PARENT_WALK) {
            if (node.isScrollable && node.performAction(direction)) {
                publish(force = true)
                return@main ActionResult.ok(via = "scroll", moved = true)
            }
            node = node.parent
            depth++
        }

        val fallback = firstScrollable(root)
        if (fallback != null && fallback.performAction(direction)) {
            publish(force = true)
            return@main ActionResult.ok(via = "scroll-container", moved = true)
        }
        ActionResult.fail("This screen has nothing scrollable")
    }

    suspend fun global(action: Int): ActionResult = main {
        val label = globalActionName(action)
        if (performGlobalAction(action)) {
            publish(force = true)
            ActionResult.ok(via = "global-action", moved = true, note = label)
        } else {
            ActionResult.fail("Android refused the $label action")
        }
    }

    /**
     * The autotyping entry point: writes text into the focused field of whatever app
     * is in the foreground, optionally pressing the keyboard's submit key afterwards.
     */
    suspend fun inputText(text: String, mode: TextMode, submit: Boolean): TextResult = main {
        val field = findEditable()
            ?: return@main TextResult(
                ok = false,
                note = "No text field is focused on the TV. Open a search box first (the same thing you would do with the remote).",
            )

        val existing = field.text?.toString().orEmpty()
        val value = when (mode) {
            TextMode.REPLACE -> text
            TextMode.APPEND -> existing + text
            TextMode.CLEAR -> ""
            TextMode.ENTER -> existing
            // One character, but never half of a surrogate pair (emoji, some scripts).
            TextMode.BACKSPACE -> if (existing.isEmpty()) existing else existing.dropLast(
                if (existing.length >= 2 && Character.isLowSurrogate(existing.last()) && Character.isHighSurrogate(existing[existing.length - 2])) 2 else 1,
            )
        }

        var applied = true
        if ((mode != TextMode.ENTER && mode != TextMode.BACKSPACE) || value != existing) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value,
                )
            }
            applied = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        var submitted = false
        if (submit || mode == TextMode.ENTER) {
            submitted = performImeEnter(field)
        }

        publish(force = true)
        TextResult(
            ok = applied || submitted,
            via = "set-text",
            submitted = submitted,
            target = FieldInfo(
                text = value.take(200),
                hint = runCatching { field.hintText?.toString() }.getOrNull(),
                pkg = field.packageName?.toString(),
                canSubmit = canImeEnter(field),
            ),
            note = when {
                !applied -> "The focused field rejected the text"
                submit && !submitted -> "Typed, but this Android version cannot simulate the keyboard's search key"
                else -> null
            },
        )
    }

    suspend fun readScreen(): ScreenSnapshot = main { buildSnapshot() }

    // -------------------------------------------------------------- node access

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private fun appRoot(): AccessibilityNodeInfo? {
        val windows = runCatching { windows }.getOrNull()
        val candidates = windows
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.sortedByDescending { it.layer }

        candidates?.firstOrNull { it.isActive }?.root?.let { return it }
        candidates?.firstNotNullOfOrNull { it.root }?.let { return it }
        return rootInActiveWindow
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        return roots
    }

    private fun focusedNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

    private fun firstFocusable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isEnabled && node.isFocusable && !node.isEditable) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Finds the field the user would type into. Scrolls every window because when the
     * soft keyboard is up, the active window can be the keyboard itself - the field
     * still lives in the app window underneath.
     */
    private fun findEditable(): AccessibilityNodeInfo? {
        var unfocused: AccessibilityNodeInfo? = null
        for (root in allRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (node.isEditable && node.isVisibleToUser && node.isEnabled) {
                    if (node.isFocused) return node
                    if (unfocused == null) unfocused = node
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { queue.add(it) }
                }
            }
        }
        return unfocused
    }

    private fun canImeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
    }

    private fun performImeEnter(node: AccessibilityNodeInfo): Boolean {
        if (canImeEnter(node)) {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        // Older releases: many TV keyboards label the submit key, but clicking it by
        // label is too fragile to be worth doing silently. Report instead.
        return false
    }

    private suspend fun performTap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    // ---------------------------------------------------------------- snapshots

    private fun buildSnapshot(): ScreenSnapshot {
        val root = appRoot()
        val packageName = root?.packageName?.toString()
        val focused = root?.let { focusedNode(it) }
        val field = findEditable()

        val labels = LinkedHashSet<String>()
        var scrollable = false
        var visited = 0

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || visited > MAX_NODES || depth > MAX_DEPTH) return
            visited++
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (text != null && labels.size < MAX_LABELS) {
                    labels.add(text.take(MAX_LABEL_CHARS))
                }
                if (node.isScrollable) scrollable = true
            }
            for (index in 0 until node.childCount) {
                visit(node.getChild(index), depth + 1)
            }
        }
        visit(root, 0)

        val imeVisible = runCatching { windows }.getOrNull()
            ?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true

        return ScreenSnapshot(
            pkg = packageName,
            app = packageName?.let { appLabel(it) },
            activity = null,
            focus = focused?.let { summarize(it) },
            field = field?.let {
                FieldInfo(
                    text = runCatching { it.text?.toString() }.getOrNull(),
                    hint = runCatching { it.hintText?.toString() }.getOrNull(),
                    pkg = it.packageName?.toString(),
                    canSubmit = canImeEnter(it),
                )
            },
            labels = labels.toList(),
            scrollable = scrollable,
            imeVisible = imeVisible,
            takenAt = System.currentTimeMillis(),
        )
    }

    private fun summarize(node: AccessibilityNodeInfo): NodeSummary {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return NodeSummary(
            text = node.text?.toString()?.take(MAX_LABEL_CHARS),
            desc = node.contentDescription?.toString()?.take(MAX_LABEL_CHARS),
            cls = node.className?.toString(),
            focused = node.isFocused,
            focusable = node.isFocusable,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            viewId = node.viewIdResourceName,
        )
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean = a == b

    private fun directionName(direction: Int): String = when (direction) {
        View.FOCUS_UP -> "up"
        View.FOCUS_DOWN -> "down"
        View.FOCUS_LEFT -> "left"
        View.FOCUS_RIGHT -> "right"
        else -> "that way"
    }

    private fun globalActionName(action: Int): String = when (action) {
        GLOBAL_ACTION_BACK -> "Back"
        GLOBAL_ACTION_HOME -> "Home"
        GLOBAL_ACTION_RECENTS -> "Recents"
        GLOBAL_ACTION_NOTIFICATIONS -> "Notifications"
        GLOBAL_ACTION_QUICK_SETTINGS -> "Quick settings"
        else -> "global action $action"
    }

    companion object {
        const val EVENT_ENGINE_STATE = "engine.state"
        const val EVENT_SCREEN = "screen.update"
        const val EVENT_APP_CHANGED = "app.changed"
        const val EVENT_INPUT_FIELD = "input.field"

        private const val DEBOUNCE_MS = 220L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40
        private const val MAX_LABELS = 80
        private const val MAX_LABEL_CHARS = 60
        private const val MAX_FOCUS_HOPS = 24
        private const val MAX_PARENT_WALK = 8
        private const val TAP_MS = 60L
    }
}
