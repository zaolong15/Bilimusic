package app.bilimusic.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 下载到应用私有目录（不需要存储权限，卸载应用时一并删除）。同时最多 2 个任务。
 * 下载期间由 DownloadService 提供前台通知，所以退到后台也不会被系统中断。
 */
class Downloader(
    private val ctx: Context,
    private val api: BiliApi,
    private val dao: LibraryDao,
    private val prefs: Prefs
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()

    val dir: File
        get() = (ctx.getExternalFilesDir("music") ?: File(ctx.filesDir, "music")).also { it.mkdirs() }

    fun fileOf(bvid: String) = File(dir, "$bvid.m4a")

    /** bvid -> 进度（0..1）；-1 表示排队中。 */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active

    val failures = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 设置里勾了「仅 Wi-Fi」且当前是计费网络（移动数据）时，不下载。 */
    private fun blockedByNetwork(): Boolean {
        if (!prefs.wifiOnly.value) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    fun enqueue(songs: List<Song>) {
        if (blockedByNetwork()) {
            failures.tryEmit("当前不是 Wi-Fi，已暂停下载。可以在「设置」里允许使用移动数据。")
            return
        }
        val todo = songs.filter { !fileOf(it.bvid).exists() && it.bvid !in _active.value }
        if (todo.isEmpty()) return
        todo.forEach { s ->
            _active.update { it + (s.bvid to -1f) }
            jobs[s.bvid] = scope.launch {
                try {
                    gate.withPermit { run(s) }
                } finally {
                    _active.update { it - s.bvid }
                    jobs.remove(s.bvid)
                }
            }
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    private suspend fun run(s: Song) {
        val target = fileOf(s.bvid)
        val job = currentCoroutineContext()[Job]
        try {
            dao.ensureSong(s)
            var last = -1
            api.download(s.bvid, target, { job?.isActive == false }) { p ->
                val pct = (p * 100).toInt()
                if (pct != last) { last = pct; _active.update { it + (s.bvid to p) } }
            }
            dao.setLocalPath(s.bvid, target.absolutePath)
        } catch (e: CancellationException) {
            File(target.path + ".part").delete()   // 用户主动取消：不保留半截文件
            throw e
        } catch (e: Exception) {
            // 出错时保留 .part，下次重试会接着下
            failures.tryEmit("《${s.title}》下载失败。${e.friendly()}")
        }
    }

    fun cancel(bvid: String) { jobs[bvid]?.cancel() }
    fun cancelAll() { jobs.values.forEach { it.cancel() } }

    suspend fun remove(bvid: String) {
        fileOf(bvid).delete()
        dao.setLocalPath(bvid, null)
    }

    suspend fun clearAll() {
        cancelAll()
        dir.listFiles()?.forEach { it.delete() }
        dao.clearAllLocalPaths()
    }

    /** 启动时对账：库里记着已下载但文件没了（被清理/换了存储）就改回未下载；文件在但库里没记就补上；清掉一周前的残留 .part。 */
    suspend fun reconcile() {
        dao.downloadedOnce().filter { !fileOf(it.bvid).exists() }.forEach { dao.setLocalPath(it.bvid, null) }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".m4a") -> dao.setLocalPath(f.name.removeSuffix(".m4a"), f.absolutePath)
                f.name.endsWith(".part") && f.lastModified() < weekAgo -> f.delete()
            }
        }
    }

    fun usedBytes(): Long = dir.listFiles()?.filter { it.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0L
}
