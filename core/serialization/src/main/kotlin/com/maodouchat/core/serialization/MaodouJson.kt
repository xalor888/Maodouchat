package com.maodouchat.core.serialization

import kotlinx.serialization.json.Json

object MaodouJson {
    val strict = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    val forwardCompatible = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
