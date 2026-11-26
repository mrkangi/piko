package dev.piko.ui.platform

import dev.piko.shared.media.player.AdoptedPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 预览面板交给播放详情页的现成播放会话。
 *
 * 预览面板关闭时不能按常规释放后端与代理流；详情页会 [take] 这条记录并接管所有权。
 * 只在主线程读写，预览与导航都在同一条 UI 线程上。
 */
class VideoPreviewHandoff(
    val fileId: String,
    val support: VideoPreviewSupport,
    val backend: PreviewBackend,
    private val adopted: AdoptedPlayback,
) {
    private var released = false

    fun adopt(): AdoptedPlayback = adopted

    /** 详情页已经接管，释放后端；代理流由 [dev.piko.shared.media.player.PlayerScreenState] 关闭。 */
    fun release() {
        if (released) return
        released = true
        runCatching { backend.stop() }
        runCatching { backend.release() }
    }

    /** 没有被详情页取走：代理流仍由预览面板持有，必须一并关闭。 */
    fun releaseUnclaimed() {
        adopted.prepared?.let { runCatching { it.close() } }
        release()
    }
}

/**
 * 预览到详情页的一次性交接槽。按文件 ID 保存，详情页首次组合时取走。
 */
object VideoPreviewHandoffs {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, VideoPreviewHandoff>()

    fun offer(handoff: VideoPreviewHandoff) {
        pending.put(handoff.fileId, handoff)?.releaseUnclaimed()
        // 导航没到达播放页时别把后端和代理流一直挂着；正常接管会先从槽里取走。
        cleanupScope.launch {
            delay(15_000)
            if (pending[handoff.fileId] === handoff) {
                pending.remove(handoff.fileId)
                handoff.releaseUnclaimed()
            }
        }
    }

    fun take(fileId: String): VideoPreviewHandoff? = pending.remove(fileId)

    fun discard(fileId: String) {
        pending.remove(fileId)?.releaseUnclaimed()
    }
}
