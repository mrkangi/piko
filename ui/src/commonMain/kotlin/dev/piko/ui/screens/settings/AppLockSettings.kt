package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.platform.AppLock
import dev.piko.ui.platform.AppLockTimeout
import dev.piko.ui.platform.PinVerification
import kotlinx.coroutines.launch

private enum class PinDialogMode { Set, Change, Disable }

private fun autoLockLabel(timeout: AppLockTimeout): String = when (timeout) {
    AppLockTimeout.NEVER -> tr("仅冷启动时锁定", "Only on cold start", "起動時のみロック", "앱 시작 시에만 잠금", "僅冷啟動時鎖定")
    AppLockTimeout.IMMEDIATELY -> tr("退到后台立即锁定", "Immediately when backgrounded", "バックグラウンド移行時に即ロック", "백그라운드 전환 즉시 잠금", "切到背景立即鎖定")
    AppLockTimeout.ONE_MINUTE -> tr("退到后台 1 分钟后", "1 minute after backgrounding", "バックグラウンド移行から1分後", "백그라운드 전환 1분 후", "切到背景 1 分鐘後")
    AppLockTimeout.FIVE_MINUTES -> tr("退到后台 5 分钟后", "5 minutes after backgrounding", "バックグラウンド移行から5分後", "백그라운드 전환 5분 후", "切到背景 5 分鐘後")
    AppLockTimeout.FIFTEEN_MINUTES -> tr("退到后台 15 分钟后", "15 minutes after backgrounding", "バックグラウンド移行から15分後", "백그라운드 전환 15분 후", "切到背景 15 分鐘後")
}

/**
 * 「同步与数据」下的隐私组。只有 Android 提供 [AppLock]，桌面端调用方不会渲染这一组。
 */
