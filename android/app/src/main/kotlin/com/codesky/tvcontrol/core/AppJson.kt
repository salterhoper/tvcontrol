package com.codesky.tvcontrol.core

import kotlinx.serialization.json.Json

/** One JSON dialect for the whole app: strict output, forgiving input. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}
