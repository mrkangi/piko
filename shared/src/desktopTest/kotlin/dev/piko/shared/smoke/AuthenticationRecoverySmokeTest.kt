package dev.piko.shared.smoke

import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticationRecoverySmokeTest {
    @Test
    fun managerRecoverySignsInAgainEvenWhenTheOldSessionStillLooksValid() = smoke { scope ->
        val server = FakePikPakServer()
        val sessions = MemorySessionStore()
        val manager = PikoClientManager(sessions, scope, server.httpClient())
        val client = manager.login("user@piko.dev") { "pw" }.getOrThrow()
        val rejected = client.currentSession
        assertEquals(1, server.count("POST /v1/auth/signin"))

        assertTrue(manager.recoverAuthentication(client, rejected))

        assertEquals(2, server.count("POST /v1/auth/signin"))
    }

    @Test
    fun cloudReadRetriesOnceAfterAuthenticationFailure() = runBlocking {
        val server = FakePikPakServer()
        val client = server.client()
        var recoveries = 0
        val provider = object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)

            override suspend fun recoverAuthentication(client: PikPakClient, rejectedSession: Session?): Boolean {
                recoveries++
                return true
            }
        }
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        var attempts = 0

        val result = repository.withAuthenticationRecovery(client) {
            attempts++
            if (attempts == 1) throw PikPakException(-1, "HTTP 400", httpStatus = 400)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(1, recoveries)
    }

    @Test
    fun businessFailureIsNotRetriedAsAuthenticationFailure() = runBlocking {
        val server = FakePikPakServer()
        val client = server.client()
        var recoveries = 0
        val provider = object : PikoClientProvider {
            override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(client)

            override suspend fun recoverAuthentication(client: PikPakClient, rejectedSession: Session?): Boolean {
                recoveries++
                return true
            }
        }
        val repository = PikoDriveRepository(provider, MemoryPreferences())

        val failure = runCatching {
            repository.withAuthenticationRecovery(client) {
                throw PikPakException(3, "file_not_found", httpStatus = 400)
            }
        }.exceptionOrNull()
        assertTrue(failure is PikPakException)
        assertEquals(0, recoveries)
    }
}
