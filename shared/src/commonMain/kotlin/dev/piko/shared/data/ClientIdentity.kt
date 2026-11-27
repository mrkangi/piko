package dev.piko.shared.data

import kotlin.random.Random

private const val HEX = "0123456789abcdef"

/** 本机稳定设备身份，供 Jellyfin、Plex 与局域网共享使用。 */
fun newClientDeviceId(): String = buildString {
    append('d')
    repeat(24) { append(HEX[Random.nextInt(HEX.length)]) }
}
