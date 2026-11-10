package dev.piko.shared.jellyfin

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/**
 * Android 的 jellyfin-core 变体要求传入 Context，否则创建时会直接抛异常；
 * 在平台入口完成构造，共享实现只拿到配置好的 Jellyfin 实例。
 */
fun createAndroidJellyfinRepository(
    context: Context,
    settings: JellyfinSettings,
    secrets: JellyfinSecrets?,
    scope: CoroutineScope,
    clientVersion: String,
    deviceName: String,
    deviceId: String,
): JellyfinRepository {
    val jellyfin = createJellyfin {
        this.context = context
        clientInfo = ClientInfo(name = "Piko", version = jellyfinHeaderValue(clientVersion))
        deviceInfo = DeviceInfo(id = "piko-$deviceId", name = jellyfinHeaderValue(deviceName))
    }
    return SdkJellyfinRepository(jellyfin, settings, secrets, scope)
}
