package dev.piko.shared.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.LocalFileAccess
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalMounts
import dev.piko.shared.data.SmbMountInfo
import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLConnection
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 只读 SMB2/3 文件来源。
 *
 * 目录元数据直接来自 SMB；文件内容经 [SmbStreamServer] 暴露为回环 HTTP，播放器、字幕与图片加载器
 * 都按 Range 读取，不把整文件落到本地缓存。
 */
class SmbFileAccess(
    private val passwordFor: suspend (LocalMount) -> String?,
) : LocalFileAccess, AutoCloseable {
    private val connectionLock = Mutex()
    private val connections = HashMap<String, ActiveConnection>()
    private val knownMounts = ConcurrentHashMap<String, LocalMount>()
    private val streamServer = SmbStreamServer { mountId, segments -> openStream(mountId, segments) }

    @Volatile
    private var mounts: LocalMounts? = null

    override fun attachMounts(mounts: LocalMounts) {
        this.mounts = mounts
    }

    override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> = withContext(Dispatchers.IO) {
        val info = mount.smb ?: error("SMB 挂载信息缺失")
        remember(mount)
        val directory = pathFor(info, location.segments)
        val entries = runCatching { withShare(mount, info) { share -> share.list(directory) } }.getOrElse {
            throw IllegalStateException(
                tr(
                    "无法读取 SMB 目录",
                    "Unable to read the SMB folder",
                    "SMB フォルダーを読み取れません",
                    "SMB 폴더를 읽을 수 없습니다",
                    "無法讀取 SMB 目錄",
                ),
                it,
            )
        }
        entries.mapNotNull { entry ->
            val name = entry.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val attributes = entry.fileAttributes
            if ((attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value) != 0L) return@mapNotNull null
            val folder = (attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val child = runCatching { location.child(name, folder) }.getOrNull() ?: return@mapNotNull null
            toStat(mount, child, name, entry.endOfFile, entry.lastWriteTime.toEpochMillis(), entry.creationTime.toEpochMillis(), folder)
        }
    }

    override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String =
        error(tr("SMB 文件请通过流地址读取", "Read SMB files through the stream URL", "SMB ファイルはストリーム URL から読み取ります", "SMB 파일은 스트림 주소로 읽습니다", "SMB 檔案請透過串流位址讀取"))

    override suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String =
        streamUrl(mount, location) ?: error("SMB 字幕不可访问")

    override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? = withContext(Dispatchers.IO) {
        val info = mount.smb ?: return@withContext null
        remember(mount)
        val path = pathFor(info, location.segments)
        val all = runCatching { withShare(mount, info) { share -> share.getFileInformation(path) } }.getOrNull()
            ?: return@withContext null
        val name = location.segments.lastOrNull() ?: mount.fallbackName
        toStat(
            mount = mount,
            location = location,
            name = name,
            size = all.standardInformation.endOfFile,
            modifiedMs = all.basicInformation.lastWriteTime.toEpochMillis(),
            createdMs = all.basicInformation.creationTime.toEpochMillis(),
            folder = all.standardInformation.isDirectory,
        )
    }

    override suspend fun streamUrl(mount: LocalMount, location: LocalLocation): String? {
        if (!mount.isSmb) return null
        remember(mount)
        return streamServer.url(mount.mountId, location.segments)
    }

    override suspend fun testSmb(info: SmbMountInfo, password: String) {
        withContext(Dispatchers.IO) {
            val connection = connect(info, password)
            try {
                connection.share.list(info.normalizedPath)
            } finally {
                connection.close()
            }
        }
    }

    override fun closeMount(mountId: String) {
        knownMounts.remove(mountId)
        val connection = synchronized(connections) { connections.remove(mountId) }
        runCatching { connection?.close() }
    }

    override fun cancelPending() {
        knownMounts.clear()
        val snapshot = synchronized(connections) {
            connections.values.toList().also { connections.clear() }
        }
        snapshot.forEach { runCatching { it.close() } }
    }

    override fun close() {
        cancelPending()
        runCatching { streamServer.close() }
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
            modifiedTime = java.time.Instant.ofEpochMilli(modifiedMs).toString(),
            createdTime = java.time.Instant.ofEpochMilli(createdMs).toString(),
            phase = TaskPhase.COMPLETE,
            fileExtension = extension,
            params = buildMap {
                put("local_path", "${mount.localRootUriOrPath}/${location.segments.joinToString("/")}".trimEnd('/'))
                put("local_kind", "smb")
            },
        )
        return if (!folder && name.isPreviewableImage()) {
            base.copy(thumbnailLink = streamServer.url(mount.mountId, location.segments))
        } else {
            base
        }
    }

    private suspend fun shareFor(mount: LocalMount, info: SmbMountInfo): DiskShare = connectionLock.withLock {
        val signature = "${info.host}:${info.port}/${info.share}|${info.domain}|${info.username}"
        synchronized(connections) { connections[mount.mountId] }
            ?.takeIf { it.signature == signature }
            ?.let { return@withLock it.share }
        val next = withContext(Dispatchers.IO) {
            val password = passwordFor(mount).orEmpty()
            connect(info, password)
        }
        synchronized(connections) { connections.put(mount.mountId, next) }?.let { runCatching { it.close() } }
        next.share
    }

    /**
     * 连接掉线后同一个 DiskShare 会一直失败。这里把失败视为连接失效，关闭后重连一次；
     * 认证错误等永久失败只会多一次连接尝试，随后仍把错误交给调用方。
     */
    private suspend fun <T> withShare(
        mount: LocalMount,
        info: SmbMountInfo,
        block: suspend (DiskShare) -> T,
    ): T {
        val first = shareFor(mount, info)
        return try {
            block(first)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (firstError: Exception) {
            invalidate(mount.mountId, first)
            val second = shareFor(mount, info)
            try {
                block(second)
            } catch (secondError: Exception) {
                secondError.addSuppressed(firstError)
                throw secondError
            }
        }
    }

    private fun invalidate(mountId: String, share: DiskShare) {
        val stale = synchronized(connections) {
            val current = connections[mountId]
            if (current?.share === share) connections.remove(mountId) else null
        }
        runCatching { stale?.close() }
    }

    private suspend fun openStream(mountId: String, segments: List<String>): SmbReadHandle? {
        val mount = mounts?.findCached(mountId)?.takeIf { it.isSmb } ?: knownMounts[mountId] ?: return null
        val info = mount.smb ?: return null
        return withShare(mount, info) { share ->
            val file = openFile(share, pathFor(info, segments))
            try {
                val all = file.getFileInformation()
                val name = segments.lastOrNull() ?: mount.fallbackName
                SmbReadHandle(
                    name = name,
                    size = all.standardInformation.endOfFile,
                    modifiedMs = all.basicInformation.lastWriteTime.toEpochMillis(),
                    readAt = { offset, buffer, offsetInBuffer, length -> file.read(buffer, offset, offsetInBuffer, length) },
                    closeBlock = { runCatching { file.close() } },
                )
            } catch (error: Throwable) {
                runCatching { file.close() }
                throw error
            }
        }
    }

    private fun connect(info: SmbMountInfo, password: String): ActiveConnection {
        val client = SMBClient(CONFIG)
        var connection: Connection? = null
        var session: Session? = null
        try {
            connection = client.connect(info.host, info.port)
            val authentication = if (info.username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(info.username, password.toCharArray(), info.domain.ifBlank { null })
            }
            session = connection.authenticate(authentication)
            val share = session.connectShare(info.share) as? DiskShare
                ?: error(tr("SMB 共享不可访问", "SMB share is unavailable", "SMB 共有にアクセスできません", "SMB 공유에 접근할 수 없습니다", "SMB 共用無法存取"))
            return ActiveConnection(
                signature = "${info.host}:${info.port}/${info.share}|${info.domain}|${info.username}",
                client = client,
                connection = connection,
                session = session,
                share = share,
            )
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            if (error is IllegalStateException || error is IllegalArgumentException) throw error
            throw IllegalStateException(
                tr(
                    "无法连接 SMB 共享",
                    "Unable to connect to the SMB share",
                    "SMB 共有に接続できません",
                    "SMB 공유에 연결할 수 없습니다",
                    "無法連線 SMB 共用",
                ),
                error,
            )
        }
    }

    private fun openFile(share: DiskShare, path: String): SmbFile = runCatching {
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ) as SmbFile
    }.getOrElse {
        throw IllegalStateException(
            tr("SMB 文件不可访问", "SMB file is unavailable", "SMB ファイルにアクセスできません", "SMB 파일에 접근할 수 없습니다", "SMB 檔案無法存取"),
            it,
        )
    }

    private fun pathFor(info: SmbMountInfo, segments: List<String>): String {
        // Windows/SMB 把反斜杠当分隔符，不能把它当作普通文件名字符放过去。
        require(segments.none { '\\' in it }) { "SMB 文件名包含反斜杠" }
        return (listOf(info.normalizedPath) + segments).filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun remember(mount: LocalMount) {
        if (mount.isSmb) knownMounts[mount.mountId] = mount
    }

    private class ActiveConnection(
        val signature: String,
        private val client: SMBClient,
        private val connection: Connection,
        private val session: Session,
        val share: DiskShare,
    ) : AutoCloseable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private companion object {
        val CONFIG: SmbConfig = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
