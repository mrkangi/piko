package dev.piko.shared.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** 监听 host 每两秒一次的本机广播；同一广播也发往 127.0.0.1，便于单机测试。 */
class UdpLanDiscovery(
    private val discoveryPort: Int = DISCOVERY_PORT,
) : LanDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun discover(timeoutMs: Long): List<LanDiscoveredHost> = withContext(Dispatchers.IO) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(discoveryPort))
            soTimeout = 250
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val found = LinkedHashMap<String, LanDiscoveredHost>()
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = packet.data.decodeToString(packet.offset, packet.offset + packet.length)
                    val info = runCatching { json.decodeFromString<LanDiscoveryPacket>(text) }.getOrNull() ?: continue
                    found[info.deviceId] = LanDiscoveredHost(
                        deviceId = info.deviceId,
                        name = info.deviceName,
                        address = packet.address?.hostAddress ?: "",
                        port = info.port,
                        platform = info.platform,
                    )
                } catch (_: SocketTimeoutException) {
                    // 继续等到截止时间
                } catch (_: Exception) {
                    // 广播包损坏或网卡切换，继续听
                }
            }
            found.values.toList()
        } finally {
            socket.close()
        }
    }

    companion object {
        const val DISCOVERY_PORT = 47822
    }
}

/** host 端广播器，随 HTTP 服务起停。 */
class UdpLanAnnouncer(
    private val packet: LanDiscoveryPacket,
    private val discoveryPort: Int = UdpLanDiscovery.DISCOVERY_PORT,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val json = Json.encodeToString(LanDiscoveryPacket.serializer(), packet).encodeToByteArray()
        thread = Thread {
            val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
            try {
                while (running && socket != null) {
                    runCatching {
                        socket.send(DatagramPacket(json, json.size, InetAddress.getByName("255.255.255.255"), discoveryPort))
                    }
                    runCatching {
                        socket.send(DatagramPacket(json, json.size, InetAddress.getByName("127.0.0.1"), discoveryPort))
                    }
                    Thread.sleep(2_000)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                socket?.close()
            }
        }.apply {
            isDaemon = true
            name = "piko-lan-announcer"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
