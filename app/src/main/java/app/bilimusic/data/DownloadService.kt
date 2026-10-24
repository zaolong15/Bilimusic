package app.bilimusic.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.bilimusic.App
import app.bilimusic.R
import app.bilimusic.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 下载期间的前台服务：显示进度通知，并让进程在后台也不被系统回收。队列清空后自动停止。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloader = (application as App).downloader
        if (intent?.action == ACTION_CANCEL) downloader.cancelAll()
        startForeground(NOTIF_ID, build("准备下载…", 0, true))
        if (watcher == null) {
            watcher = scope.launch {
                downloader.active.collect { m ->
                    if (m.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val running = m.values.filter { it >= 0f }
                        val avg = if (running.isEmpty()) 0f else running.average().toFloat()
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIF_ID, build("正在下载 ${m.size} 首", (avg * 100).toInt(), running.isEmpty()))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("BiliMusic")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "取消全部", cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIF_ID = 2
        const val ACTION_CANCEL = "app.bilimusic.CANCEL_DOWNLOADS"
    }
}
