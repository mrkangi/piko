package dev.piko.shared.lan

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** 局域网共享的设备级设置：开关、端口、已配对主机与 host 发出的令牌，不进入网盘同步。 */
class LanShareSettings(
    private val preferences: PikoUserPreferences?,
    scope: CoroutineScope,
    private val accountProvider: () -> String? = { null },
    accountFlow: kotlinx.coroutines.flow.Flow<String?> = flowOf(accountProvider()),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val state = MutableStateFlow(LanLocalSettings())
    val flow: StateFlow<LanLocalSettings> = state.asStateFlow()
    val activePeersFlow: kotlinx.coroutines.flow.Flow<List<LanPeer>> =
        combine(flow, accountFlow) { settings, account ->
            if (account == null) settings.peers else settings.peers.filter { it.account == account }
        }
    private val loaded = CompletableDeferred<Unit>()

    init {
        if (preferences == null) {
            loaded.complete(Unit)
        } else {
            scope.launch {
                preferences.lanShareSettingsFlow.collect { raw ->
                    val next = runCatching { json.decodeFromString<LanLocalSettings>(raw.ifBlank { "{}" }) }
                        .getOrDefault(LanLocalSettings())
                    state.value = next
                    loaded.complete(Unit)
                }
            }
        }
        // 登录/切号时立刻接管旧版本没有账号字段的配对，避免 host 在用户打开网盘前拒绝旧客户端。
        scope.launch {
            accountFlow.collect { next ->
                if (next != null) {
                    loaded.await()
                    adoptLegacy()
                }
            }
        }
    }

    suspend fun ensureLoaded() {
        loaded.await()
        adoptLegacy()
    }

    fun currentAccount(): String? = accountProvider()

    fun currentPeers(): List<LanPeer> {
        val account = accountProvider() ?: return state.value.peers
        return state.value.peers.filter { it.account == account }
    }

    fun currentTokens(): List<LanHostToken> {
        val account = accountProvider() ?: return state.value.hostTokens
        return state.value.hostTokens.filter { it.account == account }
    }

    /** 旧版本的配对没有账号字段，由升级后第一个登录的账号接管。 */
    suspend fun adoptLegacy() {
        val account = accountProvider() ?: return
        lock.withLock {
            val current = state.value
            val peers = current.peers.map { if (it.account.isBlank()) it.copy(account = account) else it }
            val tokens = current.hostTokens.map { if (it.account.isBlank()) it.copy(account = account) else it }
            if (peers == current.peers && tokens == current.hostTokens) return@withLock
            val next = current.copy(peers = peers, hostTokens = tokens)
            preferences?.saveLanShareSettings(json.encodeToString(next))
            state.value = next
        }
    }

    suspend fun update(change: (LanLocalSettings) -> LanLocalSettings): LanLocalSettings {
        ensureLoaded()
        return lock.withLock {
            val next = change(state.value)
            preferences?.saveLanShareSettings(json.encodeToString(next))
            state.value = next
            next
        }
    }

    fun current(): LanLocalSettings = state.value
}
