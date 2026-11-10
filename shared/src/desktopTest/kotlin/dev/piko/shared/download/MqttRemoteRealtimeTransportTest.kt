package dev.piko.shared.download

import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class MqttRemoteRealtimeTransportTest {
    @Test fun exchangesPresenceRequestAndState() = smoke { scope ->
        if (System.getenv("PIKO_MQTT_PROBE") != "1") return@smoke
        val host = System.getenv("PIKO_MQTT_HOST") ?: return@smoke
        val port = System.getenv("PIKO_MQTT_PORT") ?: "8883"
        val username = System.getenv("PIKO_MQTT_USERNAME") ?: return@smoke
        val password = System.getenv("PIKO_MQTT_PASSWORD") ?: return@smoke
        val config = RemoteRealtimeConfig("mqtts://$host:$port", username, password)
        val deviceA = "d000000000000000000000001"
        val deviceB = "d000000000000000000000002"
        val a = MqttRemoteRealtimeTransport(scope)
        val b = MqttRemoteRealtimeTransport(scope)
        a.updateConfig(config)
        b.updateConfig(config)
        try {
            a.start("piko-mqtt-probe", deviceA, "Piko A", "test")
            b.start("piko-mqtt-probe", deviceB, "Piko B", "test")
            awaitUntil("EMQX 连接", timeoutMs = 20_000) { a.connected.value && b.connected.value }

            val presence = async { b.events.first { it is RemoteRealtimeEvent.Presence } }
            delay(500)
            a.publishPresence(true)
            assertEquals(deviceA, (presence.await() as RemoteRealtimeEvent.Presence).presence.deviceId)

            val request = RemoteDownloadRequest(
                requestId = "r-probe-1",
                sourceDeviceId = deviceA,
                targetDeviceId = deviceB,
                fileId = "file-1",
                fileName = "probe.mkv",
                createdAtMs = 1,
                expiresAtMs = Long.MAX_VALUE,
            )
            val receivedRequest = async { b.events.first { it is RemoteRealtimeEvent.Request } }
            delay(500)
            a.publishRequest(deviceB, request)
            assertEquals("r-probe-1", (receivedRequest.await() as RemoteRealtimeEvent.Request).request.requestId)

            val state = RemoteDownloadState(
                requestId = "r-probe-1",
                sourceDeviceId = deviceA,
                targetDeviceId = deviceB,
                status = RemoteDownloadStatus.DOWNLOADING,
                downloadedBytes = 1024,
                totalBytes = 4096,
                updatedAtMs = 1,
            )
            val receivedState = async { a.events.first { it is RemoteRealtimeEvent.State } }
            delay(500)
            b.publishState(deviceA, state)
            val actual = (receivedState.await() as RemoteRealtimeEvent.State).state
            assertEquals("r-probe-1", actual.requestId)
            assertEquals(1024L, actual.downloadedBytes)
            assertTrue(a.connected.value && b.connected.value)
        } finally {
            a.stop()
            b.stop()
        }
    }
}
