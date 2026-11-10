package dev.piko.shared.jellyfin

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.JellyfinServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Jellyfin 服务器列表是设备本地配置；访问令牌另存平台机密存储。 */
class JellyfinSettings(
    private val preferences: PikoUserPreferences?,
    scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<JellyfinServer>>(emptyList())
    val flow: StateFlow<List<JellyfinServer>> = state

    init {
        scope.launch {
            preferences?.jellyfinServersFlow?.collect { raw ->
                state.value = runCatching {
                    json.decodeFromString<List<JellyfinServer>>(raw.ifBlank { "[]" })
                }.getOrDefault(emptyList())
            }
        }
    }

    fun current(): List<JellyfinServer> = state.value
    fun find(serverId: String): JellyfinServer? = state.value.firstOrNull { it.serverId == serverId }

    suspend fun upsert(server: JellyfinServer) {
        val next = state.value.filterNot { it.serverId == server.serverId } + server
        save(next)
    }

    suspend fun remove(serverId: String) {
        save(state.value.filterNot { it.serverId == serverId })
    }

    private suspend fun save(next: List<JellyfinServer>) {
        preferences?.saveJellyfinServers(json.encodeToString(next))
        state.value = next
    }
}

/** 每个 Jellyfin 服务器的设备级访问令牌。 */
interface JellyfinSecrets {
    suspend fun loadJellyfinToken(serverId: String): String? = null
    suspend fun saveJellyfinToken(serverId: String, token: String) {}
    suspend fun clearJellyfinToken(serverId: String) {}
}
