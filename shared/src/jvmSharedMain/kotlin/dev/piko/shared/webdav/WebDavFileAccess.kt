package dev.piko.shared.webdav

import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.LocalFileAccess
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalMounts
import dev.piko.shared.data.WebDavMountInfo
import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.URLConnection
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

/**
 * 只读 WebDAV 文件来源。
 *
 * 目录与文件元数据走 `PROPFIND`；文件内容经 [WebDavStreamServer] 暴露为回环 HTTP，
 * 播放器、字幕与图片加载器按 Range 读取，凭据不会进入交给播放器的地址。
 */
class WebDavFileAccess(
    private val http: () -> HttpClient,
    private val passwordFor: suspend (LocalMount) -> String?,
) : LocalFileAccess, AutoCloseable {
    private val client: HttpClient by lazy(http)
    private val knownMounts = ConcurrentHashMap<String, LocalMount>()
    private val streamServer = WebDavStreamServer({ client }) { mountId, segments -> targetOf(mountId, segments) }

    @Volatile
    private var mounts: LocalMounts? = null

    override fun attachMounts(mounts: LocalMounts) {
        this.mounts = mounts
    }

    override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> = withContext(Dispatchers.IO) {
        val info = mount.webDav ?: error("WebDAV 挂载信息缺失")
        remember(mount)
        val responses = runCatching { propfind(mount, targetUrl(info, location.segments), depth = 1) }.getOrElse {
            throw IllegalStateException(
                tr(
                    "无法读取 WebDAV 目录",
                    "Unable to read the WebDAV folder",
                    "WebDAV フォルダーを読み取れません",
                    "WebDAV 폴더를 읽을 수 없습니다",
                    "無法讀取 WebDAV 目錄",
                ),
                it,
            )
        }
        val basePath = pathOf(info.url)
        responses.mapNotNull { response ->
            val href = textOf(response, "href") ?: return@mapNotNull null
            if (pathOf(href) == basePath) return@mapNotNull null
            val folder = isCollection(response)
            val name = textOf(response, "displayname")?.takeIf { it.isNotBlank() } ?: lastSegmentOf(href)
            if (name.isBlank()) return@mapNotNull null
            val child = runCatching { location.child(name, folder) }.getOrNull() ?: return@mapNotNull null
            val modified = parseHttpDate(textOf(response, "getlastmodified"))
            toStat(mount, child, name, sizeOf(textOf(response, "getcontentlength"), folder), modified, modified, folder)
        }
    }

    override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String =
        error(tr("WebDAV 文件请通过流地址读取", "Read WebDAV files through the stream URL", "WebDAV ファイルはストリーム URL から読み取ります", "WebDAV 파일은 스트림 주소로 읽습니다", "WebDAV 檔案請透過串流位址讀取"))

    override suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String =
        streamUrl(mount, location) ?: error("WebDAV 字幕不可访问")

    override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? = withContext(Dispatchers.IO) {
        val info = mount.webDav ?: return@withContext null
        remember(mount)
        val response = runCatching { propfind(mount, targetUrl(info, location.segments), depth = 0).firstOrNull() }
            .getOrNull() ?: return@withContext null
        val folder = isCollection(response)
        val name = location.segments.lastOrNull() ?: mount.fallbackName
        val modified = parseHttpDate(textOf(response, "getlastmodified"))
        toStat(mount, location.copy(directory = folder), name, sizeOf(textOf(response, "getcontentlength"), folder), modified, modified, folder)
    }

    override suspend fun streamUrl(mount: LocalMount, location: LocalLocation): String? {
        if (!mount.isWebDav) return null
        remember(mount)
        return streamServer.url(mount.mountId, location.segments)
    }

    override suspend fun testWebDav(info: WebDavMountInfo, password: String) {
        withContext(Dispatchers.IO) {
            val probe = LocalMount(
                mountId = "probe",
                account = "",
                localRootUriOrPath = info.url,
                fallbackName = info.defaultName,
                createdAtMs = 0L,
                kind = dev.piko.shared.data.LocalMountKind.WEBDAV,
                webDav = info,
            )
            val responses = propfind(probe, info.url, depth = 0, password = password)
            if (responses.isEmpty()) {
                throw IllegalStateException(tr("服务器没有返回目录信息", "The server returned no folder information", "サーバーがフォルダー情報を返しませんでした", "서버가 폴더 정보를 반환하지 않았습니다", "伺服器未傳回目錄資訊"))
            }
        }
    }

    override fun closeMount(mountId: String) {
        knownMounts.remove(mountId)
    }

    override fun cancelPending() {
        knownMounts.clear()
    }

    override fun close() {
        cancelPending()
        runCatching { streamServer.close() }
    }

    private fun remember(mount: LocalMount) {
        if (mount.isWebDav) knownMounts[mount.mountId] = mount
    }

    private suspend fun targetOf(mountId: String, segments: List<String>): WebDavTarget? {
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '/' in it || '\u0000' in it }) return null
        val mount = knownMounts[mountId]
            ?: mounts?.all()?.firstOrNull { it.mountId == mountId && it.isWebDav }
            ?: return null
        val info = mount.webDav ?: return null
        return WebDavTarget(targetUrl(info, segments), authHeader(mount))
    }

    private suspend fun propfind(
        mount: LocalMount,
        url: String,
        depth: Int,
        password: String? = null,
    ): List<Element> {
        val authorization = authHeader(mount, password)
        val response = client.prepareRequest(url) {
            method = HttpMethod("PROPFIND")
            header("Depth", depth.toString())
            contentType(ContentType.Application.Xml)
            authorization?.let { header(HttpHeaders.Authorization, it) }
            setBody(PROPFIND_BODY)
        }.execute { result ->
            val text = result.bodyAsText()
            check(result.status.isSuccess()) {
                tr("WebDAV 请求失败", "WebDAV request failed", "WebDAV リクエストに失敗しました", "WebDAV 요청에 실패했습니다", "WebDAV 請求失敗") +
                    " (HTTP ${result.status.value})"
            }
            text
        }
        return parsePropfind(response)
    }

    private suspend fun authHeader(mount: LocalMount, password: String? = null): String? {
        val username = mount.webDav?.username.orEmpty()
        if (username.isEmpty()) return null
        val secret = password ?: passwordFor(mount).orEmpty()
        val raw = "$username:$secret".encodeToByteArray()
        return "Basic " + Base64.Default.encode(raw)
    }

    private suspend fun toStat(
        mount: LocalMount,
        location: LocalLocation,
        name: String,
        size: Long,
        modifiedMs: Long,
        createdMs: Long,
        folder: Boolean,
    ): FileStat {
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val base = FileStat(
            id = location.copy(directory = folder).id,
            parentId = location.parent?.id ?: DeviceLocation.SELF_ID,
            name = name,
            kind = if (folder) FileKind.FOLDER else FileKind.FILE,
            size = if (folder) "0" else size.toString(),
            mimeType = if (folder) "" else URLConnection.guessContentTypeFromName(name).orEmpty(),
            modifiedTime = if (modifiedMs > 0) Instant.ofEpochMilli(modifiedMs).toString() else "",
            createdTime = if (createdMs > 0) Instant.ofEpochMilli(createdMs).toString() else "",
            phase = TaskPhase.COMPLETE,
            fileExtension = extension,
            params = buildMap {
                put("local_path", "${mount.localRootUriOrPath}/${location.segments.joinToString("/")}".trimEnd('/'))
                put("local_kind", "webdav")
            },
        )
        return if (!folder && name.isPreviewableImage()) {
            base.copy(thumbnailLink = streamServer.url(mount.mountId, location.segments))
        } else {
            base
        }
    }

    private companion object {
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/>
</d:prop></d:propfind>"""

        fun targetUrl(info: WebDavMountInfo, segments: List<String>): String =
            if (segments.isEmpty()) info.url else info.url + "/" + segments.joinToString("/") { encodeSegment(it) }

        fun encodeSegment(value: String): String {
            val bytes = value.encodeToByteArray()
            val out = StringBuilder(bytes.size)
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '.' || char == '_' || char == '~') {
                    out.append(char)
                } else {
                    out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                }
            }
            return out.toString()
        }

        const val HEX = "0123456789ABCDEF"

        fun parsePropfind(xml: String): List<Element> {
            if (xml.isBlank()) return emptyList()
            return runCatching {
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { factory.isExpandEntityReferences = false }
                val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
                descendantsNamed(document.documentElement, "response")
            }.getOrDefault(emptyList())
        }

        fun childElements(parent: Element): List<Element> {
            val out = ArrayList<Element>()
            val nodes = parent.childNodes
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? Element)?.let(out::add)
            }
            return out
        }

        fun localNameOf(element: Element): String = element.localName ?: element.tagName.substringAfter(':')

        fun elementsNamed(parent: Element, name: String): List<Element> =
            childElements(parent).filter { localNameOf(it) == name }

        fun descendantsNamed(parent: Element, name: String): List<Element> {
            val out = ArrayList<Element>()
            fun walk(element: Element) {
                childElements(element).forEach { child ->
                    if (localNameOf(child) == name) out += child
                    walk(child)
                }
            }
            walk(parent)
            return out
        }

        fun textOf(parent: Element, name: String): String? =
            descendantsNamed(parent, name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

        fun isCollection(response: Element): Boolean {
            val type = descendantsNamed(response, "resourcetype").firstOrNull() ?: return false
            return elementsNamed(type, "collection").isNotEmpty()
        }

        fun sizeOf(raw: String?, folder: Boolean): Long = if (folder) 0L else raw?.toLongOrNull() ?: 0L

        fun pathOf(url: String): String {
            val path = if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                url.substringAfter("://").substringAfter('/', "")
            } else {
                url.removePrefix("/")
            }
            val decoded = decodePercent(path.substringBefore('?').substringBefore('#'))
            return "/" + decoded.trim('/')
        }

        fun lastSegmentOf(href: String): String {
            val path = href.substringBefore('?').substringBefore('#').trimEnd('/')
            return decodePercent(path.substringAfterLast('/'))
        }

        fun decodePercent(value: String): String {
            if ('%' !in value) return value
            val out = ByteArray(value.length)
            var write = 0
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%' && index + 2 < value.length) {
                    val hi = value[index + 1].digitToIntOrNull(16)
                    val lo = value[index + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        out[write++] = ((hi shl 4) or lo).toByte()
                        index += 3
                        continue
                    }
                }
                val bytes = char.toString().encodeToByteArray()
                bytes.copyInto(out, write)
                write += bytes.size
                index++
            }
            return out.decodeToString(0, write)
        }

        fun parseHttpDate(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            return runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(raw).toEpochMilli() }
                .getOrDefault(0L)
        }
    }
}
