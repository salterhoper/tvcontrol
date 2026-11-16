package com.codesky.tvcontrol.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * An envelope for everything that travels over the websocket. One shape means the
 * dashboard only needs a single decode path.
 */
data class ServerEvent(
    val type: String,
    val data: JsonElement? = null,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * In-process pub/sub. The accessibility service and the assistant publish,
 * the websocket hub subscribes and fans out to connected dashboards.
 */
class EventBus {

    val isRemoteConnected = MutableStateFlow(false)
    val activeDevices = MutableStateFlow<List<String>>(emptyList())

    private val _events = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    fun emit(type: String, data: JsonElement? = null) {
        _events.tryEmit(ServerEvent(type = type, data = data))
    }
}
