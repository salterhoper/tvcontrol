package com.codesky.tvcontrol.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import com.codesky.tvcontrol.control.MediaKeys
import com.codesky.tvcontrol.core.AppJson
import com.codesky.tvcontrol.core.EventBus
import com.codesky.tvcontrol.core.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream

@Serializable
data class NowPlayingDto(
    val active: Boolean,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isPlaying: Boolean = false,
    val playbackState: String = "none",
    val canSeek: Boolean = true,
    val canPlayPause: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSkipPrev: Boolean = true,
    val hasArtwork: Boolean = false,
    val artworkBase64: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
)

@Serializable
data class MediaControlResult(
    val ok: Boolean,
    val action: String,
    val message: String? = null,
    val error: String? = null,
    val nowPlaying: NowPlayingDto? = null,
)

class TvMediaManager(
    private val context: Context,
    private val events: EventBus,
    private val mediaKeys: MediaKeys,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    @Volatile
    private var activeController: MediaController? = null

    @Volatile
    private var lastSnapshot: NowPlayingDto = NowPlayingDto(active = false)

    @Volatile
    var currentArtworkBytes: ByteArray? = null
        private set

    private var tickerJob: Job? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateSnapshotAndEmit()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateSnapshotAndEmit()
        }

        override fun onSessionDestroyed() {
            activeController = null
            findActiveController()
            updateSnapshotAndEmit()
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onControllersChanged(controllers)
    }

    init {
        // Attempt to auto-enable NotificationListener if app holds WRITE_SECURE_SETTINGS
        TvNotificationListener.ensureEnabled(context)

        TvNotificationListener.onConnectedCallback = {
            scope.launch {
                delay(300)
                registerSessionListener()
                findActiveController()
                updateSnapshotAndEmit()
            }
        }

        // Initial setup
        registerSessionListener()
        findActiveController()

        // Background ticker to keep timeline in sync and detect changes
        tickerJob = scope.launch {
            while (isActive) {
                delay(1200)
                if (activeController == null) {
                    findActiveController()
                }
                val current = buildSnapshot()
                if (current.active || lastSnapshot.active) {
                    if (current != lastSnapshot) {
                        lastSnapshot = current
                        events.emit("now_playing", AppJson.encodeToJsonElement(current))
                    }
                }
            }
        }
    }

    private fun registerSessionListener() {
        val sm = sessionManager ?: return
        val cn = TvNotificationListener.componentName(context)
        runCatching {
            sm.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            sm.addOnActiveSessionsChangedListener(sessionsChangedListener, cn)
            Logging.i("MediaSession active sessions listener registered")
        }
    }

    private fun onControllersChanged(controllers: List<MediaController>?) {
        val playing = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull {
            val state = it.playbackState?.state ?: PlaybackState.STATE_NONE
            state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
        } ?: controllers?.firstOrNull()

        if (playing?.sessionToken != activeController?.sessionToken) {
            runCatching { activeController?.unregisterCallback(controllerCallback) }
            activeController = playing
            runCatching { activeController?.registerCallback(controllerCallback) }
            updateSnapshotAndEmit()
        }
    }

    private fun findActiveController(): MediaController? {
        val sm = sessionManager ?: return null
        val cn = TvNotificationListener.componentName(context)
        val controllers = runCatching {
            sm.getActiveSessions(cn)
        }.getOrNull()

        onControllersChanged(controllers)
        return activeController
    }

    fun getNowPlaying(): NowPlayingDto {
        val current = buildSnapshot()
        lastSnapshot = current
        return current
    }

    fun control(action: String, positionMs: Long? = null): MediaControlResult {
        val controller = activeController ?: findActiveController()

        var success = false
        var message: String? = null

        if (controller != null) {
            val transport = controller.transportControls
            when (action.lowercase()) {
                "play" -> {
                    transport.play()
                    success = true
                    message = "Playback started"
                }
                "pause" -> {
                    transport.pause()
                    success = true
                    message = "Playback paused"
                }
                "play_pause" -> {
                    val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (isPlaying) transport.pause() else transport.play()
                    success = true
                    message = if (isPlaying) "Playback paused" else "Playback started"
                }
                "next" -> {
                    transport.skipToNext()
                    success = true
                    message = "Skipped to next"
                }
                "previous" -> {
                    transport.skipToPrevious()
                    success = true
                    message = "Skipped to previous"
                }
                "seek" -> {
                    if (positionMs != null && positionMs >= 0) {
                        transport.seekTo(positionMs)
                        success = true
                        message = "Seeked to ${formatTime(positionMs)}"
                    } else {
                        return MediaControlResult(ok = false, action = action, error = "Invalid seek position")
                    }
                }
                "fast_forward" -> {
                    val cur = controller.playbackState?.position ?: 0L
                    val dur = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
                    val target = (cur + 10_000L).coerceAtMost(dur)
                    transport.seekTo(target)
                    success = true
                    message = "Fast forwarded 10s"
                }
                "rewind" -> {
                    val cur = controller.playbackState?.position ?: 0L
                    val target = (cur - 10_000L).coerceAtLeast(0L)
                    transport.seekTo(target)
                    success = true
                    message = "Rewound 10s"
                }
                else -> {
                    return MediaControlResult(ok = false, action = action, error = "Unknown media action: $action")
                }
            }
        } else {
            // Fallback to hardware media keys
            when (action.lowercase()) {
                "play" -> {
                    mediaKeys.play()
                    success = true
                    message = "Sent play"
                }
                "pause" -> {
                    mediaKeys.pause()
                    success = true
                    message = "Sent pause"
                }
                "play_pause" -> {
                    mediaKeys.playPause()
                    success = true
                    message = "Toggled play/pause"
                }
                "next" -> {
                    mediaKeys.next()
                    success = true
                    message = "Skipped to next"
                }
                "previous" -> {
                    mediaKeys.previous()
                    success = true
                    message = "Skipped to previous"
                }
                "fast_forward" -> {
                    mediaKeys.fastForward()
                    success = true
                    message = "Fast forwarded"
                }
                "rewind" -> {
                    mediaKeys.rewind()
                    success = true
                    message = "Rewound"
                }
                else -> {
                    return MediaControlResult(ok = false, action = action, error = "No active media session on TV")
                }
            }
        }

        // Trigger immediate snapshot refresh
        scope.launch {
            delay(150)
            updateSnapshotAndEmit()
        }

        val updated = buildSnapshot()
        return MediaControlResult(ok = success, action = action, message = message, nowPlaying = updated)
    }

    private fun updateSnapshotAndEmit() {
        val snapshot = buildSnapshot()
        lastSnapshot = snapshot
        events.emit("now_playing", AppJson.encodeToJsonElement(snapshot))
    }

    private fun buildSnapshot(): NowPlayingDto {
        val controller = activeController ?: findActiveController()
        if (controller == null) {
            // Check fallback via dumpsys
            return parseDumpsysFallback() ?: NowPlayingDto(active = false)
        }

        val pbState = controller.playbackState
        val metadata = controller.metadata

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING
        val stateStr = when (pbState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_FAST_FORWARDING -> "fast_forwarding"
            PlaybackState.STATE_REWINDING -> "rewinding"
            else -> "none"
        }

        val rawPos = pbState?.position ?: 0L
        val lastUpdate = pbState?.lastPositionUpdateTime ?: 0L
        val speed = pbState?.playbackSpeed ?: 1.0f

        // Real-time interpolated position
        val position = if (isPlaying && lastUpdate > 0) {
            val delta = SystemClock.elapsedRealtime() - lastUpdate
            rawPos + (delta * speed).toLong()
        } else {
            rawPos
        }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: metadata?.description?.subtitle?.toString()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: metadata?.description?.description?.toString()

        val pkg = controller.packageName
        val appName = getAppLabel(pkg)

        // Artwork
        val artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.description?.iconBitmap

        val artBase64 = artBitmap?.let { bitmapToBase64(it) }

        val actions = pbState?.actions ?: 0L
        val canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L || duration > 0

        return NowPlayingDto(
            active = !title.isNullOrBlank() || isPlaying,
            title = title,
            artist = artist,
            album = album,
            packageName = pkg,
            appName = appName,
            durationMs = duration,
            positionMs = position.coerceAtLeast(0L),
            playbackSpeed = speed,
            isPlaying = isPlaying,
            playbackState = stateStr,
            canSeek = canSeek,
            canPlayPause = true,
            canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSkipPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            hasArtwork = artBase64 != null,
            artworkBase64 = artBase64,
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private fun parseDumpsysFallback(): NowPlayingDto? {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "media_session"))
            val output = process.inputStream.bufferedReader().use { it.readText() }

            if (!output.contains("package=") || !output.contains("PlaybackState")) return null

            val lines = output.lines()
            var pkg: String? = null
            var isPlaying = false
            var positionMs = 0L
            var title: String? = null
            var artist: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("package=") && pkg == null) {
                    val p = trimmed.substringAfter("package=").trim()
                    if (p != "com.android.bluetooth") pkg = p
                }
                if (trimmed.startsWith("state=PlaybackState")) {
                    if (trimmed.contains("state=PLAYING")) isPlaying = true
                    val posMatch = Regex("position=(\\d+)").find(trimmed)
                    if (posMatch != null) positionMs = posMatch.groupValues[1].toLongOrNull() ?: 0L
                }
                if (trimmed.startsWith("metadata:") && trimmed.contains("description=")) {
                    val desc = trimmed.substringAfter("description=").substringBeforeLast(", null")
                    val parts = desc.split(",")
                    if (parts.isNotEmpty()) title = parts[0].trim()
                    if (parts.size > 1) artist = parts[1].trim()
                }
            }

            if (pkg != null && (!title.isNullOrBlank() || isPlaying)) {
                NowPlayingDto(
                    active = true,
                    title = title ?: "Playing media",
                    artist = artist,
                    packageName = pkg,
                    appName = getAppLabel(pkg),
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    playbackState = if (isPlaying) "playing" else "paused",
                    lastUpdated = System.currentTimeMillis(),
                )
            } else {
                null
            }
        }.getOrNull()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String? {
        return runCatching {
            val maxDim = 256
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val (w, h) = if (ratio > 1) maxDim to (maxDim / ratio).toInt() else (maxDim * ratio).toInt() to maxDim
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            val bytes = out.toByteArray()
            currentArtworkBytes = bytes
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun getAppLabel(packageName: String?): String? {
        if (packageName == null) return null
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: packageName.split('.').lastOrNull()?.replaceFirstChar { it.uppercase() }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}
