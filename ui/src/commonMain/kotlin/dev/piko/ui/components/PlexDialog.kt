package dev.piko.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.PlexAccount
import dev.piko.shared.data.PlexHomeProfile
import dev.piko.shared.i18n.tr
import dev.piko.shared.plex.PlexAccountIdentity
import dev.piko.shared.plex.PlexDiscoveredServer
import dev.piko.shared.plex.PlexPin
import dev.piko.shared.plex.PlexPinPoll
import dev.piko.shared.plex.PlexQrEncoder
import dev.piko.shared.plex.PlexQrMatrix
import dev.piko.shared.plex.PlexRepository
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Plex 登录：PIN 短码、浏览器/二维码、Home 用户 PIN、服务器选择。
 * 登录成功后由调用方负责跳转和刷新，对话框不直接依赖网盘状态。
 */
@Composable
fun PlexLoginDialog(
    repository: PlexRepository,
    qrEncoder: PlexQrEncoder?,
    onDismiss: () -> Unit,
    onAdded: (PlexAccount) -> Unit,
) {
    val platform = LocalPikoPlatform.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(LoginStep.Start) }
    var pin by remember { mutableStateOf<PlexPin?>(null) }
    var accountToken by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf<PlexAccountIdentity?>(null) }
    var profiles by remember { mutableStateOf<List<PlexHomeProfile>>(emptyList()) }
    var selectedProfile by remember { mutableStateOf<PlexHomeProfile?>(null) }
    var selectedProfileUuid by remember { mutableStateOf("") }
    var profileToken by remember { mutableStateOf("") }
    var homePin by remember { mutableStateOf("") }
    var homePinError by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<PlexDiscoveredServer>>(emptyList()) }
    var selectedMachines by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadServers(token: String) {
        val servers = repository.discoverServers(token)
        discovered = servers
        selectedMachines = servers.map { it.server.machineIdentifier }.toSet()
        step = LoginStep.Servers
    }

    fun start() {
        scope.launch {
            busy = true
            error = null
            runCatching { repository.createPin() }
                .onSuccess {
                    pin = it
                    step = LoginStep.Pin
                }
                .onFailure { error = it.message ?: "Plex PIN" }
            busy = false
        }
    }

    fun selectProfile(profile: PlexHomeProfile?) {
        selectedProfile = profile
        selectedProfileUuid = profile?.uuid ?: ""
        profileToken = ""
        homePin = ""
        homePinError = false
        if (profile?.protected == true) {
            step = LoginStep.HomePin
            return
        }
        scope.launch {
            busy = true
            error = null
            runCatching {
                val token = if (profile == null) accountToken else repository.switchProfile(accountToken, profile.uuid)
                profileToken = token
                loadServers(token)
            }.onFailure {
                error = it.message ?: tr("Plex 家庭用户切换失败", "Plex Home switch failed", "Plex Home の切り替えに失敗しました", "Plex Home 전환에 실패했습니다", "Plex 家庭使用者切換失敗")
            }
            busy = false
        }
    }

    fun confirmHomePin() {
        val profile = selectedProfile ?: return
        scope.launch {
            busy = true
            error = null
            homePinError = false
            runCatching {
                val token = repository.switchProfile(accountToken, profile.uuid, homePin)
                profileToken = token
                loadServers(token)
            }.onFailure {
                homePinError = true
                error = it.message ?: tr("PIN 不正确", "Incorrect PIN", "PIN が正しくありません", "PIN이 올바르지 않습니다", "PIN 不正確")
            }
            busy = false
        }
    }

    fun add() {
        val account = identity ?: return
        val selected = discovered.filter { it.server.machineIdentifier in selectedMachines }
        if (selected.isEmpty()) {
            error = tr("请选择至少一台服务器", "Select at least one server", "サーバーを 1 台以上選択してください", "서버를 하나 이상 선택하세요", "請選擇至少一台伺服器")
            return
        }
        scope.launch {
            busy = true
            error = null
            runCatching {
                val tokenForProfile = profileToken.ifBlank {
                    if (selectedProfile == null) accountToken
                    else repository.switchProfile(accountToken, selectedProfile!!.uuid, homePin.ifBlank { null })
                }
                repository.addAccount(
                    accountToken = accountToken,
                    identity = account,
                    profiles = profiles,
                    profile = selectedProfile,
                    profileToken = tokenForProfile,
                    servers = selected,
                )
            }.onSuccess {
                onAdded(it)
                onDismiss()
            }.onFailure {
                error = it.message ?: tr("添加 Plex 服务器失败", "Failed to add Plex server", "Plex サーバーの追加に失敗しました", "Plex 서버 추가에 실패했습니다", "新增 Plex 伺服器失敗")
            }
            busy = false
        }
    }

    LaunchedEffect(pin?.id, step) {
        val current = pin ?: return@LaunchedEffect
        if (step != LoginStep.Pin) return@LaunchedEffect
        var backoff = 1_000L
        val started = TimeSource.Monotonic.markNow()
        while (isActive) {
            if (started.elapsedNow() > 2.minutes) {
                error = tr("Plex PIN 已过期，请重新开始", "Plex PIN expired; start again", "Plex PIN の有効期限が切れました。もう一度開始してください", "Plex PIN이 만료되었습니다. 다시 시작하세요", "Plex PIN 已過期，請重新開始")
                step = LoginStep.Start
                return@LaunchedEffect
            }
            delay(backoff)
            val polled = try {
                repository.pollPin(current.id)
            } catch (failure: Throwable) {
                error = failure.message ?: tr("Plex 登录检查失败", "Plex sign-in check failed", "Plex サインイン確認に失敗しました", "Plex 로그인 확인에 실패했습니다", "Plex 登入檢查失敗")
                step = LoginStep.Start
                return@LaunchedEffect
            }
            when (polled) {
                is PlexPinPoll.Claimed -> {
                    accountToken = polled.token
                    val info = runCatching { repository.identity(polled.token) }.getOrElse {
                        error = it.message ?: "Plex account"
                        step = LoginStep.Start
                        return@LaunchedEffect
                    }
                    identity = info
                    profiles = runCatching { repository.homeProfiles(polled.token) }.getOrDefault(emptyList())
                    step = LoginStep.Profiles
                    return@LaunchedEffect
                }
                PlexPinPoll.Expired -> {
                    error = tr("Plex PIN 已过期，请重新开始", "Plex PIN expired; start again", "Plex PIN の有効期限が切れました。もう一度開始してください", "Plex PIN이 만료되었습니다. 다시 시작하세요", "Plex PIN 已過期，請重新開始")
                    step = LoginStep.Start
                    return@LaunchedEffect
                }
                PlexPinPoll.Waiting -> backoff = (backoff * 2).coerceAtMost(5_000L)
            }
        }
    }

    val title = when (step) {
        LoginStep.Start -> tr("添加 Plex 服务器", "Add Plex server", "Plex サーバーを追加", "Plex 서버 추가", "新增 Plex 伺服器")
        LoginStep.Pin -> tr("登录 Plex", "Sign in to Plex", "Plex にサインイン", "Plex 로그인", "登入 Plex")
        LoginStep.Profiles -> tr("选择 Plex 用户", "Choose Plex user", "Plex ユーザーを選択", "Plex 사용자 선택", "選擇 Plex 使用者")
        LoginStep.HomePin -> tr("输入家庭用户 PIN", "Enter Home PIN", "Home PIN を入力", "Home PIN 입력", "輸入家庭使用者 PIN")
        LoginStep.Servers -> tr("选择 Plex 服务器", "Choose Plex servers", "Plex サーバーを選択", "Plex 서버 선택", "選擇 Plex 伺服器")
    }

    PikoDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        confirmButton = {
            when (step) {
                LoginStep.Start -> PikoDialogConfirm(
                    label = tr("开始登录", "Sign in", "サインイン", "로그인", "開始登入"),
                    enabled = !busy,
                    onClick = ::start,
                )
                LoginStep.Pin -> PikoDialogConfirm(
                    label = tr("我已授权", "I have authorized", "承認しました", "승인했습니다", "我已授權"),
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            val current = pin ?: return@launch
                            when (val result = runCatching { repository.pollPin(current.id) }.getOrNull()) {
                                is PlexPinPoll.Claimed -> {
                                    accountToken = result.token
                                    profileToken = ""
                                    identity = runCatching { repository.identity(result.token) }.getOrElse {
                                        error = it.message ?: "Plex account"
                                        return@launch
                                    }
                                    profiles = runCatching { repository.homeProfiles(result.token) }.getOrDefault(emptyList())
                                    step = LoginStep.Profiles
                                }
                                PlexPinPoll.Expired -> error = tr("Plex PIN 已过期", "Plex PIN expired", "Plex PIN の有効期限が切れました", "Plex PIN이 만료되었습니다", "Plex PIN 已過期")
                                else -> error = tr("尚未在 Plex 页面完成授权", "Authorization is not complete yet", "まだ Plex で承認されていません", "아직 Plex에서 승인하지 않았습니다", "尚未在 Plex 頁面完成授權")
                            }
                        }
                    },
                )
                LoginStep.Profiles -> PikoDialogConfirm(
                    label = tr("继续", "Continue", "続行", "계속", "繼續"),
                    enabled = !busy,
                    onClick = { selectProfile(if (selectedProfileUuid.isEmpty()) null else selectedProfile) },
                )
                LoginStep.HomePin -> PikoDialogConfirm(
                    label = tr("确认 PIN", "Confirm PIN", "PIN を確認", "PIN 확인", "確認 PIN"),
                    enabled = !busy,
                    onClick = ::confirmHomePin,
                )
                LoginStep.Servers -> PikoDialogConfirm(
                    label = tr("添加服务器", "Add servers", "サーバーを追加", "서버 추가", "新增伺服器"),
                    enabled = !busy && selectedMachines.isNotEmpty(),
                    onClick = ::add,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    LoginStep.Start -> Text(
                        tr("使用 Plex 账号登录，可自动发现自有和共享的 Plex 服务器。访问令牌只保存在本机。", "Sign in with your Plex account to discover owned and shared servers. Tokens stay on this device.", "Plex アカウントでサインインすると、所有・共有サーバーを自動検出します。トークンはこの端末だけに保存します。", "Plex 계정으로 로그인하면 소유 및 공유 서버를 자동으로 찾습니다. 토큰은 이 기기에만 저장됩니다.", "使用 Plex 帳號登入，可自動探索自有與共享的 Plex 伺服器。存取權杖只保存在本機。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LoginStep.Pin -> {
                        val current = pin
                        if (current == null) {
                            Text(tr("正在创建登录请求…", "Creating sign-in request…", "サインイン要求を作成中…", "로그인 요청 생성 중…", "正在建立登入要求…"))
                        } else {
                            Text(
                                tr("在 Plex 页面输入短码，或用手机扫描二维码：", "Enter this code on the Plex page or scan the QR code:", "Plex ページでコードを入力するか、QR コードをスキャンしてください:", "Plex 페이지에서 코드를 입력하거나 QR 코드를 스캔하세요:", "在 Plex 頁面輸入短碼，或用手機掃描二維碼："),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                current.code,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            qrEncoder?.encode(current.authUrl)?.let { PlexQrCode(it, Modifier.size(220.dp).align(Alignment.CenterHorizontally)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { platform.openUrl(current.authUrl) }) {
                                    Text(tr("打开 Plex 登录页", "Open Plex sign-in", "Plex サインインページを開く", "Plex 로그인 페이지 열기", "開啟 Plex 登入頁"))
                                }
                                TextButton(onClick = { platform.copyToClipboard("Plex", current.authUrl) }) {
                                    Text(tr("复制链接", "Copy link", "リンクをコピー", "링크 복사", "複製連結"))
                                }
                            }
                        }
                    }
                    LoginStep.Profiles -> {
                        Text(tr("选择主账号或 Plex Home 用户：", "Choose the main account or a Plex Home user:", "メインアカウントまたは Plex Home ユーザーを選択:", "기본 계정 또는 Plex Home 사용자를 선택하세요:", "選擇主帳號或 Plex Home 使用者："))
                        ProfileRow(
                            title = identity?.username.orEmpty().ifBlank { tr("主账号", "Main account", "メインアカウント", "기본 계정", "主帳號") },
                            subtitle = identity?.email.orEmpty(),
                            selected = selectedProfileUuid.isEmpty(),
                            onClick = { selectProfile(null) },
                        )
                        profiles.forEach { profile ->
                            ProfileRow(
                                title = profile.title,
                                subtitle = if (profile.protected) tr("受 PIN 保护", "PIN protected", "PIN 保護", "PIN 보호", "受 PIN 保護") else "",
                                selected = selectedProfileUuid == profile.uuid,
                                onClick = { selectProfile(profile) },
                            )
                        }
                    }
                    LoginStep.HomePin -> {
                        Text(tr("该 Plex Home 用户受 PIN 保护。", "This Plex Home user is PIN protected.", "この Plex Home ユーザーは PIN で保護されています。", "이 Plex Home 사용자는 PIN으로 보호됩니다.", "此 Plex Home 使用者受 PIN 保護。"), style = MaterialTheme.typography.bodyMedium)
                        PikoTextField(
                            value = homePin,
                            onValueChange = { homePin = it.filter(Char::isDigit).take(8) },
                            label = tr("PIN", "PIN", "PIN", "PIN", "PIN"),
                            isError = homePinError,
                            supportingText = if (homePinError) tr("PIN 不正确，请重试", "Incorrect PIN, try again", "PIN が正しくありません。再試行してください", "PIN이 올바르지 않습니다. 다시 시도하세요", "PIN 不正確，請重試") else null,
                        )
                    }
                    LoginStep.Servers -> {
                        Text(tr("选择要添加的服务器：", "Choose servers to add:", "追加するサーバーを選択:", "추가할 서버를 선택하세요:", "選擇要新增的伺服器："))
                        discovered.forEach { item ->
                            ServerRow(
                                name = item.server.name,
                                subtitle = if (item.server.owned) tr("自有服务器", "Owned server", "所有サーバー", "소유 서버", "自有伺服器") else tr("共享服务器", "Shared server", "共有サーバー", "공유 서버", "共享伺服器"),
                                checked = item.server.machineIdentifier in selectedMachines,
                                onCheckedChange = {
                                    selectedMachines = if (it) selectedMachines + item.server.machineIdentifier else selectedMachines - item.server.machineIdentifier
                                },
                            )
                        }
                    }
                }
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else Color.Transparent,
                )
            }
        },
    )
}

