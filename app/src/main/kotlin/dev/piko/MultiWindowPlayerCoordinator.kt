package dev.piko

import android.content.Context
import android.os.SystemClock
import dev.piko.ui.VideoPlayerRequest
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3 个固定播放槽位的调度器。
 *
 * 用固定的 3 个 Activity 而不是无限开 `MULTIPLE_TASK`：槽位本身就是上限，
 * 系统里最多只会有 3 个播放任务；重复打开同一个视频直接命中已有槽位；
 * 3 个都占着时替换最早失去焦点的那个，经 `onNewIntent` 原地换片，
 * 窗口在空间里的位置与大小不会被系统丢掉。
 *
 * 只在主线程调用。
 */
object MultiWindowPlayerCoordinator {

    const val MAX_WINDOWS = 3

    private val fileIds = arrayOfNulls<String>(MAX_WINDOWS)
    private val lastFocusedAt = LongArray(MAX_WINDOWS)
    private val activities = arrayOfNulls<WeakReference<PlayerActivity>>(MAX_WINDOWS)

    private val _focusedSlot = MutableStateFlow(NO_FOCUSED_WINDOW)
    val focusedSlot: StateFlow<Int> = _focusedSlot.asStateFlow()

    /** 在独立窗口里打开；返回 false 时调用方回退到应用内播放页。 */
    fun open(context: Context, request: VideoPlayerRequest): Boolean {
        val slot = allocate(request.fileId)
        return runCatching {
            PlayerActivity.launch(context, slot, request)
            fileIds[slot] = request.fileId
            true
        }.getOrDefault(false)
    }

    internal fun attach(slot: Int, activity: PlayerActivity, request: VideoPlayerRequest) {
        activities[slot] = WeakReference(activity)
        fileIds[slot] = request.fileId
    }

    internal fun detach(slot: Int, activity: PlayerActivity) {
        val current = activities[slot]?.get()
        // 同一槽位可能已经被下一次启动接管，别把新窗口的记录清掉
        if (current != null && current !== activity) return
        activities[slot] = null
        fileIds[slot] = null
        if (_focusedSlot.value == slot) {
            _focusedSlot.value = activities.indexOfFirst { it?.get() != null }.takeIf { it >= 0 } ?: NO_FOCUSED_WINDOW
        }
    }

    internal fun onFocusGained(slot: Int) {
        lastFocusedAt[slot] = SystemClock.elapsedRealtime()
        _focusedSlot.value = slot
    }

    /** 换号或退出登录时关闭全部播放窗口：它们放的是上一个账号的文件。 */
    fun closeAll() {
        activities.forEach { reference ->
            reference?.get()?.finishAndRemoveTask()
        }
        fileIds.fill(null)
        activities.fill(null)
        _focusedSlot.value = NO_FOCUSED_WINDOW
    }

    /**
     * 同一个文件已经在某个槽位里 → 复用（并把续播位置更新到这次请求）；
     * 否则取空闲槽位；都占着时替换最久没获得过焦点的那一个。
     */
    private fun allocate(fileId: String): Int {
        fileIds.indexOf(fileId).takeIf { it >= 0 }?.let { return it }
        fileIds.indexOf(null).takeIf { it >= 0 }?.let { return it }
        return lastFocusedAt.indices.minByOrNull { lastFocusedAt[it] } ?: 0
    }
}
