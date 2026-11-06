package dev.piko.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.piko.MainActivity
import dev.piko.PikoApplication
import dev.piko.shared.i18n.tr
import dev.piko.shared.lan.LanHostStatus
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Android 作为局域网共享 host 时保持进程存活的前台服务。
 *
 * HTTP 服务本身在 [AndroidLanShareHost] 中，进程被回收后由应用启动流程按已保存的开关重新拉起。
 * 使用 `specialUse`：共享是用户显式开启的长期能力，不属于 `dataSync` 的 6 小时限额。
 */
class AndroidLanShareService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                PikoApplication.instance.services.lanShareSettings.update { it.copy(enabled = false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(PikoApplication.instance.services.lanShareHost?.status?.value), type)
        } catch (error: RuntimeException) {
            PikoLog.w(TAG, "局域网共享 host 进入前台服务被拒", error)
            stopSelf()
            return START_NOT_STICKY
        }
        observeStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeStatus() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            PikoApplication.instance.services.lanShareHost?.status?.collect { status ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            }
        }
    }

    private fun notification(status: LanHostStatus?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(
            tr(
                "局域网共享已开启",
                "LAN sharing is on",
                "LAN 共有が有効です",
                "LAN 공유가 켜져 있습니다",
                "區域網路共享已開啟",
            ),
        )
        .setContentText(
            if (status?.running == true) {
                "${status.address}:${status.port} · ${tr("在应用内查看配对码", "Open the app for the pairing code", "ペアリングコードはアプリで確認", "페어링 코드는 앱에서 확인", "在應用程式內查看配對碼")}"
            } else {
                tr(
                    "访问目录的已配对设备可直接读取本机文件",
                    "Paired devices can read mounted folders directly",
                    "ペアリング済みの端末はマウント済みフォルダーを直接読み取れます",
                    "페어링된 기기는 마운트된 폴더를 직접 읽을 수 있습니다",
                    "已配對裝置可直接讀取本機掛載目錄",
                )
            },
        )
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            tr("关闭", "Turn off", "オフにする", "끄기", "關閉"),
            PendingIntent.getService(
                this,
                STOP_REQUEST_CODE,
                Intent(this, AndroidLanShareService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("局域网共享", "LAN sharing", "LAN 共有", "LAN 공유", "區域網路共享"),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "AndroidLanShare"
        private const val CHANNEL_ID = "piko_lan_share"
        private const val NOTIFICATION_ID = 0x504C
        private const val OPEN_REQUEST_CODE = 0x504D
        private const val STOP_REQUEST_CODE = 0x504E
        private const val ACTION_STOP = "dev.piko.action.STOP_LAN_SHARE"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AndroidLanShareService::class.java),
                )
            }.onFailure { PikoLog.w(TAG, "启动局域网共享服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AndroidLanShareService::class.java)) }
        }
    }
}
