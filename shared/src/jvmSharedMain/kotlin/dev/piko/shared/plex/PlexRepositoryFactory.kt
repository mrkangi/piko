package dev.piko.shared.plex

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

/** 两端共用的 Plex 仓库构造；HTTP 引擎由平台入口注入，确保代理安装在前。 */
fun createPlexRepository(
    http: HttpClient,
    settings: PlexSettings,
    secrets: PlexSecrets?,
    scope: CoroutineScope,
    clientVersion: String,
    deviceName: String,
    deviceId: String,
    platform: String,
): PlexRepository = HttpPlexRepository(
    api = PlexApi(
        http = http,
        clientIdentifier = "piko-$deviceId",
        clientVersion = plexHeaderValue(clientVersion),
        deviceName = plexHeaderValue(deviceName),
        platform = plexHeaderValue(platform),
    ),
    settings = settings,
    secrets = secrets,
    scope = scope,
)

internal fun plexHeaderValue(value: String): String =
    value.map { if (it.code in 0x20..0x7e) it else '_' }.joinToString("").trim().ifBlank { "Piko" }
