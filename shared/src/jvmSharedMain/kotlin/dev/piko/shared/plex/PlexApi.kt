package dev.piko.shared.plex

import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class PlexApiException(
    val operation: String,
    val status: HttpStatusCode,
    val responseBody: String,
) : Exception("$operation returned HTTP ${status.value}")

internal class PlexTimeoutException(operation: String) : Exception("$operation timed out")

/**
 * plex.tv 与 PMS 的 HTTP 客户端。
 *
 * 所有请求都显式带 Plex 客户端头；令牌只放在请求头或播放 URL 里，不写日志。
 * 请求失败按 HTTP 状态分类，调用方据此区分临时网络故障、令牌失效和 PIN 过期。
 */
internal class PlexApi(
    private val http: HttpClient,
    private val clientIdentifier: String,
    private val clientVersion: String,
    private val deviceName: String,
    private val platform: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun clientIdentifier(): String = clientIdentifier

    suspend fun createPin(): JsonObject {
        val response = request(
            base = PLEX_TV,
            path = "/api/v2/pins",
            operation = "Plex PIN creation",
            method = Method.POST,
            params = mapOf("strong" to "true"),
        )
        return response.jsonObject
    }

    suspend fun pollPin(pinId: Long): JsonObject {
        val response = request(base = PLEX_TV, path = "/api/v2/pins/$pinId", operation = "Plex PIN polling")
        return response.jsonObject
    }

    suspend fun identity(token: String): JsonObject =
        request(base = PLEX_TV, path = "/api/v2/user", operation = "Plex account verification", token = token).jsonObject

    suspend fun homeProfiles(token: String): List<JsonObject> {
        val root = request(base = PLEX_TV, path = "/api/v2/home/users", operation = "Plex Home user listing", token = token).jsonObject
        val users = root["users"] as? JsonArray ?: root["MediaContainer"]?.jsonObject?.get("User") as? JsonArray
        return users.orEmpty().mapNotNull { it as? JsonObject }
    }

    suspend fun switchProfile(accountToken: String, profileUuid: String, pin: String? = null): JsonObject {
        val response = request(
            base = PLEX_TV,
            path = "/api/v2/home/users/$profileUuid/switch",
            operation = "Plex Home user switch",
            method = Method.POST,
            token = accountToken,
            params = buildMap {
                put("includeSubscriptions", "1")
                put("includeProviders", "1")
                put("includeSettings", "1")
                put("includeSharedSettings", "1")
                put("X-Plex-Language", "en")
                if (!pin.isNullOrBlank()) put("pin", pin)
            },
        )
        return response.jsonObject
    }

    /** 自有与共享 PMS 列表；clients 域名传输失败时回退到 plex.tv。 */
    suspend fun resources(token: String): List<JsonObject> {
        val params = mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "1")
        val body = try {
            request(base = CLIENTS_TV, path = "/api/v2/resources", operation = "Plex resource discovery", token = token, params = params)
        } catch (error: PlexApiException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 只有传输层失败才回退；401/403 不能换个域名重试。
            request(base = PLEX_TV, path = "/api/v2/resources", operation = "Plex resource discovery", token = token, params = params)
        }
        return body.jsonArray.mapNotNull { it as? JsonObject }
    }

    suspend fun sections(baseUrl: String, token: String): JsonObject =
        request(base = baseUrl, path = "/library/sections", operation = "PMS library sections", token = token).jsonObject

    suspend fun sectionItems(
        baseUrl: String,
        token: String,
        sectionId: String,
        start: Int = 0,
        size: Int = 200,
    ): JsonObject = request(
        base = baseUrl,
        path = "/library/sections/$sectionId/all",
        operation = "PMS library items",
        token = token,
        params = mapOf(
            "X-Plex-Container-Start" to start.toString(),
            "X-Plex-Container-Size" to size.toString(),
        ),
    ).jsonObject

    suspend fun children(baseUrl: String, token: String, ratingKey: String): JsonObject =
        request(base = baseUrl, path = "/library/metadata/$ratingKey/children", operation = "PMS metadata children", token = token).jsonObject

    suspend fun metadata(baseUrl: String, token: String, ratingKey: String): JsonObject = request(
        base = baseUrl,
        path = "/library/metadata/$ratingKey",
        operation = "PMS metadata",
        token = token,
        params = mapOf("checkFiles" to "1", "includeStreams" to "1"),
    ).jsonObject

    suspend fun onDeck(baseUrl: String, token: String, limit: Int = 100): JsonObject = request(
        base = baseUrl,
        path = "/library/onDeck",
        operation = "PMS On Deck",
        token = token,
        params = mapOf("X-Plex-Container-Size" to limit.toString()),
    ).jsonObject

    suspend fun timeline(
        baseUrl: String,
        token: String,
        ratingKey: String,
        positionMillis: Long,
        durationMillis: Long,
        state: String,
        sessionIdentifier: String,
    ): JsonObject = request(
        base = baseUrl,
        path = "/:/timeline",
        operation = "PMS timeline",
        method = Method.GET,
        token = token,
        params = buildMap {
            put("ratingKey", ratingKey)
            put("key", "/library/metadata/$ratingKey")
            put("time", positionMillis.toString())
            put("duration", durationMillis.toString())
            put("state", state)
        },
        extraHeaders = mapOf("X-Plex-Session-Identifier" to sessionIdentifier),
    ).jsonObject

    suspend fun probeIdentity(baseUrl: String, expectedMachineIdentifier: String): Boolean =
        try {
            val root = request(base = baseUrl, path = "/identity", operation = "PMS identity probe", timeoutMillis = PROBE_TIMEOUT_MILLIS).jsonObject
            val container = root["MediaContainer"] as? JsonObject ?: root
            container.string("machineIdentifier") == expectedMachineIdentifier
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }

    private suspend fun request(
        base: String,
        path: String,
        operation: String,
        method: Method = Method.GET,
        token: String? = null,
        params: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): JsonElement {
        val normalizedBase = base.trimEnd('/')
        val response = try {
            withTimeoutOrNull(timeoutMillis) {
                val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    headers {
                        append("Accept", "application/json")
                        append("X-Plex-Product", "Piko")
                        append("X-Plex-Client-Identifier", clientIdentifier)
                        append("X-Plex-Version", clientVersion)
                        append("X-Plex-Device-Name", deviceName)
                        append("X-Plex-Platform", platform)
                        if (!token.isNullOrBlank()) append("X-Plex-Token", token)
                        extraHeaders.forEach { (key, value) -> append(key, value) }
                    }
                    params.forEach { (key, value) -> parameter(key, value) }
                }
                when (method) {
                    Method.GET -> http.get(normalizedBase + path, builder)
                    Method.POST -> http.post(normalizedBase + path, builder)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PikoLog.w(TAG, "$operation 传输失败", error)
            throw error
        } ?: throw PlexTimeoutException(operation).also {
            PikoLog.w(TAG, "$operation 超时", it)
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw PlexApiException(operation, response.status, text).also { error ->
                val requestId = response.headers["X-Request-Id"]?.takeIf { it.length <= 128 }
                PikoLog.w(
                    TAG,
                    "$operation：HTTP ${response.status.value}" + requestId?.let { "，请求 ID $it" }.orEmpty(),
                    error,
                )
            }
        }
        return runCatching { json.parseToJsonElement(text) }.getOrElse {
            throw PlexException("$operation returned invalid JSON")
        }
    }

    private enum class Method { GET, POST }

    companion object {
        private const val TAG = "Plex"
        private const val PLEX_TV = "https://plex.tv"
        private const val CLIENTS_TV = "https://clients.plex.tv"
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val PROBE_TIMEOUT_MILLIS = 3_000L
    }
}

private fun JsonElement.string(key: String): String? = (this as? JsonObject)?.get(key)?.let { element ->
    if (element is JsonNull) return@let null
    runCatching { element.jsonPrimitive.content }.getOrNull()?.takeIf { it != "null" }
}
