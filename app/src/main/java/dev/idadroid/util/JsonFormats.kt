package dev.idadroid.util

import kotlinx.serialization.json.Json

object JsonFormats {
    val pretty: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
