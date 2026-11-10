package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase

/**
 * 网盘视图里的设备命名空间。
 *
 * 本机挂载不再散落在任意网盘目录，统一收在 `设备 / 此设备` 下；局域网主机以后作为同一层的
 * 设备节点出现。设备节点只存在于客户端视图，不是服务端文件夹。
 */
object DeviceLocation {
    const val ROOT_ID = "piko-device-root"
    const val SELF_ID = "piko-device:self"
    private const val PEER_PREFIX = "piko-device:peer-"

    fun isDevice(id: String): Boolean = id == ROOT_ID || id.startsWith("piko-device:")

    fun isSelf(id: String): Boolean = id == SELF_ID

    fun peerId(deviceId: String): String = PEER_PREFIX + deviceId

    fun peerDeviceId(id: String): String? = id.removePrefix(PEER_PREFIX)
        .takeIf { id.startsWith(PEER_PREFIX) && it.isNotEmpty() }

    fun isPeer(id: String): Boolean = peerDeviceId(id) != null

    fun rootName(): String = tr("设备", "Devices", "デバイス", "기기", "裝置")

    fun selfName(): String = tr("此设备", "This device", "この端末", "이 기기", "本機")

    fun rootCrumb(): PikoPathBreadcrumb = PikoPathBreadcrumb(ROOT_ID, rootName())

    fun selfCrumb(): PikoPathBreadcrumb = PikoPathBreadcrumb(SELF_ID, selfName())

    fun peerCrumb(deviceId: String, name: String): PikoPathBreadcrumb = PikoPathBreadcrumb(peerId(deviceId), name)

    fun rootStat(): FileStat = stat(ROOT_ID, "", rootName())

    fun selfStat(): FileStat = stat(SELF_ID, ROOT_ID, selfName())

    fun peerStat(deviceId: String, name: String): FileStat = stat(peerId(deviceId), ROOT_ID, name)

    private fun stat(id: String, parentId: String, name: String): FileStat = FileStat(
        id = id,
        parentId = parentId,
        name = name,
        kind = FileKind.FOLDER,
        size = "0",
        phase = TaskPhase.COMPLETE,
    )
}

/** 不是网盘真实条目的位置：本机挂载与设备节点。这些位置只读，也不能拿去请求服务端。 */
object VirtualLocation {
    fun isVirtual(id: String): Boolean =
        LocalLocation.isLocal(id) || DeviceLocation.isDevice(id) || LanLocation.isLan(id) || JellyfinLocation.isJellyfin(id)
}

val FileStat.isVirtual: Boolean get() = VirtualLocation.isVirtual(id)

val FileStat.isDeviceNode: Boolean get() = DeviceLocation.isDevice(id)
