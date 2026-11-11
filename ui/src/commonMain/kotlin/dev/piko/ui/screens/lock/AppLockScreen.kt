package dev.piko.ui.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.PikoBrandIcons
import dev.piko.ui.platform.AppLock
import dev.piko.ui.platform.PinVerification
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 冷启动恢复账号后的应用锁屏。只有输入正确的 4 位 PIN 才会离开这一页；
 * 切后台再回来不会重新进入，见 [AppLock] 的锁定语义。
 */
@Composable
fun AppLockScreen(appLock: AppLock, modifier: Modifier = Modifier) {
    BackHandler(enabled = true) {}

    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var blockedSeconds by remember { mutableIntStateOf(0) }

    val submit: (String) -> Unit = { candidate ->
        if (!verifying && blockedSeconds == 0 && candidate.length == 4) {
            verifying = true
            error = null
            scope.launch {
                when (val result = appLock.verifyPin(candidate)) {
                    PinVerification.Verified -> Unit
                    PinVerification.Incorrect -> {
                        pin = ""
                        error = tr("PIN 不正确，请重试", "Incorrect PIN, try again", "PINが正しくありません。再試行してください", "PIN이 올바르지 않습니다. 다시 시도하세요", "PIN 不正確，請重試")
                    }
                    is PinVerification.Blocked -> {
                        pin = ""
                        blockedSeconds = result.secondsRemaining
                    }
                }
                verifying = false
            }
        }
    }
    val press: (Char) -> Unit = { digit ->
        if (!verifying && blockedSeconds == 0 && pin.length < 4) {
            error = null
            val next = pin + digit
            pin = next
            if (next.length == 4) submit(next)
        }
    }

    LaunchedEffect(blockedSeconds) {
        if (blockedSeconds > 0) {
            delay(1_000)
            blockedSeconds -= 1
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val viewportHeight = maxHeight
            val keySize = if (viewportHeight < 560.dp) 56.dp else 68.dp
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight)
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = PikoBrandIcons.Logo,
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = tr("Piko 已锁定", "Piko is locked", "Pikoはロックされています", "Piko가 잠겨 있습니다", "Piko 已鎖定"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (verifying) {
                            tr("正在验证…", "Verifying…", "確認中…", "확인 중…", "正在驗證…")
                        } else {
                            tr("输入 4 位 PIN 解锁", "Enter your 4-digit PIN to unlock", "4桁のPINを入力してロックを解除", "4자리 PIN을 입력하여 잠금 해제", "輸入 4 位 PIN 解鎖")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    PinDots(filled = pin.length)
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                        val message = if (blockedSeconds > 0) {
                            tr(
                                "尝试次数过多，请在 $blockedSeconds 秒后重试",
                                "Too many attempts; try again in $blockedSeconds s",
                                "試行回数が多すぎます。$blockedSeconds 秒後に再試行してください",
                                "시도 횟수가 너무 많습니다. ${blockedSeconds}초 후 다시 시도하세요",
                                "嘗試次數過多，請在 $blockedSeconds 秒後重試",
                            )
                        } else {
                            error
                        }
                        if (message != null) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Keypad(keySize = keySize, enabled = !verifying && blockedSeconds == 0, onDigit = press, onDelete = {
                        if (!verifying) {
                            pin = pin.dropLast(1)
                            error = null
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun PinDots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f),
                    ),
            )
        }
    }
}

@Composable
private fun Keypad(keySize: Dp, enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("123", "456", "789", " 0⌫").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { key ->
                    when (key) {
                        ' ' -> Spacer(Modifier.size(keySize))
                        '⌫' -> KeyButton(enabled = enabled, onClick = onDelete, size = keySize) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = tr("删除", "Delete", "削除", "삭제", "刪除"),
                            )
                        }
                        else -> KeyButton(enabled = enabled, onClick = { onDigit(key) }, size = keySize) {
                            Text(text = key.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(size: Dp, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
