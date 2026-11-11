package dev.piko.data.auth

import dev.piko.ui.platform.AppLock
import dev.piko.ui.platform.PinVerification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Android 的应用锁状态。PIN 摘要与多任务遮罩开关都在 [SessionManager] 的本机偏好里，
 * 不进入账号设置同步；锁只在冷启动恢复已登录账号时生效，切后台不重新上锁。
 */
internal class AndroidAppLock(
    private val preferences: SessionManager,
    scope: CoroutineScope,
) : AppLock {
    private val initialPinHash = runBlocking { preferences.appPinHashFlow.first() }
    private val _configured = MutableStateFlow(initialPinHash != null)
    private val _locked = MutableStateFlow(initialPinHash != null)
    private val _recentsMaskEnabled = MutableStateFlow(runBlocking { preferences.recentsMaskFlow.first() })
    private val verifyLock = Mutex()
    private var failures = 0
    private var blockedUntilMillis = 0L
    private var startupDecided = false

    override val configured: StateFlow<Boolean> = _configured.asStateFlow()
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()
    override val recentsMaskEnabled: StateFlow<Boolean> = _recentsMaskEnabled.asStateFlow()

    init {
        scope.launch {
            preferences.appPinHashFlow.collect { stored ->
                _configured.value = stored != null
                if (stored == null) _locked.value = false
            }
        }
        scope.launch {
            preferences.recentsMaskFlow.collect { _recentsMaskEnabled.value = it }
        }
    }

    override suspend fun setPin(pin: String) {
        require(AppPinHasher.isWellFormed(pin)) { "PIN must be four digits" }
        val stored = withContext(Dispatchers.Default) { AppPinHasher.hash(pin) }
        preferences.setAppPinHash(stored)
        failures = 0
        blockedUntilMillis = 0L
    }

    override suspend fun clearPin() {
        preferences.setAppPinHash(null)
        failures = 0
        blockedUntilMillis = 0L
        _locked.value = false
    }

    override suspend fun verifyPin(pin: String): PinVerification = verifyLock.withLock {
        val now = System.currentTimeMillis()
        if (now < blockedUntilMillis) {
            return@withLock PinVerification.Blocked(secondsUntil(blockedUntilMillis, now))
        }
        val stored = preferences.appPinHash() ?: return@withLock PinVerification.Incorrect
        val verified = withContext(Dispatchers.Default) { AppPinHasher.verify(pin, stored) }
        if (verified) {
            failures = 0
            blockedUntilMillis = 0L
            _locked.value = false
            return@withLock PinVerification.Verified
        }
        failures += 1
        if (failures >= MAX_FAILURES) {
            failures = 0
            blockedUntilMillis = now + BLOCK_MILLIS
            PinVerification.Blocked(BLOCK_SECONDS)
        } else {
            PinVerification.Incorrect
        }
    }

    override suspend fun setRecentsMaskEnabled(enabled: Boolean) {
        preferences.setRecentsMaskEnabled(enabled)
    }

    override fun onStartupRestored(hasAccount: Boolean) {
        if (startupDecided) return
        startupDecided = true
        if (!hasAccount || !_configured.value) _locked.value = false
    }

    private fun secondsUntil(untilMillis: Long, nowMillis: Long): Int =
        ceil((untilMillis - nowMillis).coerceAtLeast(0L) / 1000.0).toInt().coerceAtLeast(1)

    private companion object {
        const val MAX_FAILURES = 5
        const val BLOCK_MILLIS = 30_000L
        const val BLOCK_SECONDS = 30
    }
}
