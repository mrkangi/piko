package dev.piko.shared.plex

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PlexAccount
import dev.piko.shared.data.PlexServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Plex 账号与服务器列表是设备本地配置；令牌另存平台机密存储。 */
class PlexSettings(
    private val preferences: PikoUserPreferences?,
    scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<List<PlexAccount>>(emptyList())
    val flow: StateFlow<List<PlexAccount>> = state

    init {
        scope.launch {
            preferences?.plexAccountsFlow?.collect { raw ->
                state.value = runCatching {
                    json.decodeFromString<List<PlexAccount>>(raw.ifBlank { "[]" })
                }.getOrDefault(emptyList())
            }
        }
    }

    fun current(): List<PlexAccount> = state.value
    fun find(accountUuid: String): PlexAccount? = state.value.firstOrNull { it.accountUuid == accountUuid }
    fun server(mountId: String): PlexServer? = state.value.asSequence().flatMap { it.servers.asSequence() }.firstOrNull { it.mountId == mountId }

    fun accountOfServer(mountId: String): PlexAccount? =
        state.value.firstOrNull { account -> account.servers.any { it.mountId == mountId } }

    suspend fun upsert(account: PlexAccount) {
        save(state.value.filterNot { it.accountUuid == account.accountUuid } + account)
    }

    suspend fun remove(accountUuid: String) {
        save(state.value.filterNot { it.accountUuid == accountUuid })
    }

    suspend fun removeServer(mountId: String) {
        save(
            state.value.map { account ->
                if (account.servers.none { it.mountId == mountId }) account
                else account.copy(servers = account.servers.filterNot { it.mountId == mountId })
            }.filter { it.servers.isNotEmpty() },
        )
    }

    private suspend fun save(next: List<PlexAccount>) {
        preferences?.savePlexAccounts(json.encodeToString(next))
        state.value = next
    }
}