@Composable
private fun ProfileRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = selected, onValueChange = { onClick() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerRow(name: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlexQrCode(matrix: PlexQrMatrix, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White)) {
        Canvas(Modifier.fillMaxWidth().size(220.dp)) {
            drawQr(matrix)
        }
    }
}

private fun DrawScope.drawQr(matrix: PlexQrMatrix) {
    val module = size.minDimension / matrix.size
    for (y in 0 until matrix.size) {
        for (x in 0 until matrix.size) {
            if (matrix.dark(x, y)) {
                drawRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(x * module, y * module),
                    size = androidx.compose.ui.geometry.Size(module + 0.5f, module + 0.5f),
                )
            }
        }
    }
}

@Composable
fun PlexHomePinDialog(
    account: PlexAccount,
    onDismiss: () -> Unit,
    onConfirm: (profileUuid: String, pin: String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("切换 Plex 用户", "Switch Plex user", "Plex ユーザーを切り替え", "Plex 사용자 전환", "切換 Plex 使用者")) },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("切换", "Switch", "切り替え", "전환", "切換"),
                enabled = pin.isNotBlank() && account.activeProfileUuid.isNotBlank(),
                onClick = { onConfirm(account.activeProfileUuid, pin) },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel", "キャンセル", "취소", "取消")) } },
        text = {
            PikoTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = "PIN",
            )
        },
    )
}

