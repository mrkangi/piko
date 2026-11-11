package dev.piko

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.LocalAppLanguage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.player.MediampVideoPlayerScreen
import dev.piko.ui.theme.appearanceFlow
import dev.piko.ui.theme.PikoTheme
import dev.piko.platform.installMotionScale
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 独立的多窗口播放器。每个槽位（[PlayerSlot0Activity] 等）在清单里各有独立的
 * `taskAffinity`，因此是一个独立的系统任务；Quest 这类多窗口系统会把它显示成
 * 空间里可以单独拖动、缩放的 2D 面板，和系统浏览器开多个窗口是同一套机制。
 *
 * 槽位复用时（最多 3 个窗口都已存在，再点第 4 个视频）系统走 [onNewIntent]，
 * 当前窗口原地换片，用户在空间里摆好的位置和大小保持不变。
 */
open class PlayerActivity : ComponentActivity() {

    protected open val slotIndex: Int get() = 0

    private var requestState by mutableStateOf<VideoPlayerRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemLocale = Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
        PikoLocale.bindSystemLocale(systemLocale.toLanguageTag())
        super.onCreate(savedInstanceState)
        val app = PikoApplication.instance
        PikoLocale.apply(runBlocking { app.sessionManager.appLanguageFlow.first() })
        enableEdgeToEdge()

        val request = requestOf(intent)
        if (request == null) {
            finish()
            return
        }
        requestState = request
        MultiWindowPlayerCoordinator.attach(slotIndex, this, request)

        val appearanceFlow = app.sessionManager.appearanceFlow(app.platform.supportsDynamicColor)
        // 与主界面一样同步读一次，避免首帧按系统取色画完再跳成用户选的主题
        val initialAppearance = runBlocking { appearanceFlow.first() }
        installMotionScale(app.platform.motionScale, lifecycle)
        setContent {
            val appearance by appearanceFlow.collectAsStateWithLifecycle(initialAppearance)
            val appLanguage by app.services.preferences.appLanguageFlow
                .collectAsStateWithLifecycle(initialValue = PikoLocale.preference)
            PikoLocale.apply(appLanguage)
            val effectiveLanguage by PikoLocale.effectiveLanguageState
            val focusedSlot by MultiWindowPlayerCoordinator.focusedSlot.collectAsStateWithLifecycle()
            // 播放窗口是独立的组合树，主窗口根部的输入来源追踪管不到这里
            val pointerSource = remember { PointerSource() }
            app.platform.ProvideLocalizedEnvironment(effectiveLanguage) {
                CompositionLocalProvider(
                    LocalAppLanguage provides effectiveLanguage,
                    LocalPikoServices provides app.services,
                    LocalPikoPlatform provides app.platform,
                    LocalPointerSource provides pointerSource,
                ) {
                    PikoTheme(appearance = appearance) {
                        val request = requestState ?: return@PikoTheme
                        // 换片时整棵播放器重建：后端、取流会话与续播都从新文件重新开始
                        key(request.fileId, request.startMillis) {
                            MediampVideoPlayerScreen(
                                initialFileId = request.fileId,
                                initialFileName = request.fileName,
                                initialLocalPath = request.localPath,
                                initialStartMillis = request.startMillis,
                                onBackClick = ::closeWindow,
                                standaloneWindow = true,
                                // 多个窗口同时出声，但系统媒体通知只由当前聚焦的那个窗口持有
                                ownsSystemMediaControls = focusedSlot == NO_FOCUSED_WINDOW || focusedSlot == slotIndex,
                                modifier = Modifier.trackPointerSource(pointerSource),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val request = requestOf(intent) ?: return
        requestState = request
        MultiWindowPlayerCoordinator.attach(slotIndex, this, request)
    }

    override fun onResume() {
        super.onResume()
        PikoApplication.instance.restoreTransportMemoryBudget()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) MultiWindowPlayerCoordinator.onFocusGained(slotIndex)
    }

    override fun onDestroy() {
        MultiWindowPlayerCoordinator.detach(slotIndex, this)
        super.onDestroy()
    }

    private fun closeWindow() {
        finishAndRemoveTask()
    }

    private fun requestOf(intent: Intent?): VideoPlayerRequest? {
        val fileId = intent?.getStringExtra(EXTRA_FILE_ID)?.takeIf { it.isNotEmpty() } ?: return null
        return VideoPlayerRequest(
            fileId = fileId,
            fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
            localPath = intent.getStringExtra(EXTRA_LOCAL_PATH),
            startMillis = intent.getLongExtra(EXTRA_START_MILLIS, -1L).takeIf { it >= 0 },
        )
    }

    companion object {
        private const val EXTRA_FILE_ID = "dev.piko.extra.PLAYER_FILE_ID"
        private const val EXTRA_FILE_NAME = "dev.piko.extra.PLAYER_FILE_NAME"
        private const val EXTRA_LOCAL_PATH = "dev.piko.extra.PLAYER_LOCAL_PATH"
        private const val EXTRA_START_MILLIS = "dev.piko.extra.PLAYER_START_MILLIS"

        fun launch(context: Context, slot: Int, request: VideoPlayerRequest) {
            val target = when (slot) {
                1 -> PlayerSlot1Activity::class.java
                2 -> PlayerSlot2Activity::class.java
                else -> PlayerSlot0Activity::class.java
            }
            val intent = Intent(context, target).apply {
                // 每个槽位在清单里各有 taskAffinity，配 NEW_TASK 就是一个独立任务
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FILE_ID, request.fileId)
                putExtra(EXTRA_FILE_NAME, request.fileName)
                putExtra(EXTRA_LOCAL_PATH, request.localPath)
                val start = request.startMillis
                if (start != null) putExtra(EXTRA_START_MILLIS, start)
            }
            context.startActivity(intent)
        }
    }
}

class PlayerSlot0Activity : PlayerActivity() {
    override val slotIndex: Int get() = 0
}

class PlayerSlot1Activity : PlayerActivity() {
    override val slotIndex: Int get() = 1
}

class PlayerSlot2Activity : PlayerActivity() {
    override val slotIndex: Int get() = 2
}

internal const val NO_FOCUSED_WINDOW = -1
