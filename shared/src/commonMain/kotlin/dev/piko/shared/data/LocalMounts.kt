package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
enum class LocalMountKind { LOCAL, SMB }

/** SMB2/3 共享的只读位置。密码不在这里，按设备与挂载 ID 存进平台机密存储。 */
@Serializable
data class SmbMountInfo(
    val host: String,
    val port: Int = 445,
    val share: String,
    val path: String = "",
    val domain: String = "",
    val username: String = "",
) {
    init {
        require(host.isNotBlank() && host.none { it == '/' || it == '\\' })
        require(port in 1..65535)
        require(share.isNotBlank() && share.none { it == '/' || it == '\\' })
        require(path.replace('\\', '/').split('/').none { it == "." || it == ".." })
    }

    val normalizedPath: String get() = path.replace('\\', '/').trim('/')
    val authority: String get() = if (port == 445) host else "$host:$port"
    val displayPath: String get() = "smb://$authority/$share" + normalizedPath.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    val defaultName: String get() = normalizedPath.substringAfterLast('/', share).ifBlank { share }

    companion object {
        /** 输入框允许粘贴 `smb://host/share/path` 或 `\\\\host\\share`，拆成 host/share/path 三部分。 */
        fun parse(raw: String): SmbMountInfo? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            var rest = text.removePrefix("smb://").removePrefix("\\\\")
            if (rest.isEmpty()) return null
            val hostEnd = rest.indexOfFirst { it == '/' || it == '\\' }
            val hostPart = if (hostEnd < 0) rest else rest.take(hostEnd)
            rest = if (hostEnd < 0) "" else rest.drop(hostEnd + 1)
            val host = hostPart.substringBefore(':').trim()
            val port = hostPart.substringAfter(':', "445").toIntOrNull() ?: 445
            if (host.isBlank()) return null
            val parts = rest.split('/', '\\').filter { it.isNotEmpty() }
            val share = parts.firstOrNull()?.trim().orEmpty()
            if (share.isEmpty()) return null
            return runCatching {
                SmbMountInfo(host = host, port = port, share = share, path = parts.drop(1).joinToString("/"))
            }.getOrNull()
        }
    }
}

@Serializable
data class LocalMount(
    val mountId: String,
    val account: String,
    val localRootUriOrPath: String,
    val fallbackName: String,
    val createdAtMs: Long,
    val pinned: Boolean = false,
    val lanShared: Boolean = false,
    val kind: LocalMountKind = LocalMountKind.LOCAL,
    val smb: SmbMountInfo? = null,
) {
    val isSmb: Boolean get() = kind == LocalMountKind.SMB
    val location: LocalLocation get() = LocalLocation(mountId)
    fun toFileStat(thumbnailLink: String = "") = FileStat(id = location.id, name = fallbackName, parentId = DeviceLocation.SELF_ID,
        kind = io.github.nihildigit.pikpak.FileKind.FOLDER, thumbnailLink = thumbnailLink,
        params = buildMap {
            put("local_path", localRootUriOrPath)
            if (isSmb) put("local_kind", "smb")
        },
        createdTime = kotlin.time.Instant.fromEpochMilliseconds(createdAtMs).toString())
}

data class LocalMountOutcome(val mount: LocalMount, val alreadyMounted: Boolean)

/** 挂载密码的设备级保管处，不进入网盘同步。实现按账号与挂载 ID 存取。 */
interface LocalMountSecrets {
    suspend fun loadLocalMountPassword(account: String, mountId: String): String? = null
    suspend fun saveLocalMountPassword(account: String, mountId: String, password: String) {}
    suspend fun clearLocalMountPassword(account: String, mountId: String) {}
}