@Composable
internal fun ColumnScope.AppLockSettings(appLock: AppLock) {
    val configured by appLock.configured.collectAsStateWithLifecycle(initialValue = appLock.configured.value)
    val recentsMask by appLock.recentsMaskEnabled.collectAsStateWithLifecycle(initialValue = appLock.recentsMaskEnabled.value)
    val biometricAvailable by appLock.biometricAvailable.collectAsStateWithLifecycle(
        initialValue = appLock.biometricAvailable.value,
    )
    val biometricEnabled by appLock.biometricEnabled.collectAsStateWithLifecycle(
        initialValue = appLock.biometricEnabled.value,
    )
    val autoLockTimeout by appLock.autoLockTimeout.collectAsStateWithLifecycle(
        initialValue = appLock.autoLockTimeout.value,
    )
    val scope = rememberCoroutineScope()
    var showManage by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PinDialogMode?>(null) }
    var showAutoLock by remember { mutableStateOf(false) }

    SettingsGroup(tr("隐私与安全", "Privacy & security", "プライバシーとセキュリティ", "개인정보 및 보안", "隱私與安全")) {
        SettingsNavigationRow(
            icon = Icons.Outlined.Lock,
            title = tr("应用锁", "App lock", "アプリロック", "앱 잠금", "應用程式鎖定"),
            supporting = if (configured) {
                tr("已开启；", "On; ", "オン。", "켜짐. ", "已開啟；") + autoLockLabel(autoLockTimeout)
            } else {
                tr(
                    "启动时输入 4 位 PIN 才能进入主界面",
                    "Require a 4-digit PIN when the app starts",
                    "起動時に4桁のPINを要求します",
                    "실행 시 4자리 PIN을 요구합니다",
                    "啟動時輸入 4 位 PIN 才能進入主畫面",
                )
            },
            onClick = {
                if (configured) showManage = true else dialog = PinDialogMode.Set
            },
            trailingIcon = null,
        )
        SettingsSwitchRow(
            icon = Icons.Outlined.VisibilityOff,
            title = tr("多任务隐私遮罩", "Hide content in Recents", "最近使用したアプリで内容を隠す", "최근 앱에서 내용 숨기기", "在最近使用應用程式中隱藏內容"),
            supporting = tr(
                "切到多任务页面时隐藏应用画面",
                "Hide the app preview in the recent apps screen",
                "最近使用したアプリの画面に内容を表示しません",
                "최근 앱 화면에서 앱 내용을 숨깁니다",
                "切換至最近使用應用程式時隱藏畫面",
            ),
            checked = recentsMask,
            onCheckedChange = { scope.launch { appLock.setRecentsMaskEnabled(it) } },
        )
        if (configured) {
            if (biometricAvailable) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = tr("生物识别解锁", "Biometric unlock", "生体認証で解除", "생체 인증 잠금 해제", "生物辨識解鎖"),
                    supporting = tr(
                        "锁屏时优先使用指纹或面容，失败后仍可输入 PIN",
                        "Prefer fingerprint or face unlock, with PIN as fallback",
                        "ロック解除時に指紋や顔認証を優先し、失敗時はPINを使用します",
                        "잠금 해제 시 지문이나 얼굴 인증을 우선하고 실패하면 PIN을 사용합니다",
                        "鎖定時優先使用指紋或面容，失敗後仍可輸入 PIN",
                    ),
                    checked = biometricEnabled,
                    onCheckedChange = { scope.launch { appLock.setBiometricEnabled(it) } },
                )
            }
            SettingsNavigationRow(
                icon = Icons.Outlined.Schedule,
                title = tr("自动锁定", "Auto-lock", "自動ロック", "자동 잠금", "自動鎖定"),
                supporting = autoLockLabel(autoLockTimeout),
                onClick = { showAutoLock = true },
                trailingIcon = null,
            )
            SettingsNavigationRow(
                icon = Icons.Outlined.Lock,
                title = tr("立即锁定", "Lock now", "今すぐロック", "지금 잠그기", "立即鎖定"),
                supporting = tr(
                    "把应用切回锁屏，播放器会暂停",
                    "Lock the app now and pause playback",
                    "アプリをロック画面に戻し、再生を一時停止します",
                    "앱을 잠금 화면으로 돌리고 재생을 일시 중지합니다",
                    "將應用程式切回鎖定畫面，播放器會暫停",
                ),
                onClick = { appLock.lockNow() },
                trailingIcon = null,
            )
        }
    }

    if (showAutoLock) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.Schedule,
            title = tr("自动锁定", "Auto-lock", "自動ロック", "자동 잠금", "自動鎖定"),
            onDismiss = { showAutoLock = false },
        ) {
            AppLockTimeout.entries.forEach { timeout ->
                SettingsChoiceOption(
                    title = autoLockLabel(timeout),
                    selected = timeout == autoLockTimeout,
                    onClick = {
                        showAutoLock = false
                        scope.launch { appLock.setAutoLockTimeout(timeout) }
                    },
                )
            }
        }
    }

    if (showManage) {
        SettingsChoiceDialog(
            icon = Icons.Outlined.Lock,
            title = tr("应用锁", "App lock", "アプリロック", "앱 잠금", "應用程式鎖定"),
            onDismiss = { showManage = false },
        ) {
            SettingsChoiceOption(
                title = tr("修改 PIN", "Change PIN", "PINを変更", "PIN 변경", "修改 PIN"),
                selected = false,
                onClick = {
                    showManage = false
                    dialog = PinDialogMode.Change
                },
            )
            SettingsChoiceOption(
                title = tr("关闭应用锁", "Turn off app lock", "アプリロックを解除", "앱 잠금 끄기", "關閉應用程式鎖定"),
                selected = false,
                onClick = {
                    showManage = false
                    dialog = PinDialogMode.Disable
                },
            )
        }
    }

    dialog?.let { mode ->
        AppPinDialog(
            mode = mode,
            appLock = appLock,
            onDismiss = { dialog = null },
            onDone = { dialog = null },
        )
    }
}

