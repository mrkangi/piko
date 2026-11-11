package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState

/**
 * 任务页的顶层页签。前五项仍按 [TransferKind] 筛选本地任务，远程下载单独一页，
 * 避免把设备请求混进文件任务的类型枚举。
 */
internal sealed interface TransferTab {
    val label: String
    /** Pager 会把 item key 注册进 SaveableStateHolder，必须是 Bundle 可存类型。 */
    val saveKey: String
    fun icon(selected: Boolean): ImageVector

    data class Kind(val kind: TransferKind) : TransferTab {
        override val label: String get() = kind.label
        override val saveKey: String get() = kind.name
        override fun icon(selected: Boolean): ImageVector = kind.icon(selected)
    }

    data object Remote : TransferTab {
        override val label: String get() = tr("远程", "Remote", "リモート", "원격", "遠端")
        override val saveKey: String get() = "remote"
        override fun icon(selected: Boolean): ImageVector = Icons.Outlined.DevicesOther
    }
}

internal val transferTabs: List<TransferTab> =
    TransferKind.entries.map { TransferTab.Kind(it) } + TransferTab.Remote

internal fun TransferTab.count(state: TransfersState, remoteCount: Int): Int = when (this) {
    is TransferTab.Kind -> state.counts[kind] ?: 0
    TransferTab.Remote -> remoteCount
}
