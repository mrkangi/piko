package dev.piko.ui.screens.player

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 框选填充生效时的「还原」浮窗胶囊按钮，随控件栏显隐浮在画面底部，一键还原回适应屏幕。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RestoreCropPromptButton(
    visible: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
    ) {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = playerContainerColor(),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(stringResource(Res.string.text_reset))
        }
    }
}

/**
 * VR 的常驻操作放在左边一条竖栏里：投影格式、回正视角、退出。
 *
 * 原先在画面底部居中，VR 画面铺满全屏时正好压在视线下方；挪到左侧只占一条窄栏，
 * 也避开了底栏、续播提示与中央播放键。投影格式本身是按钮，点一次换到下一种。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VrSideControls(
    visible: Boolean,
    canReset: Boolean,
    projectionLabel: String,
    onCycleProjection: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) { -it },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec()) { -it },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 168.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                .padding(start = 16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (projectionLabel.isNotEmpty()) {
                VrSideButton(
                    onClick = onCycleProjection,
                    modifier = Modifier.semantics {
                        contentDescription = tr(
                            "投影格式 $projectionLabel，点击切换",
                            "Projection $projectionLabel, tap to change",
                            "投影形式 $projectionLabel、タップで切り替え",
                            "투영 형식 $projectionLabel, 탭하여 전환",
                            "投影格式 $projectionLabel，點擊切換",
                        )
                    },
                ) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(projectionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (canReset) {
                VrSideButton(onClick = onReset) {
                    Text(tr("回正视角", "Recenter view", "視点をリセット", "시점 초기화", "視角回正"))
                }
            }
            VrSideButton(onClick = onExit) {
                Text(tr("退出 VR", "Exit VR", "VR を終了", "VR 종료", "退出 VR"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VrSideButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = playerContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
        content = content,
    )
}

/**
 * 续播提示：「从 xx 继续播放」，附带「从头播放」。
 *
 * 形式上就是一条带操作的 Snackbar：短暂、不打断播放、只有一个操作，
 * 用 Snackbar 组件本身而不是自己拼一个胶囊。位置由 [PlayerBottomStack] 统一安排。
 */
@Composable
internal fun ResumeTipCapsule(
    visible: Boolean,
    resumedPositionMillis: Long,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it },
    ) {
        // 与 SnackbarHost 里 withDismissAction 的提示保持同一形态：操作之外再给一个关闭
        Snackbar(
            action = {
                // TextButton 自带的内容色是 primary，会盖掉 Snackbar 给操作区的 inversePrimary；
                // 在反色的提示条上要用后者，与 SnackbarHost 默认的写法一致
                TextButton(
                    onClick = onRestart,
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                ) { Text(stringResource(Res.string.text_play_from_start)) }
            },
            dismissAction = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
                }
            },
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text(tr("从 ${formatTime(resumedPositionMillis)} 继续播放", "Resume at ${formatTime(resumedPositionMillis)}", "${formatTime(resumedPositionMillis)} から再生", "${formatTime(resumedPositionMillis)}부터 이어 재생", "從 ${formatTime(resumedPositionMillis)} 繼續播放"))
        }
    }
}

/**
 * 画面底部的提示区：消息提示、续播提示、框选还原自上而下排成一列。
 *
 * 三者原先各自按固定的底部间距定位，同时出现就会叠在一起。放进同一列后由布局
 * 负责让位；整列在控件栏出现时抬到底栏上方，收起后落回贴近底边的位置。
 */
@Composable
internal fun BoxScope.PlayerBottomStack(
    controlsVisible: Boolean,
    isLandscape: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val clearance by animateDpAsState(
        targetValue = when {
            controlsVisible && isLandscape -> BOTTOM_BAR_CLEARANCE_LANDSCAPE
            controlsVisible -> BOTTOM_BAR_CLEARANCE_PORTRAIT
            else -> 16.dp
        },
        animationSpec = motion.defaultSpatialSpec(),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, bottom = clearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * 锁定手势与控件的开关。锁定后它是唯一可点的控件。
 *
 * 用可切换图标按钮：锁定态换成方角并填充，和未锁定一眼能分开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LockToggle(
    isLocked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconToggleButton(
        checked = isLocked,
        onCheckedChange = { onToggle() },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (isLocked) tr("解锁屏幕", "Unlock screen", "画面ロックを解除", "화면 잠금 해제", "解除螢幕鎖定") else tr("锁定屏幕", "Lock screen", "画面をロック", "화면 잠금", "鎖定螢幕"),
        )
    }
}

/**
 * 播放失败提示：原因、重试，可选返回。
 *
 * 错误卡片出现时控件栏保持展开，返回键始终可达；卡片里的返回是给竖屏单手操作的近路。
 */
@Composable
internal fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 400.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.text_unable_to_play), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            // 卡片是不透明的 surfaceContainerHigh，取自 PlayerTheme 的深色配色，按钮用默认色即可，
            // 与底下的画面无关。重试用 tonal：一次播放失败不该渲染成需要下决心的主按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    TextButton(onClick = onBack) { Text(stringResource(Res.string.common_back)) }
                }
                FilledTonalButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            }
        }
    }
}

private val BOTTOM_BAR_CLEARANCE_PORTRAIT = 120.dp
private val BOTTOM_BAR_CLEARANCE_LANDSCAPE = 104.dp
