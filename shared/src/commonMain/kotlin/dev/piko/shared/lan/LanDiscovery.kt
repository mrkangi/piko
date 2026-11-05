package dev.piko.shared.lan

import kotlinx.serialization.Serializable

@Serializable
data class LanDiscoveryPacket(
    val deviceId: String,
    val deviceName: String,
    val port: Int,
    val platform: String = "",
)

data class LanDiscoveredHost(
    val deviceId: String,
    val name: String,
    val address: String,
    val port: Int,
    val platform: String = "",
)

/** 局域网主机发现。UDP 广播是尽力而为；找不到时仍可用地址 + 配对码手动添加。 */
interface LanDiscovery {
    suspend fun discover(timeoutMs: Long = 3_000L): List<LanDiscoveredHost>
}
