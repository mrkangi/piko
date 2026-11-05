package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlin.io.encoding.Base64

/**
 * 局域网共享里的一个位置。路径逐段编码，和本机挂载一样不接受绝对路径、空段或父目录跳转。
 *
 * ID 里带着对端设备与共享，客户端据此找到对应的 host；共享内部的路径只相对于该共享根。
 */
data class LanLocation(
    val peerId: String,
    val shareId: String,
    val segments: List<String> = emptyList(),
    val directory: Boolean = true,
) {
    init {
        require(peerId.matches(IDENTIFIER))
        require(shareId.matches(IDENTIFIER))
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c == '\u0000' || c == ':' } })
    }

    val path: String get() = segments.joinToString("/")

    val id: String get() = (if (directory) DIR_PREFIX else FILE_PREFIX) + peerId + "/" + shareId + "/" +
        segments.joinToString("/") { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(it.encodeToByteArray()) }

    fun child(name: String, directory: Boolean) = copy(segments = segments + name, directory = directory)

    val parent: LanLocation? get() = if (segments.isEmpty()) null else copy(segments = segments.dropLast(1), directory = true)

    companion object {
        const val DIR_PREFIX = "piko-lan-dir:"
        const val FILE_PREFIX = "piko-lan-file:"
        private val IDENTIFIER = Regex("[a-zA-Z0-9_-]{1,80}")

        fun isLan(id: String): Boolean = id.startsWith(DIR_PREFIX) || id.startsWith(FILE_PREFIX)

        fun of(id: String): LanLocation? {
            if (!isLan(id)) return null
            return runCatching {
                val directory = id.startsWith(DIR_PREFIX)
                val raw = id.removePrefix(if (directory) DIR_PREFIX else FILE_PREFIX)
                val parts = raw.split('/', limit = 3)
                require(parts.size >= 2)
                val peerId = parts[0]
                val shareId = parts[1]
                val encoded = parts.getOrNull(2).orEmpty()
                val segments = if (encoded.isEmpty()) emptyList() else encoded.split('/').map {
                    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).decodeToString(throwOnInvalidSequence = true)
                }
                LanLocation(peerId, shareId, segments, directory).also { require(it.id == id) }
            }.getOrNull()
        }

        /** 日志里不能出现共享内的真实路径，只留对端与共享标识。 */
        fun logIdentity(id: String): String? = if (isLan(id)) {
            of(id)?.let { "局域网 ${it.peerId}/${it.shareId}" } ?: "局域网 ?"
        } else null
    }
}

val FileStat.isLan: Boolean get() = LanLocation.isLan(id)
