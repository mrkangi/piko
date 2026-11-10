package dev.piko.download

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
import dev.piko.shared.log.PikoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android 作为远程下载目标时保持进程存活的前台服务。
 *
 * 只负责挂通知；MQTT 连接、presence 与任务状态在 [dev.piko.shared.download.RemoteDownloadCoordinator] 里。
 * 使用 `specialUse`：远程接收是用户显式开启的长期能力，不属于 6 小时限额的 dataSync。
 */
class RemoteDownloadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                PikoApplication.instance.sessionManager.setRemoteDownloadEnabled(false)
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: RuntimeException) {
            PikoLog.w(TAG, "远程下载接收器进入前台服务被拒", e)
            stopSelf()
            return START_NOT_STICKY
        }
        PikoApplication.instance.services.remoteDownloads.refreshNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(tr("远程下载已开启", "Remote downloads are on", "リモートダウンロードが有効です", "원격 다운로드가 켜져 있습니다", "遠端下載已開啟"))
        .setContentText(tr("同一账号的其他 Piko 设备可以向本机发送下载任务", "Other Piko devices on this account can send downloads here", "同じアカウントの他の Piko 端末からこの端末へダウンロードを送信できます", "같은 계정의 다른 Piko 기기에서 이 기기로 다운로드를 보낼 수 있습니다", "同一帳號的其他 Piko 裝置可以向本機傳送下載工作"))
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
                Intent(this, RemoteDownloadService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("远程下载", "Remote downloads", "リモートダウンロード", "원격 다운로드", "遠端下載"),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "RemoteDownload"
        private const val CHANNEL_ID = "piko_remote_download"
        private const val NOTIFICATION_ID = 0x5049
        private const val OPEN_REQUEST_CODE = 0x504A
        private const val STOP_REQUEST_CODE = 0x504B
        private const val ACTION_STOP = "dev.piko.action.STOP_REMOTE_DOWNLOAD"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteDownloadService::class.java),
                )
            }.onFailure { PikoLog.w(TAG, "启动远程下载接收器失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteDownloadService::class.java)) }
        }
    }
}
