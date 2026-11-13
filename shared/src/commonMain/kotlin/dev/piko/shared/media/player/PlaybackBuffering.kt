package dev.piko.shared.media.player

/**
 * 预览播放器（信息流与段落预览）的解复用缓存预算。
 *
 * 预览同时开着三个播放器，每个都按整片播放器的 128 MiB 预算囤数据会占满账号连接与移动端内存，
 * 所以固定 8 MiB 起步，再按实测码率放宽到最多 48 MiB。低码率 720P 切片仍停在 8 MiB，
 * 行为与原先一致；没有 720P 转码、退回原画的高码率视频才拿得到更深的缓冲，
 * 避免 8 MiB 在 20 MB/s 的码流上只够零点几秒、播放头一到就反复卡顿。
 */
object PlaybackBuffering {
    private const val MIB = 1024L * 1024L

    /** 等待或暂停中的预览段保留的最小前向缓冲，与原先的固定值一致。 */
    const val PREVIEW_MIN_FORWARD_BYTES: Long = 8L * MIB

    /** 单路预览最多占用的前向缓冲；三路都到上限时合计也只有 144 MiB，不会无限膨胀。 */
    const val PREVIEW_MAX_FORWARD_BYTES: Long = 48L * MIB

    const val PREVIEW_MIN_BACK_BYTES: Long = 4L * MIB
    const val PREVIEW_MAX_BACK_BYTES: Long = 16L * MIB

    /** 字节预算最多按这么多秒的码流计算；`cache-secs` 再大也先受这个上限约束。 */
    const val PREVIEW_MAX_HORIZON_SECONDS: Int = 6

    /**
     * 按码率与播放器当前的 `cache-secs` 计算前向字节预算。
     * 码率未知（本地文件、探测不到时长）时返回 null，调用方保留后端的固定默认值。
     */
    fun previewForwardBytes(mediaBytesPerSecond: Long?, bufferSeconds: Int): Long? {
        val rate = mediaBytesPerSecond?.takeIf { it > 0L } ?: return null
        val horizon = bufferSeconds.coerceIn(1, PREVIEW_MAX_HORIZON_SECONDS)
        return (rate * horizon).coerceIn(PREVIEW_MIN_FORWARD_BYTES, PREVIEW_MAX_FORWARD_BYTES)
    }

    /** 回退缓冲按前向预算的四分之一留，够拖动与切轨回读，又不与正在播放的段抢内存。 */
    fun previewBackBytes(forwardBytes: Long): Long =
        (forwardBytes / 4).coerceIn(PREVIEW_MIN_BACK_BYTES, PREVIEW_MAX_BACK_BYTES)
}
