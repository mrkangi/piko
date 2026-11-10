package dev.piko.shared.jellyfin

import kotlinx.coroutines.CoroutineScope
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

fun createDesktopJellyfinRepository(
    settings: JellyfinSettings,
    secrets: JellyfinSecrets?,
    scope: CoroutineScope,
    clientVersion: String,
    deviceName: String,
    deviceId: String,
): JellyfinRepository {
    val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Piko", version = jellyfinHeaderValue(clientVersion))
        deviceInfo = DeviceInfo(id = "piko-$deviceId", name = jellyfinHeaderValue(deviceName))
    }
    return SdkJellyfinRepository(jellyfin, settings, secrets, scope)
}
