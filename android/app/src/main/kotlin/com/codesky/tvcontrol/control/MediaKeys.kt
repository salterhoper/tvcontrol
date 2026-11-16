package com.codesky.tvcontrol.control

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import kotlin.math.roundToInt

/**
 * The part of a remote that works system wide without any special permission:
 * volume and media transport keys. Every streaming app honours these because they
 * travel through the normal audio stack.
 */
class MediaKeys(private val context: Context) {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    fun volumeUp(): Int? = adjust(AudioManager.ADJUST_RAISE)

    fun volumeDown(): Int? = adjust(AudioManager.ADJUST_LOWER)

    fun toggleMute(): Int? {
        val manager = audio ?: return null
        manager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_TOGGLE_MUTE,
            AudioManager.FLAG_SHOW_UI,
        )
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun setVolumePercent(percent: Int): Int? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((percent.coerceIn(0, 100) / 100.0) * max).roundToInt()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return target
    }

    fun currentVolumePercent(): Int? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).roundToInt()
    }

    fun muteState(): Boolean? = audio?.isStreamMute(AudioManager.STREAM_MUSIC)

    fun playPause(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    fun play(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)

    fun pause(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)

    fun next(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_NEXT)

    fun previous(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun stop(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_STOP)

    fun rewind(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_REWIND)

    fun fastForward(): Boolean = dispatch(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

    private fun adjust(direction: Int): Int? {
        val manager = audio ?: return null
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun dispatch(keyCode: Int): Boolean = runCatching {
        val manager = audio ?: return false
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        manager.dispatchMediaKeyEvent(down)
        manager.dispatchMediaKeyEvent(up)
        true
    }.getOrDefault(false)
}
