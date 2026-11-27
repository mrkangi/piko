package dev.piko.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 拖动预览的取帧来源。网盘始终读原画：系统解码器对转码 MPEG-TS 的视频轨支持不稳。 */
data class SeekPreviewSource(
    val fileId: String,
    val localPath: String?,
    val isLocal: Boolean,
)

/** 平台提供的抽帧会话。一个会话只对应一个文件与一档画质，关闭时释放解码器与读源。 */
interface SeekPreviewExtractor : AutoCloseable {
    suspend fun frameAt(positionMillis: Long, targetWidthPx: Int = SeekPreviewDefaults.TargetWidthPx): SeekPreviewFrame?
}

/** Android 实现本地直读与网盘随机读；桌面端为 null，界面自动退回时间气泡。 */
fun interface SeekPreviewSupport {
    suspend fun open(source: SeekPreviewSource): SeekPreviewExtractor?
}

internal object SeekPreviewDefaults {
    const val BucketMillis = 5_000L
    const val DebounceMillis = 120L
    const val FrameTimeoutMillis = 8_000L
    const val IdleCloseMillis = 30_000L
    const val CacheEntries = 28
    const val TargetWidthPx = 384
}

/**
 * 拖动进度的取帧调度。
 *
 * 指针每帧都在动，但抽帧只在同一档位停稳 [SeekPreviewDefaults.DebounceMillis] 后才发起；
 * 一次只跑一个取帧任务，拖动期间的新目标只覆盖待处理项。已经开始的旧任务仍会把结果放进缓存，
 * 但不会闪回当前画面。缓存按 5 秒一档，只留最近 [SeekPreviewDefaults.CacheEntries] 张。
 */
class SeekPreviewController(
    private val scope: CoroutineScope,
    private val support: SeekPreviewSupport,
) {
    var frame by mutableStateOf<SeekPreviewFrame?>(null)
        private set

    private data class Request(val bucketMillis: Long, val serial: Long)

    private var source: SeekPreviewSource? = null
    private var extractor: SeekPreviewExtractor? = null
    private var worker: Job? = null
    private var idleJob: Job? = null
    private var pending: Request? = null
    private var latestPositionMillis: Long? = null
    private var serial = 0L
    private var released = false
    private val cache = SeekPreviewLruCache<Long, SeekPreviewFrame>(SeekPreviewDefaults.CacheEntries)
    // 离开组合时 rememberCoroutineScope 随后就会取消；释放 native 解码器不能挂在那上面。
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 文件、画质或本地/云端来源切换。旧缓存与解码器立即作废，避免一瞬间闪出上一集。 */
    fun bind(next: SeekPreviewSource?) {
        if (released) return
        if (next == source) return
        source = next
        frame = null
        cache.clear()
        pending = null
        serial += 1
        closeExtractor()
        latestPositionMillis?.let(::request)
    }

    /**
     * [positionMillis] 为 null 表示拖动结束。结束不取消已经在跑的抽帧：
     * 让它把结果写进缓存，下一次拖回同一段时直接命中。画面本身由进度条是否处于拖动态决定显隐。
     */
    fun request(positionMillis: Long?) {
        if (released) return
        latestPositionMillis = positionMillis
        if (positionMillis == null) {
            pending = null
            serial += 1
            return
        }
        if (source == null) return
        val bucket = seekPreviewBucketOf(positionMillis)
        serial += 1
        val requested = Request(bucket, serial)
        cache[bucket]?.let { cached ->
            pending = null
            frame = cached
            return
        }
        pending = requested
        ensureWorker()
    }

    /** 离开播放器时调用。缓存也会随控制器一起丢掉。 */
    fun release() {
        if (released) return
        released = true
        pending = null
        serial += 1
        worker?.cancel()
        worker = null
        idleJob?.cancel()
        idleJob = null
        cache.clear()
        frame = null
        closeExtractor()
    }

    private fun ensureWorker() {
        if (released) return
        idleJob?.cancel()
        idleJob = null
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                while (true) {
                    delay(SeekPreviewDefaults.DebounceMillis)
                    val request = pending ?: break
                    pending = null
                    val activeSource = source ?: continue
                    val cached = cache[request.bucketMillis]
                    if (cached != null) {
                        if (request.serial == serial) frame = cached
                        continue
                    }
                    val result = load(activeSource, request.bucketMillis) ?: continue
                    cache[request.bucketMillis] = result
                    if (request.serial == serial) frame = result
                }
            } finally {
                worker = null
                scheduleIdleClose()
            }
        }
    }

    private suspend fun load(activeSource: SeekPreviewSource, bucketMillis: Long): SeekPreviewFrame? {
        val session = extractor ?: support.open(activeSource)?.let { opened ->
            if (source != activeSource) {
                opened.close()
                return null
            }
            opened.also { extractor = it }
        } ?: return null
        return try {
            withTimeout(SeekPreviewDefaults.FrameTimeoutMillis) {
                session.frameAt(bucketMillis, SeekPreviewDefaults.TargetWidthPx)
            }
        } catch (_: TimeoutCancellationException) {
            // 读源可能卡在 native 读取上；关掉会话，下一次请求重新开。
            closeExtractor()
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // 抽帧失败或读源已被换片关闭：静默降级，拖动与播放本身不受影响。
            null
        }
    }

    private fun scheduleIdleClose() {
        if (released) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(SeekPreviewDefaults.IdleCloseMillis)
            if (worker?.isActive != true) closeExtractor()
        }
    }

    private fun closeExtractor() {
        val old = extractor ?: return
        extractor = null
        closeScope.launch { runCatching { old.close() } }
    }
}

/** 拖动目标取固定 5 秒档；时间读数仍用精确位置，只有缩略图按档位复用。 */
internal fun seekPreviewBucketOf(positionMillis: Long): Long {
    val safe = positionMillis.coerceAtLeast(0L)
    return safe / SeekPreviewDefaults.BucketMillis * SeekPreviewDefaults.BucketMillis
}

/** 小型 LRU，测试单独覆盖淘汰顺序；控制器只在主线程访问。 */
internal class SeekPreviewLruCache<K, V>(private val maxEntries: Int) {
    private val entries = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        entries[key] = value
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
