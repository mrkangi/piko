package dev.piko.shared.download

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.sync.JvmSyncCipher
import dev.piko.shared.sync.RemoteSettingsStore
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteRealtimeConfigStoreTest {
    @Test
    fun encryptsRoundTripsAndClearsConfig() = runBlocking {
        val remote = MemoryRemote()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clients = object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(null)
        }
        fun store() = RemoteRealtimeConfigStore(
            clients = clients,
            remote = remote,
            passwordOf = { "account-password" },
            cipher = JvmSyncCipher(),
            scope = scope,
            accountProvider = { "account" },
        )

        val config = RemoteRealtimeConfig("mqtts://broker.example:8883", "piko", "secret-password")
        assertTrue(store().save(config).isSuccess)
        val stored = remote.text.orEmpty()
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains("secret-password"))
        assertFalse(stored.contains("broker.example"))

        val anotherDevice = store()
        anotherDevice.refresh()
        assertEquals(config, anotherDevice.config.value)

        assertTrue(anotherDevice.clear().isSuccess)
        val thirdDevice = store()
        thirdDevice.refresh()
        assertNull(thirdDevice.config.value)
    }

    private class MemoryRemote : RemoteSettingsStore {
        var text: String? = null
        override suspend fun read(account: String): String? = text
        override suspend fun write(account: String, text: String, stamp: Long) {
            this.text = text
        }
    }
}
