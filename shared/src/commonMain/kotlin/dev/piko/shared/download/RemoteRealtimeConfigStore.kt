package dev.piko.shared.download

import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.sync.DriveSettingsStore
import dev.piko.shared.sync.RemoteSettingsStore
import dev.piko.shared.sync.SyncCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * 远程下载实时通道的账号级配置，加密后存进网盘 `.piko/remote-realtime-<时间戳>.json`。
 * 密钥与压缩包密码同步一样由账号密码派生；同账号设备登录后自动读取，不必每台重填。
 */
class RemoteRealtimeConfigStore(
    private val clients: PikoClientProvider,
    private val remote: RemoteSettingsStore,
    private val passwordOf: suspend (account: String) -> String?,
    private val cipher: SyncCipher,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val accountProvider: () -> String? = { clients.currentClient.value?.account },
) {
    constructor(
        clients: PikoClientProvider,
        driveRepo: PikoDriveRepository,
        passwordOf: suspend (account: String) -> String?,
        cipher: SyncCipher,
        scope: CoroutineScope,
    ) : this(clients, DriveSettingsStore(clients, driveRepo, FILE_PREFIX), passwordOf, cipher, scope)

    enum class Status { IDLE, LOADED, MISSING, LOCKED, ERROR }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _config = MutableStateFlow<RemoteRealtimeConfig?>(null)
    val config: StateFlow<RemoteRealtimeConfig?> = _config.asStateFlow()
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var cachedKey: CachedKey? = null

    fun start() {
        scope.launch {
            clients.currentClient
                .map { it?.account }
                .distinctUntilChanged()
                .collectLatest { account ->
                    if (account == null) {
                        _config.value = null
                        _status.value = Status.IDLE
                        return@collectLatest
                    }
                    load(account)
                }
        }
    }

    /** 打开设置或手动刷新时重新拉一次网盘上最新的配置。 */
    suspend fun refresh() {
        val account = accountProvider() ?: return
        load(account)
    }

    suspend fun save(config: RemoteRealtimeConfig): Result<Unit> = withContext(Dispatchers.Default) {
        lock.withLock {
            val account = accountProvider()
                ?: return@withLock Result.failure(IllegalStateException(tr("尚未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")))
            if (!config.isConfigured) {
                return@withLock Result.failure(IllegalArgumentException(tr("请填写服务器地址、用户名和密码", "Enter the server URL, username and password", "サーバー URL、ユーザー名、パスワードを入力してください", "서버 주소, 사용자 이름, 비밀번호를 입력하세요", "請填寫伺服器位址、使用者名稱與密碼")))
            }
            val secret = passwordOf(account)
                ?: return@withLock Result.failure(IllegalStateException(tr("读不出账号密码，无法加密保存", "The account password is unavailable, so the configuration cannot be encrypted", "アカウントのパスワードを読み取れないため暗号化して保存できません", "계정 비밀번호를 읽을 수 없어 암호화하여 저장할 수 없습니다", "讀不出帳號密碼，無法加密儲存")))
            runCatching {
                val salt = cipher.newSalt()
                val key = keyFor(secret, salt, ITERATIONS)
                val sealed = cipher.seal(key, json.encodeToString(RemoteRealtimeConfig.serializer(), config).encodeToByteArray())
                val text = json.encodeToString(
                    Envelope.serializer(),
                    Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)),
                )
                remote.write(account, text, now())
                _config.value = config
                _status.value = Status.LOADED
            }.logFailure(TAG, "保存远程下载实时配置失败")
        }
    }

    suspend fun clear(): Result<Unit> = withContext(Dispatchers.Default) {
        lock.withLock {
            runCatching {
                val account = accountProvider()
                    ?: return@withLock Result.failure(IllegalStateException(tr("尚未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")))
                val secret = passwordOf(account)
                    ?: return@withLock Result.failure(IllegalStateException(tr("读不出账号密码", "The account password is unavailable", "アカウントのパスワードを読み取れません", "계정 비밀번호를 읽을 수 없습니다", "讀不出帳號密碼")))
                val salt = cipher.newSalt()
                val key = keyFor(secret, salt, ITERATIONS)
                val sealed = cipher.seal(key, json.encodeToString(RemoteRealtimeConfig.serializer(), RemoteRealtimeConfig()).encodeToByteArray())
                val text = json.encodeToString(
                    Envelope.serializer(),
                    Envelope(VERSION, ITERATIONS, Base64.encode(salt), Base64.encode(sealed)),
                )
                remote.write(account, text, now())
                _config.value = null
                _status.value = Status.MISSING
            }.logFailure(TAG, "清除远程下载实时配置失败")
        }
    }

    private suspend fun load(account: String) {
        val text = runCatching { remote.read(account) }
            .logFailure(TAG, "读取远程下载实时配置失败")
            .getOrNull()
        if (text.isNullOrBlank()) {
            _config.value = null
            _status.value = Status.MISSING
            return
        }
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
        if (envelope == null || envelope.version > VERSION) {
            _config.value = null
            _status.value = Status.ERROR
            return
        }
        val secret = passwordOf(account)
        if (secret == null) {
            _config.value = null
            _status.value = Status.LOCKED
            return
        }
        val plain = open(envelope, secret)
        if (plain == null) {
            _config.value = null
            _status.value = Status.ERROR
            return
        }
        val config = runCatching {
            json.decodeFromString(RemoteRealtimeConfig.serializer(), plain.decodeToString())
        }.getOrNull()
        _config.value = config?.takeIf { it.isConfigured }
        _status.value = if (_config.value != null) Status.LOADED else Status.MISSING
    }

    private suspend fun open(envelope: Envelope, secret: String): ByteArray? {
        val salt = runCatching { Base64.decode(envelope.salt) }.getOrNull() ?: return null
        val sealed = runCatching { Base64.decode(envelope.data) }.getOrNull() ?: return null
        return cipher.open(keyFor(secret, salt, envelope.iterations), sealed)
    }

    private suspend fun keyFor(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        cachedKey?.takeIf { it.secret == secret && it.salt.contentEquals(salt) && it.iterations == iterations }?.let { return it.key }
        val key = withContext(Dispatchers.Default) { cipher.deriveKey(secret, salt, iterations) }
        cachedKey = CachedKey(secret, salt, iterations, key)
        return key
    }

    private class CachedKey(val secret: String, val salt: ByteArray, val iterations: Int, val key: ByteArray)

    @Serializable
    private data class Envelope(val version: Int, val iterations: Int, val salt: String, val data: String)

    private companion object {
        const val TAG = "RemoteRealtimeConfig"
        const val FILE_PREFIX = "remote-realtime-"
        const val VERSION = 1
        const val ITERATIONS = 600_000
    }
}