/** 将存储的本机路径或 Android SAF URI 转换为适合在属性面板展示的人类可读路径。 */
fun formatLocalPathForDisplay(raw: String): String {
    if (!raw.startsWith("content://")) return raw
    val marker = when {
        "/document/" in raw -> raw.substringAfterLast("/document/")
        "/tree/" in raw -> raw.substringAfterLast("/tree/")
        else -> return raw
    }.substringBefore('?').substringBefore('#')
    val decoded = decodePercentUtf8(marker)
    return when {
        decoded.startsWith("primary:") -> decoded.removePrefix("primary:").ifEmpty { "/" }
        decoded.startsWith("home:") -> "Documents/" + decoded.removePrefix("home:")
        else -> decoded
    }
}

private fun decodePercentUtf8(encoded: String): String {
    if ('%' !in encoded) return encoded
    val out = ByteArray(encoded.length)
    var writeIdx = 0
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        if (c == '%' && i + 2 < encoded.length) {
            val hi = encoded[i + 1].digitToIntOrNull(16)
            val lo = encoded[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out[writeIdx++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        val bytes = c.toString().encodeToByteArray()
        bytes.copyInto(out, writeIdx)
        writeIdx += bytes.size
        i++
    }
    return out.decodeToString(0, writeIdx)
}
/** 平台文件访问只提供读取，不暴露任何磁盘写操作。媒体缓存写入由实现封装在应用缓存目录中。 */
interface LocalFileAccess {
    fun attachMounts(mounts: LocalMounts) {}
    suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat>
    suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String
    suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String = playbackPath(mount, location)
    suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat?
    /** 只能经本机回环 HTTP 读取的来源（SMB）给出 URL；本地路径来源返回 null。 */
    suspend fun streamUrl(mount: LocalMount, location: LocalLocation): String? = null
    /** 给挂载对话框做连接测试；不支持网络挂载的来源抛异常或返回。 */
    suspend fun testSmb(info: SmbMountInfo, password: String) {}
    fun closeMount(mountId: String) {}
    fun folderCover(mount: LocalMount, location: LocalLocation = mount.location): String = ""
    fun cancelPending() {}
}

/** 挂载表属于设备，表内显式按账号隔离，不进入网盘设置同步。 */
class LocalMounts(
    private val preferences: PikoUserPreferences?,
    private val account: () -> String?,
    private val changed: (Set<String>) -> Unit,
    scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    var access: LocalFileAccess? = null
    var secrets: LocalMountSecrets? = null
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<LocalMount>>(emptyList())
    val flow: kotlinx.coroutines.flow.StateFlow<List<LocalMount>> = state
    private var cached: List<LocalMount>
        get() = state.value
        set(value) { state.value = value }
    init {
        scope.launch {
            preferences?.localMountsFlow?.collect { raw ->
                val next = runCatching { json.decodeFromString<List<LocalMount>>(raw.ifBlank { "[]" }) }.getOrDefault(emptyList())
                val old = cached
                cached = next
                if ((old + next).any { it.account == account() }) changed(setOf(DeviceLocation.SELF_ID))
            }
        }
    }
    private fun toRootStat(mount: LocalMount): FileStat =
        mount.toFileStat(thumbnailLink = runCatching { access?.folderCover(mount, mount.location).orEmpty() }.getOrDefault(""))

    fun cachedRoots(): List<FileStat> =
        cached.filter { it.account == account() }.map(::toRootStat)
    private suspend fun read(): List<LocalMount> = runCatching {
        json.decodeFromString<List<LocalMount>>(preferences?.localMountsFlow?.first().orEmpty().ifBlank { "[]" })
    }.getOrDefault(emptyList())

    suspend fun all(): List<LocalMount> = read().also { cached = it }.filter { it.account == account() }
    fun isMountedCached(folderId: String): Boolean {
        if (!LocalLocation.isLocal(folderId)) return true
        val location = LocalLocation.of(folderId) ?: return false
        return cached.any { it.account == account() && it.mountId == location.mountId }
    }

    fun findCached(id: String): LocalMount? = LocalLocation.of(id)?.let { location -> cached.firstOrNull { it.account == account() && it.mountId == location.mountId } }
    suspend fun find(id: String): LocalMount? = LocalLocation.of(id)?.let { location -> all().firstOrNull { it.mountId == location.mountId } }
    suspend fun roots(): List<FileStat> = all().map(::toRootStat)
    suspend fun mount(path: String, name: String): LocalMount =
        mountOrExisting(path, name).mount

    suspend fun mountSmb(info: SmbMountInfo, name: String, password: String): LocalMountOutcome = lock.withLock {
        require(info.host.isNotBlank() && info.share.isNotBlank())
        val owner = requireNotNull(account())
        val currentEntries = read()
        val remotePath = info.displayPath
        val existing = currentEntries.firstOrNull {
            it.account == owner && it.localRootUriOrPath == remotePath
        }
        if (existing != null) {
            if (existing.isSmb && password.isNotEmpty()) {
                requireNotNull(secrets) { dev.piko.shared.i18n.tr("挂载密码保管处不可用", "Mount password storage is unavailable", "マウントパスワードを保存できません", "마운트 비밀번호를 저장할 수 없습니다", "無法儲存掛載密碼") }
                    .saveLocalMountPassword(owner, existing.mountId, password)
                runCatching { access?.closeMount(existing.mountId) }
            }
            cached = currentEntries
            return@withLock LocalMountOutcome(existing, alreadyMounted = true)
        }
        val entry = LocalMount(
            mountId = Uuid.random().toString(),
            account = owner,
            localRootUriOrPath = remotePath,
            fallbackName = name.ifBlank { info.defaultName },
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
            kind = LocalMountKind.SMB,
            smb = info.copy(path = info.normalizedPath),
        )
        val entries = currentEntries + entry
        check(owner == account())
        requireNotNull(preferences).saveLocalMounts(json.encodeToString(entries))
        try {
            if (password.isNotEmpty()) {
                requireNotNull(secrets) { dev.piko.shared.i18n.tr("挂载密码保管处不可用", "Mount password storage is unavailable", "マウントパスワードを保存できません", "마운트 비밀번호를 저장할 수 없습니다", "無法儲存掛載密碼") }
                    .saveLocalMountPassword(owner, entry.mountId, password)
            }
        } catch (error: Throwable) {
            val rolledBack = currentEntries
            runCatching { requireNotNull(preferences).saveLocalMounts(json.encodeToString(rolledBack)) }
            cached = rolledBack
            throw error
        }
        cached = entries
        changed(setOf(DeviceLocation.SELF_ID))
        LocalMountOutcome(entry, alreadyMounted = false)
    }

    suspend fun testSmb(info: SmbMountInfo, password: String) {
        requireNotNull(access) {
            dev.piko.shared.i18n.tr("网络挂载不可用", "Network mounts are unavailable", "ネットワークマウントを利用できません", "네트워크 마운트를 사용할 수 없습니다", "網路掛載無法使用")
        }.testSmb(info, password)
    }

    suspend fun mountOrExisting(path: String, name: String): LocalMountOutcome = lock.withLock {
        require(path.isNotBlank())
        val owner = requireNotNull(account())
        val currentEntries = read()
        val existing = currentEntries.firstOrNull {
            it.account == owner && it.localRootUriOrPath == path
        }
        if (existing != null) {
            cached = currentEntries
            return@withLock LocalMountOutcome(existing, alreadyMounted = true)
        }
        val entry = LocalMount(Uuid.random().toString(), owner, path, name, Clock.System.now().toEpochMilliseconds())
        val entries = currentEntries + entry
        check(owner == account())
        requireNotNull(preferences).saveLocalMounts(json.encodeToString(entries))
        cached = entries
        changed(setOf(DeviceLocation.SELF_ID))
        LocalMountOutcome(entry, alreadyMounted = false)
    }
    suspend fun pin(id: String, pinned: Boolean) = lock.withLock {
        val entry = find(id) ?: return@withLock
        val entries = read().map { if (it.account == entry.account && it.mountId == entry.mountId) it.copy(pinned = pinned) else it }
        if (entry.account != account()) return@withLock
        preferences?.saveLocalMounts(json.encodeToString(entries))
        cached = entries
    }
    suspend fun setLanShared(id: String, shared: Boolean) = lock.withLock {
        val entry = find(id) ?: return@withLock
        val entries = read().map {
            if (it.account == entry.account && it.mountId == entry.mountId) it.copy(lanShared = shared) else it
        }
        if (entry.account != account()) return@withLock
        preferences?.saveLocalMounts(json.encodeToString(entries))
        cached = entries
        changed(setOf(DeviceLocation.SELF_ID))
    }
    fun restoredStack(stack: List<PikoPathBreadcrumb>): List<PikoPathBreadcrumb> {
        val firstLocal = stack.indexOfFirst { LocalLocation.isLocal(it.id) }
        if (firstLocal < 0) return stack
        val local = stack.drop(firstLocal).takeWhile { isMountedCached(it.id) }
        if (local.isEmpty()) return stack.take(firstLocal)
        return listOf(
            PikoDriveRepository.ROOT_BREADCRUMB,
            DeviceLocation.rootCrumb(),
            DeviceLocation.selfCrumb(),
        ) + local
    }
    suspend fun unmount(id: String) = lock.withLock {
        val entry = find(id) ?: return@withLock
        val entries = read().filterNot { it.mountId == entry.mountId && it.account == entry.account }
        if (entry.account != account()) return@withLock
        preferences?.saveLocalMounts(json.encodeToString(entries))
        runCatching { access?.closeMount(entry.mountId) }
        runCatching { secrets?.clearLocalMountPassword(entry.account, entry.mountId) }
        cached = entries
        changed(setOf(DeviceLocation.SELF_ID, entry.location.id))
    }
    suspend fun list(id: String): List<FileStat> {
        val location = requireNotNull(LocalLocation.of(id))
        val mount = find(id) ?: error("本地文件夹已取消挂载")
        return safeRead { requireNotNull(access).list(mount, location) }
    }
    suspend fun resolve(id: String, subtitle: Boolean = false): String? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: error("本地文件夹已取消挂载")
        val reader = requireNotNull(access) { "本机文件夹不可访问" }
        if (mount.isSmb) {
            if (!subtitle) return null
            return safeRead { reader.streamUrl(mount, location) }
        }
        return safeRead { if (subtitle) reader.subtitlePath(mount, location) else reader.playbackPath(mount, location) }
    }

    suspend fun streamUrl(id: String): String? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: return null
        if (!mount.isSmb) return null
        return safeRead { access?.streamUrl(mount, location) }
    }
    suspend fun probe(id: String): FileStat? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: return null
        return safeRead { access?.probe(mount, location) }
    }
    /** 文件系统异常包含本机路径，跨出访问边界时只传递不含路径的可展示错误。 */
    private suspend fun <T> safeRead(read: suspend () -> T): T = try {
        read()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw IllegalStateException(dev.piko.shared.i18n.tr("文件夹不可访问", "Folder is unavailable", "フォルダーにアクセスできません", "폴더에 접근할 수 없습니다", "資料夾無法存取"))
    }
    suspend fun pathTo(id: String): List<PikoPathBreadcrumb>? {
        val location = LocalLocation.of(id) ?: return null
        val mount = find(id) ?: return null
        return listOf(PikoPathBreadcrumb(mount.location.id, mount.fallbackName)) + location.segments.mapIndexed { i, name ->
            PikoPathBreadcrumb(LocalLocation(location.mountId, location.segments.take(i + 1)).id, name)
        }
    }
    /** [pathTo] 的同步版本，只读当前已载入的挂载表，供路径展示等非挂起调用使用。 */
    fun cachedPathTo(id: String): List<PikoPathBreadcrumb>? {
        val location = LocalLocation.of(id) ?: return null
        val mount = findCached(id) ?: return null
        return listOf(PikoPathBreadcrumb(mount.location.id, mount.fallbackName)) + location.segments.mapIndexed { i, name ->
            PikoPathBreadcrumb(LocalLocation(location.mountId, location.segments.take(i + 1)).id, name)
        }
    }
}
