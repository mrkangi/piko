package dev.piko.shared.lan

import kotlinx.coroutines.flow.StateFlow

/** host 端监听状态，界面据此显示地址、端口与一次性配对码。 */
data class LanHostStatus(
    val running: Boolean = false,
    val address: String = "",
    val port: Int = 0,
    val pairingCode: String = "",
    val error: String? = null,
)

/**
 * 局域网共享 host。桌面端用 JDK 的 HttpServer 实现；Android host 以后接同一个接口。
 * 实现自己观察 [LanShareSettings.enabled]，开关变化时起停监听。
 */
interface LanShareHost {
    val status: StateFlow<LanHostStatus>

    fun start()

    fun stop()

    /** 换一个一次性配对码；已配对的客户端令牌不受影响。 */
    fun regeneratePairingCode()
}
