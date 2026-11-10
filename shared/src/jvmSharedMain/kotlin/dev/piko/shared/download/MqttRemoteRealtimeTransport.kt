package dev.piko.shared.download

import dev.piko.shared.log.PikoLog
import dev.piko.shared.i18n.tr
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.MqttClientPersistence
import org.eclipse.paho.client.mqttv3.internal.NetworkModule
import org.eclipse.paho.client.mqttv3.internal.SSLNetworkModule
import org.eclipse.paho.client.mqttv3.internal.websocket.WebSocketSecureNetworkModule
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlin.time.Clock

/**
 * EMQX/MQTT 上的远程下载通道。请求、状态和 presence 都经这里传输，不再依赖网盘 `.piko` 账本。
 */
class MqttRemoteRealtimeTransport(
    private val scope: CoroutineScope,
) : RemoteRealtimeTransport {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _events = MutableSharedFlow<RemoteRealtimeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RemoteRealtimeEvent> = _events.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val publishedPresence = AtomicBoolean(false)

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var prefix: String = ""
    @Volatile private var deviceId: String = ""
    @Volatile private var deviceName: String = ""
    @Volatile private var platform: String = ""
    @Volatile private var receiverEnabled = false
    @Volatile private var started = false
    @Volatile private var config: RemoteRealtimeConfig? = null
    @Volatile private var lastConnectionError: String? = null
    @Volatile private var account: String = ""
    private var heartbeatJob: Job? = null

    override fun start(account: String, deviceId: String, deviceName: String, platform: String) {
        val currentConfig = config
        if (currentConfig == null || !currentConfig.isConfigured) {
            PikoLog.d(TAG, "未配置 EMQX，远程下载不可用")
            return
        }
        if (started && this.deviceId == deviceId && this.account == account) return
        stop()
        this.account = account
        this.deviceId = deviceId
        this.deviceName = deviceName
        this.platform = platform
        prefix = "piko/v1/${channelOf(account)}"
        started = true
        scope.launch(Dispatchers.IO) {
            while (isActive && started) {
                val result = runCatching { connect() }
                if (result.isSuccess) break
                val error = result.exceptionOrNull()
                lastConnectionError = error?.message ?: error?.let { it::class.simpleName }
                _connected.value = false
                PikoLog.w(TAG, "连接 EMQX 失败，10 秒后重试", error)
                runCatching { client?.close() }
                client = null
                delay(CONNECT_RETRY_MS)
            }
        }
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (started) publishPresence(receiverEnabled)
            }
        }
    }

    override fun updateConfig(config: RemoteRealtimeConfig?) {
        this.config = config
        // 配置变了就断开；由协调器决定是否立刻用新配置重连（远程下载关闭时不能自行启动）。
        if (started) stop()
    }

    override suspend fun testConnection(config: RemoteRealtimeConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext Result.failure(IllegalArgumentException(tr("请填写服务器地址、用户名和密码", "Enter the server URL, username and password", "サーバー URL、ユーザー名、パスワードを入力してください", "서버 주소, 사용자 이름, 비밀번호를 입력하세요", "請填寫伺服器位址、使用者名稱與密碼")))
        val probe = MqttRemoteRealtimeTransport(scope)
        probe.updateConfig(config)
        val account = "probe-${Clock.System.now().toEpochMilliseconds()}"
        val device = "probe-${Clock.System.now().toEpochMilliseconds()}"
        return@withContext try {
            probe.start(account, device, "Piko probe", "probe")
            withTimeout(12_000) { probe.connected.first { it } }
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(IllegalStateException(probe.lastConnectionError ?: tr("连接超时，请检查地址、端口和账号密码", "Connection timed out. Check the URL, port and credentials", "接続がタイムアウトしました。アドレス、ポート、認証情報を確認してください", "연결 시간이 초과되었습니다. 주소, 포트, 계정 정보를 확인하세요", "連線逾時，請檢查位址、連接埠與帳號密碼"), error))
        } finally {
            probe.stop()
        }
    }

    override fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val current = client
        if (started && current?.isConnected == true) {
            runCatching { publishPresenceInternal(online = false) }
        }
        started = false
        publishedPresence.set(false)
        client = null
        _connected.value = false
        runCatching { current?.disconnectForcibly(0, 0, false) }
        runCatching { current?.close() }
    }

    override fun publishPresence(receiverEnabled: Boolean) {
        this.receiverEnabled = receiverEnabled
        if (!started) return
        publishPresenceInternal(online = true)
    }

    private fun publishPresenceInternal(online: Boolean) {
        val payload = json.encodeToString(
            RemotePresence.serializer(),
            RemotePresence(
                deviceId = deviceId,
                name = deviceName,
                platform = platform,
                receiverEnabled = receiverEnabled,
                online = online,
                updatedAtMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        if (publish("$prefix/dev/$deviceId", payload.encodeToByteArray(), qos = 1, retained = true) && online) {
            publishedPresence.set(true)
        }
    }

    override fun publishRequest(targetDeviceId: String, request: RemoteDownloadRequest): Boolean {
        val payload = json.encodeToString(RequestPayload.serializer(), RequestPayload(request))
        return publish("$prefix/request/$targetDeviceId", payload.encodeToByteArray(), qos = 1, retained = false)
    }

    override fun publishState(sourceDeviceId: String, state: RemoteDownloadState): Boolean {
        val payload = json.encodeToString(StatePayload.serializer(), StatePayload(state))
        return publish("$prefix/state/$sourceDeviceId", payload.encodeToByteArray(), qos = 1, retained = false)
    }

    private fun connect() {
        val currentConfig = config ?: return
        val id = "piko-$deviceId"
        val will = json.encodeToString(
            RemotePresence.serializer(),
            RemotePresence(
                deviceId = deviceId,
                name = deviceName,
                platform = platform,
                receiverEnabled = receiverEnabled,
                online = false,
                updatedAtMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        val created = DirectMqttAsyncClient(serverUri(currentConfig), id, MemoryPersistence())
        created.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                lastConnectionError = null
                _connected.value = true
                subscribe(created)
                publishPresence(receiverEnabled)
                PikoLog.i(TAG, "EMQX 已连接${if (reconnect) "（重连）" else ""}")
            }

            override fun connectionLost(cause: Throwable?) {
                _connected.value = false
                lastConnectionError = cause?.message ?: cause?.let { it::class.simpleName }
                PikoLog.w(TAG, "EMQX 连接断开，等待自动重连", cause)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val body = message?.payload?.decodeToString() ?: return
                val path = topic.orEmpty()
                when {
                    path.startsWith("$prefix/dev/") -> {
                        val presence = runCatching { json.decodeFromString(RemotePresence.serializer(), body) }.getOrNull() ?: return
                        if (presence.deviceId == deviceId) return
                        _events.tryEmit(RemoteRealtimeEvent.Presence(presence))
                    }
                    path == "$prefix/request/$deviceId" -> {
                        val request = runCatching { json.decodeFromString(RequestPayload.serializer(), body) }.getOrNull() ?: return
                        _events.tryEmit(RemoteRealtimeEvent.Request(request.request))
                    }
                    path == "$prefix/state/$deviceId" -> {
                        val state = runCatching { json.decodeFromString(StatePayload.serializer(), body) }.getOrNull() ?: return
                        _events.tryEmit(RemoteRealtimeEvent.State(state.state))
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            userName = currentConfig.username
            password = currentConfig.password.toCharArray()
            keepAliveInterval = 30
            connectionTimeout = 10
            setWill("$prefix/dev/$deviceId", will.encodeToByteArray(), 1, true)
        }
        client = created
        created.connect(options).waitForCompletion(CONNECT_TIMEOUT_MS)
    }

    private fun serverUri(config: RemoteRealtimeConfig): String {
        val raw = config.url.trim().trimEnd('/')
        return when {
            raw.startsWith("mqtts://") || raw.startsWith("ssl://") -> {
                val uri = URI(raw.replaceFirst("mqtts://", "ssl://"))
                "ssl://${uri.host}:${if (uri.port > 0) uri.port else 8883}"
            }
            raw.startsWith("wss://") -> raw
            "://" in raw -> throw IllegalArgumentException(tr("仅支持 mqtts:// 或 wss:// 地址", "Only mqtts:// or wss:// URLs are supported", "mqtts:// または wss:// のみ対応しています", "mqtts:// 또는 wss:// 주소만 지원합니다", "僅支援 mqtts:// 或 wss:// 位址"))
            ':' in raw -> "ssl://$raw"
            else -> "ssl://$raw:8883"
        }
    }

    private fun subscribe(client: MqttAsyncClient) {
        runCatching {
            client.subscribe(
                arrayOf("$prefix/dev/+", "$prefix/request/$deviceId", "$prefix/state/$deviceId"),
                intArrayOf(1, 1, 1),
            ).waitForCompletion(SUBSCRIBE_TIMEOUT_MS)
        }.onFailure { PikoLog.w(TAG, "订阅 EMQX 主题失败", it) }
    }

    private fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean): Boolean {
        val current = client ?: return false
        if (!current.isConnected) return false
        return runCatching {
            current.publish(topic, MqttMessage(payload).apply { this.qos = qos; isRetained = retained })
            true
        }.onFailure { PikoLog.d(TAG, "发布 EMQX 消息失败：${it::class.simpleName}") }.getOrDefault(false)
    }

    private fun channelOf(account: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("piko-remote-v1|$account".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    @Serializable private data class RequestPayload(val request: RemoteDownloadRequest)
    @Serializable private data class StatePayload(val state: RemoteDownloadState)

    private companion object {
        const val TAG = "RemoteRealtime"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        const val CONNECT_RETRY_MS = 10_000L
    }

    /**
     * Paho 用 ServiceLoader 找 NetworkModuleFactory，APK 资源收缩会把 META-INF/services 删掉。
     * 直接给它 TLS 模块，绕过 ServiceLoader；Android 与桌面共用这一条路径。
     */
    private class DirectMqttAsyncClient(
        serverURI: String,
        private val id: String,
        persistence: MqttClientPersistence,
    ) : MqttAsyncClient(serverURI, id, persistence) {
        override fun createNetworkModules(address: String, options: MqttConnectOptions): Array<NetworkModule> {
            val uri = URI(address)
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 8883
            return when (uri.scheme.lowercase()) {
                "wss" -> arrayOf(
                    WebSocketSecureNetworkModule(
                        SSLSocketFactory.getDefault() as SSLSocketFactory,
                        address,
                        uri.host,
                        port,
                        id,
                        null,
                    ),
                )
                else -> arrayOf(
                    SSLNetworkModule(SSLSocketFactory.getDefault() as SSLSocketFactory, uri.host, port, id).apply {
                        setHttpsHostnameVerificationEnabled(true)
                        setSSLhandshakeTimeout(options.connectionTimeout)
                    },
                )
            }
        }
    }
}
