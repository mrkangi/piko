package dev.piko.shared.jellyfin

/** X-Emby-Authorization 是 HTTP 头，只能放 ASCII；设备名里的「·」等字符会让请求直接失败。 */
internal fun jellyfinHeaderValue(value: String): String =
    value.map { if (it.code in 0x20..0x7e) it else '_' }.joinToString("").trim().ifBlank { "Piko" }
