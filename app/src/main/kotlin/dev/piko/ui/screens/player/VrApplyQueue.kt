package dev.piko.ui.screens.player

import dev.piko.shared.media.player.VrModeState

internal data class VrApplyRequest(
    val mode: VrModeState,
    val refreshOutput: Boolean,
)

/**
 * 把高频 VR 更新压成单线程上的最新值。执行期间到来的请求不会丢失，输出刷新标志也不会被后续拖动覆盖。
 */
internal class VrApplyQueue(
    private val execute: (() -> Unit) -> Unit,
    private val stopped: () -> Boolean,
    private val apply: (VrApplyRequest) -> Unit,
) {
    private val lock = Any()
    private var pending: VrApplyRequest? = null
    private var scheduled = false

    fun submit(mode: VrModeState, refreshOutput: Boolean = false) {
        synchronized(lock) {
            val refresh = refreshOutput || pending?.refreshOutput == true
            pending = VrApplyRequest(mode, refresh)
            if (scheduled) return
            scheduled = true
        }
        execute(::drain)
    }

    private fun drain() {
        while (!stopped()) {
            val request = synchronized(lock) {
                val next = pending
                if (next == null) {
                    scheduled = false
                    return
                }
                pending = null
                next
            }
            apply(request)
        }
        synchronized(lock) {
            pending = null
            scheduled = false
        }
    }
}