@Composable
private fun AppPinDialog(
    mode: PinDialogMode,
    appLock: AppLock,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val needsCurrent = mode != PinDialogMode.Set
    val needsNew = mode != PinDialogMode.Disable
    val mismatch = needsNew && confirmPin.length == 4 && newPin != confirmPin
    val canSubmit = !busy &&
        (!needsCurrent || currentPin.length == 4) &&
        (!needsNew || (newPin.length == 4 && confirmPin.length == 4 && newPin == confirmPin))
    val title = when (mode) {
        PinDialogMode.Set -> tr("设置应用锁", "Set app lock", "アプリロックを設定", "앱 잠금 설정", "設定應用程式鎖定")
        PinDialogMode.Change -> tr("修改 PIN", "Change PIN", "PINを変更", "PIN 변경", "修改 PIN")
        PinDialogMode.Disable -> tr("关闭应用锁", "Turn off app lock", "アプリロックを解除", "앱 잠금 끄기", "關閉應用程式鎖定")
    }
    val description = when (mode) {
        PinDialogMode.Set -> tr("设置后，下次启动恢复账号时需要输入 4 位 PIN。", "After this, a 4-digit PIN is required when the app restores your account.", "設定後、次回起動時のアカウント復元に4桁のPINが必要になります。", "설정하면 다음 실행 시 계정 복구에 4자리 PIN이 필요합니다.", "設定後，下次啟動還原帳號時需要輸入 4 位 PIN。")
        PinDialogMode.Change -> tr("先输入当前 PIN，再设置新的 4 位 PIN。", "Enter the current PIN, then set a new 4-digit PIN.", "現在のPINを入力し、新しい4桁のPINを設定してください。", "현재 PIN을 입력한 뒤 새 4자리 PIN을 설정하세요.", "先輸入目前 PIN，再設定新的 4 位 PIN。")
        PinDialogMode.Disable -> tr("输入当前 PIN 以关闭应用锁。", "Enter the current PIN to turn off app lock.", "アプリロックを解除するには現在のPINを入力してください。", "앱 잠금을 끄려면 현재 PIN을 입력하세요.", "輸入目前 PIN 以關閉應用程式鎖定。")
    }
    val submit: () -> Unit = {
        if (canSubmit) {
            busy = true
            error = null
            scope.launch {
                if (needsCurrent) {
                    when (val result = appLock.verifyPin(currentPin)) {
                        PinVerification.Verified -> Unit
                        PinVerification.Incorrect -> {
                            error = tr("PIN 不正确", "Incorrect PIN", "PINが正しくありません", "PIN이 올바르지 않습니다", "PIN 不正確")
                            busy = false
                            return@launch
                        }
                        is PinVerification.Blocked -> {
                            error = tr(
                                "尝试次数过多，请在 ${result.secondsRemaining} 秒后重试",
                                "Too many attempts; try again in ${result.secondsRemaining} s",
                                "試行回数が多すぎます。${result.secondsRemaining} 秒後に再試行してください",
                                "시도 횟수가 너무 많습니다. ${result.secondsRemaining}초 후 다시 시도하세요",
                                "嘗試次數過多，請在 ${result.secondsRemaining} 秒後重試",
                            )
                            busy = false
                            return@launch
                        }
                    }
                }
                when (mode) {
                    PinDialogMode.Set, PinDialogMode.Change -> appLock.setPin(newPin)
                    PinDialogMode.Disable -> appLock.clearPin()
                }
                busy = false
                onDone()
            }
        }
    }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsCurrent) {
                    PinField(
                        value = currentPin,
                        onValueChange = { currentPin = it },
                        label = tr("当前 PIN", "Current PIN", "現在のPIN", "현재 PIN", "目前 PIN"),
                        imeAction = if (needsNew) ImeAction.Next else ImeAction.Done,
                    )
                }
                if (needsNew) {
                    PinField(
                        value = newPin,
                        onValueChange = { newPin = it },
                        label = tr("新的 4 位 PIN", "New 4-digit PIN", "新しい4桁のPIN", "새 4자리 PIN", "新的 4 位 PIN"),
                        imeAction = ImeAction.Next,
                    )
                    PinField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it },
                        label = tr("再次输入新 PIN", "Confirm new PIN", "新しいPINを再入力", "새 PIN 다시 입력", "再次輸入新 PIN"),
                        imeAction = ImeAction.Done,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
                    val message = if (mismatch) {
                        tr("两次输入的 PIN 不一致", "PINs do not match", "PINが一致しません", "PIN이 일치하지 않습니다", "兩次輸入的 PIN 不一致")
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
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = if (mode == PinDialogMode.Disable) {
                    tr("关闭", "Turn off", "解除", "끄기", "關閉")
                } else {
                    tr("保存", "Save", "保存", "저장", "儲存")
                },
                onClick = submit,
                enabled = canSubmit,
            )
        },
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it in '0'..'9' }.take(4)) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
    )
}