@Composable
fun PlexManageDialog(
    accounts: List<PlexAccount>,
    onDismiss: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onRefreshAccount: (String) -> Unit,
    onSwitchProfile: (accountUuid: String, profileUuid: String, pin: String?) -> Unit,
) {
    var pending by remember { mutableStateOf<Pair<PlexAccount, PlexHomeProfile>?>(null) }
    var pin by remember { mutableStateOf("") }
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("管理 Plex 账号", "Manage Plex accounts", "Plex アカウントを管理", "Plex 계정 관리", "管理 Plex 帳號")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close", "閉じる", "닫기", "關閉")) } },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                accounts.forEach { account ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.username.ifBlank { account.email.ifBlank { account.accountUuid } }, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onRemoveAccount(account.accountUuid) }) {
                            Text(tr("移除账号", "Remove account", "アカウントを削除", "계정 제거", "移除帳號"), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { onRefreshAccount(account.accountUuid) }) {
                            Text(tr("刷新服务器", "Refresh servers", "サーバーを更新", "서버 새로 고침", "重新整理伺服器"))
                        }
                        if (account.activeProfileUuid.isNotBlank()) {
                            TextButton(onClick = { onSwitchProfile(account.accountUuid, "", null) }) {
                                Text(tr("切换回主账号", "Switch to main account", "メインアカウントに切り替え", "기본 계정으로 전환", "切換回主帳號"))
                            }
                        }
                        account.profiles.forEach { profile ->
                            TextButton(onClick = {
                                if (profile.protected) pending = account to profile
                                else onSwitchProfile(account.accountUuid, profile.uuid, null)
                            }) {
                                Text(
                                    if (account.activeProfileUuid == profile.uuid) "✓ ${profile.title}" else profile.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        account.servers.forEach { server ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(server.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onRemoveServer(server.mountId) }) {
                                    Text(tr("移除", "Remove", "削除", "제거", "移除"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                if (accounts.isEmpty()) Text(tr("尚未添加 Plex 账号", "No Plex accounts yet", "Plex アカウントはまだありません", "아직 Plex 계정이 없습니다", "尚未新增 Plex 帳號"))
                pending?.let { (account, profile) ->
                    Text(tr("切换到 ${profile.title} 需要 PIN", "Switching to ${profile.title} requires a PIN", "${profile.title} への切り替えには PIN が必要です", "${profile.title}(으)로 전환하려면 PIN이 필요합니다", "切換至 ${profile.title} 需要 PIN"))
                    PikoTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = "PIN",
                    )
                    TextButton(
                        enabled = pin.isNotBlank(),
                        onClick = {
                            onSwitchProfile(account.accountUuid, profile.uuid, pin)
                            pending = null
                            pin = ""
                            onDismiss()
                        },
                    ) { Text(tr("切换", "Switch", "切り替え", "전환", "切換")) }
                }
            }
        },
    )
}

private enum class LoginStep { Start, Pin, Profiles, HomePin, Servers }
