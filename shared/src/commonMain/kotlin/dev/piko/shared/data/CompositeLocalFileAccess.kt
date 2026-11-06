package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat

/** 本机路径/SAF 与 SMB 各有一个来源，按挂载类型分派。 */
class CompositeLocalFileAccess(
    private val local: LocalFileAccess?,
    private val smb: LocalFileAccess?,
) : LocalFileAccess {
    private fun source(mount: LocalMount): LocalFileAccess = when {
        mount.isSmb -> requireNotNull(smb) { "SMB 挂载不可用" }
        else -> requireNotNull(local) { "本机文件夹不可访问" }
    }

    override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> =
        source(mount).list(mount, location)

    override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String =
        source(mount).playbackPath(mount, location)

    override suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String =
        source(mount).subtitlePath(mount, location)

    override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? =
        source(mount).probe(mount, location)

    override suspend fun streamUrl(mount: LocalMount, location: LocalLocation): String? =
        source(mount).streamUrl(mount, location)

    override suspend fun testSmb(info: SmbMountInfo, password: String) {
        requireNotNull(smb) { "SMB 挂载不可用" }.testSmb(info, password)
    }

    override fun closeMount(mountId: String) {
        local?.closeMount(mountId)
        smb?.closeMount(mountId)
    }

    override fun folderCover(mount: LocalMount, location: LocalLocation): String {
        if (mount.isSmb) return smb?.folderCover(mount, location).orEmpty()
        return local?.folderCover(mount, location).orEmpty()
    }

    override fun cancelPending() {
        local?.cancelPending()
        smb?.cancelPending()
    }
}
