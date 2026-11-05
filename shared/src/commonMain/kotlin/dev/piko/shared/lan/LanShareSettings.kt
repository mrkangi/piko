package dev.piko.shared.lan

import dev.piko.data.auth.PikoUserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** 局域网共享的设备级设置：开关、端口、已配对主机与 host 发出的令牌，不进入网盘同步。 */
class LanShareSettings(
    private val preferences: PikoUserPreferences?,
    scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val state = MutableStateFlow(LanLocalSettings())
    val flow: StateFlow<LanLocalSettings> = state.asStateFlow()
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
    }

    suspend fun ensureLoaded() = loaded.await()

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
