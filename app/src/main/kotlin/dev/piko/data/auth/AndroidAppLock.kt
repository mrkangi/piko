package dev.piko.data.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.piko.shared.i18n.tr
import dev.piko.ui.platform.AppLock
import dev.piko.ui.platform.AppLockTimeout
import dev.piko.ui.platform.PinVerification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.ceil

/**
 * Android 的应用锁状态。PIN 摘要、多任务遮罩、自动锁定与生物识别开关都在
 * [SessionManager] 的本机偏好里，不进入账号设置同步。
 */
internal class AndroidAppLock(
    private val context: Context,
    private val preferences: SessionManager,
    private val activityProvider: () -> FragmentActivity?,
    private val scope: CoroutineScope,
) : AppLock {
    private val initialPinHash = runBlocking { preferences.appPinHashFlow.first() }
    private val _configured = MutableStateFlow(initialPinHash != null)
    private val _locked = MutableStateFlow(initialPinHash != null)
    private val _recentsMaskEnabled = MutableStateFlow(runBlocking { preferences.recentsMaskFlow.first() })
    private val _biometricAvailable = MutableStateFlow(canUseBiometric())
    private val _biometricEnabled = MutableStateFlow(
        _biometricAvailable.value && runBlocking { preferences.appLockBiometricEnabledFlow.first() },
    )
    private val _autoLockTimeout = MutableStateFlow(runBlocking { preferences.appLockTimeoutFlow.first() })
    private val verifyLock = Mutex()
    private var failures = 0
    private var blockedUntilMillis = 0L
    private var startupDecided = false
    private var inBackground = false
    private var autoLockJob: Job? = null

    override val configured: StateFlow<Boolean> = _configured.asStateFlow()
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()
    override val recentsMaskEnabled: StateFlow<Boolean> = _recentsMaskEnabled.asStateFlow()
    override val biometricAvailable: StateFlow<Boolean> = _biometricAvailable.asStateFlow()
    override val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()
    override val autoLockTimeout: StateFlow<AppLockTimeout> = _autoLockTimeout.asStateFlow()

    init {
        scope.launch {
            preferences.appPinHashFlow.collect { stored ->
                _configured.value = stored != null
                if (stored == null) {
                    _locked.value = false
                    inBackground = false
                    autoLockJob?.cancel()
                }
            }
        }
        scope.launch {
            preferences.recentsMaskFlow.collect { _recentsMaskEnabled.value = it }
        }
        scope.launch {
            preferences.appLockBiometricEnabledFlow.collect { enabled ->
                _biometricEnabled.value = enabled && _biometricAvailable.value
            }
        }
        scope.launch {
            preferences.appLockTimeoutFlow.collect { _autoLockTimeout.value = it }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                onAppBackgrounded()
            }
        })
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
        inBackground = false
        autoLockJob?.cancel()
    }

    override suspend fun verifyPin(pin: String): PinVerification = verifyLock.withLock {
        val now = System.currentTimeMillis()
        if (now < blockedUntilMillis) {
            return@withLock PinVerification.Blocked(secondsUntil(blockedUntilMillis, now))
        }
        val stored = preferences.appPinHash() ?: return@withLock PinVerification.Incorrect
        val verified = withContext(Dispatchers.Default) { AppPinHasher.verify(pin, stored) }
        if (verified) {
            unlock()
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

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && !_biometricAvailable.value) return
        preferences.setAppLockBiometricEnabled(enabled)
        _biometricEnabled.value = enabled
    }

    override suspend fun setAutoLockTimeout(timeout: AppLockTimeout) {
        preferences.setAppLockTimeout(timeout)
        _autoLockTimeout.value = timeout
        if (timeout == AppLockTimeout.NEVER) autoLockJob?.cancel()
    }

    override fun lockNow() {
        if (_configured.value) _locked.value = true
    }

    override suspend fun authenticateBiometric(): Boolean {
        if (!_configured.value || !_biometricEnabled.value || !_biometricAvailable.value) return false
        val activity = activityProvider() ?: return false
        val authenticated = suspendCancellableCoroutine<Boolean> { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(tr("解锁 Piko", "Unlock Piko", "Piko のロックを解除", "Piko 잠금 해제", "解鎖 Piko"))
                .setSubtitle("Piko")
                .setNegativeButtonText(tr("使用 PIN", "Use PIN", "PIN を使用", "PIN 사용", "使用 PIN"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
            runCatching { prompt.authenticate(info) }
                .onFailure { if (continuation.isActive) continuation.resume(false) }
        }
        if (authenticated) unlock()
        return authenticated
    }

    override fun onStartupRestored(hasAccount: Boolean) {
        if (startupDecided) return
        startupDecided = true
        if (!hasAccount || !_configured.value) _locked.value = false
    }

    private fun unlock() {
        failures = 0
        blockedUntilMillis = 0L
        _locked.value = false
    }

    private fun onAppBackgrounded() {
        if (inBackground) return
        inBackground = true
        autoLockJob?.cancel()
        val timeout = _autoLockTimeout.value
        if (!_configured.value || timeout == AppLockTimeout.NEVER) return
        val millis = timeout.millis ?: return
        if (millis <= 0L) {
            _locked.value = true
            return
        }
        autoLockJob = scope.launch {
            delay(millis)
            if (inBackground && _configured.value) _locked.value = true
        }
    }

    private fun onAppForegrounded() {
        inBackground = false
        autoLockJob?.cancel()
        autoLockJob = null
    }

    private fun canUseBiometric(): Boolean = runCatching {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    private fun secondsUntil(untilMillis: Long, nowMillis: Long): Int =
        ceil((untilMillis - nowMillis).coerceAtLeast(0L) / 1000.0).toInt().coerceAtLeast(1)

    private companion object {
        const val MAX_FAILURES = 5
        const val BLOCK_MILLIS = 30_000L
        const val BLOCK_SECONDS = 30
    }
}
