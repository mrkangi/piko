package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一个媒体会话：一个字节来源，按请求位置自适应复用一组短时保活的 reader 游标。
 *
 * 播放器拖动时先开新连接、后断旧连接，两个请求会短暂重叠；交错得不好的 MP4 甚至一直在音视频两处偏移来回发短请求。
 * 若每个 HTTP 请求结束都立即关闭游标，在途预读（READ_AHEAD_PRIORITY）会在几毫秒内被连环取消并销毁底层连接，
 * 新请求重建游标又从单槽位（256 KiB）重算跨度。这里维护小容量多轨游标池：邻近偏移直接接续原游标（零 seek、零取消），
 * 远距离双轨各自保有独立游标，闲置超时或会话关闭时统一回收。
 */
internal class ProxySession(
    private val source: ProxyByteSource,
    private val scope: CoroutineScope? = null,
) : AutoCloseable {
    val size: Long get() = source.size

    @Volatile
    var isClosed = false
        private set

    private class PooledReader(
        val reader: ProxyReader,
        var inUse: Boolean = true,
        var lastUsedSeq: Long = 0L,
        var idleJob: Job? = null,
        var activeStreamJob: Job? = null,
        var releasedSignal: CompletableDeferred<Unit>? = null,
    )

    private val poolLock = Any()
    private val pool = ArrayList<PooledReader>(MAX_POOLED_READERS)
    private var useSeq = 0L

    @Volatile
    private var tailRequested = false

    private fun snapshotReaders(): List<ProxyReader> = synchronized(poolLock) {
        pool.map { it.reader }
    }

    /**
     * 前台或后台，见 [ProxyReader.role]。切换时同步给池中所有游标（含短时保活游标），缓存不丢；之后开的照它开。
     * 预热的会话以后台建起，翻到它时升为前台。
     */
    @Volatile
    var role: StreamRole = StreamRole.FOREGROUND
        set(value) {
            field = value
            snapshotReaders().forEach { it.role = value }
        }

    /** 预读深度，见 [ProxyReader.readAheadLimit]。null 为默认；同步给池中所有游标，降档时闲置游标一并收起预读。 */
    @Volatile
    var readAheadLimit: Long? = null
        set(value) {
            field = value
            snapshotReaders().forEach { it.readAheadLimit = value }
        }

    /**
     * 有人正等着这个会话，见 [ProxyReader.urgent]。同步给池中游标，之后开的照它开：
     * 拖动时播放器正是新开一个请求去读新位置。
     */
    @Volatile
    var urgent: Boolean = false
        set(value) {
            field = value
            snapshotReaders().forEach { it.urgent = value }
        }

    @Volatile
    private var latestBufferedDuration: Duration? = null

    @Volatile
    private var latestMediaBytesPerSecond: Long? = null

    /** 向底层缓存及活动游标同步播放器当前缓冲时长与估算字节速率，供播放头停滞时按期限发起救援。 */
    fun reportPlaybackBuffer(bufferedDuration: Duration, mediaBytesPerSecond: Long? = null) {
        val safeBuffered = if (bufferedDuration.isNegative()) Duration.ZERO else bufferedDuration
        latestBufferedDuration = safeBuffered
        if (mediaBytesPerSecond != null && mediaBytesPerSecond > 0L) {
            latestMediaBytesPerSecond = mediaBytesPerSecond
            source.updateMediaBytesPerSecond(mediaBytesPerSecond)
        }
        val rate = latestMediaBytesPerSecond
        snapshotReaders().forEach { it.reportPlaybackBuffer(safeBuffered, rate) }
    }

    /**
     * 把 [ranges] 取进缓存，等全部到手才返回；[role] 缺省为会话的角色。没有缓存可填的来源立即返回。
     * 等的一方被取消（超时、翻走）时一并撤回这次预取：SDK 的预取不随等待者取消，留着它会以更早的
     * 需求排在眼前要放的段前面。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole = this.role, priority: Int? = null) {
        val warm = source.prefetch(ranges, role, priority) ?: return
        try {
            warm.await()
        } finally {
            warm.cancel()
        }
    }

    /** 把 [start, endExclusive) 的字节依次交给 [sink]。 */
    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        if (start == 0L) requestTail()
        val myJob = currentCoroutineContext().job
        val entry = acquireReader(start, myJob)
        val reader = entry.reader
        var broken = false
        try {
            if (isClosed) throw CancellationException("会话已关闭")
            val currentRole = role
            if (reader.role != currentRole) reader.role = currentRole
            val currentLimit = readAheadLimit
            if (currentLimit != null && reader.readAheadLimit != currentLimit) {
                reader.readAheadLimit = currentLimit
            }
            val currentUrgent = urgent
            if (reader.urgent != currentUrgent) reader.urgent = currentUrgent
            latestBufferedDuration?.let { buffered ->
                reader.reportPlaybackBuffer(buffered, latestMediaBytesPerSecond)
            }
            if (reader.position != start) {
                reader.seekTo(start)
            }
            pump(reader, start, endExclusive, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 播放器换片或读够时断开连接，写给它就会失败，游标本身完好，不算底层读取损坏
            if (e !is ClientGone) {
                broken = true
                if (!isClosed) PikoLog.w("Proxy", "读取 $start-$endExclusive/$size 失败", e)
            }
            throw e
        } finally {
            releaseReader(entry, broken)
        }
    }

    private suspend fun acquireReader(start: Long, callerJob: Job): PooledReader {
        for (attempt in 0 until 3) {
            var reused: PooledReader? = null
            var preemptSignal: CompletableDeferred<Unit>? = null
            var preemptJob: Job? = null
            synchronized(poolLock) {
                if (isClosed) throw CancellationException("会话已关闭")
                val idle = pool.filter { !it.inUse }
                // 1. 优先命中已将 [start] 保留在内存窗口内（含回退窗口与后台超前预读窗口）的闲置游标
                val inWindow = idle
                    .filter { start in it.reader.retainedStartPosition..it.reader.retainedEndPosition }
                    .minByOrNull { abs(it.reader.position - start) }
                // 2. 其次接续前方不远处的闲置游标，或允许小幅回退的闲置游标
                val nearForward = if (inWindow == null) {
                    idle
                        .filter { start > it.reader.retainedEndPosition && start - it.reader.retainedEndPosition <= FORWARD_REUSE_GAP_BYTES }
                        .minByOrNull { start - it.reader.retainedEndPosition }
                } else null
                val nearBackward = if (inWindow == null && nearForward == null) {
                    idle
                        .filter { it.reader.retainedStartPosition > start && it.reader.retainedStartPosition - start <= BACKWARD_REUSE_GAP_BYTES }
                        .minByOrNull { it.reader.retainedStartPosition - start }
                } else null

                val matchedIdle = inWindow ?: nearForward ?: nearBackward
                if (matchedIdle != null) {
                    matchedIdle.inUse = true
                    matchedIdle.activeStreamJob = callerJob
                    matchedIdle.lastUsedSeq = ++useSeq
                    matchedIdle.idleJob?.cancel()
                    matchedIdle.idleJob = null
                    reused = matchedIdle
                } else {
                    // 3. 若播放器刚切走并重连同一条轨，新请求可能比旧回环 socket 的 FIN 早到数毫秒；
                    //    此时该轨游标虽仍标为 inUse，但 start 恰落在它已覆盖的同轨窗口 `[retainedStartPosition, retainedEndPosition]` 内，
                    //    立即取消旧响应协程并接管该游标，避免同轨并发新开多条重复 HTTP 连接互抢带宽。
                    val inUseSameTrack = pool.firstOrNull {
                        it.inUse &&
                            it.reader.retainedStartPosition < it.reader.position &&
                            start in it.reader.retainedStartPosition..it.reader.retainedEndPosition
                    }
                    if (inUseSameTrack != null) {
                        preemptJob = inUseSameTrack.activeStreamJob
                        preemptSignal = inUseSameTrack.releasedSignal
                            ?: CompletableDeferred<Unit>().also { inUseSameTrack.releasedSignal = it }
                    } else if (pool.size >= MAX_POOLED_READERS) {
                        // 4. 池已满时淘汰复用最久未用的闲置游标；池未满时为远距离新轨道另开游标
                        val victim = idle.minByOrNull { it.lastUsedSeq }
                        if (victim != null) {
                            victim.inUse = true
                            victim.activeStreamJob = callerJob
                            victim.lastUsedSeq = ++useSeq
                            victim.idleJob?.cancel()
                            victim.idleJob = null
                            reused = victim
                        }
                    }
                }
            }
            reused?.let { return it }
            if (preemptSignal != null) {
                preemptJob?.cancel(CancellationException("同轨后续请求接管游标"))
                preemptSignal.await()
                continue
            }
            break
        }

        val fresh = source.openReader(role)
        var closeImmediately = false
        val created = synchronized(poolLock) {
            if (isClosed) {
                closeImmediately = true
                PooledReader(fresh, inUse = false)
            } else {
                PooledReader(fresh, inUse = true, lastUsedSeq = ++useSeq, activeStreamJob = callerJob).also { pool.add(it) }
            }
        }
        if (closeImmediately) {
            runCatching { fresh.close() }
            throw CancellationException("会话已关闭")
        }
        return created
    }

    private fun releaseReader(entry: PooledReader, broken: Boolean) {
        var toClose: List<ProxyReader> = emptyList()
        var waiterToWake: CompletableDeferred<Unit>? = null
        synchronized(poolLock) {
            entry.idleJob?.cancel()
            entry.idleJob = null
            entry.activeStreamJob = null
            waiterToWake = entry.releasedSignal
            entry.releasedSignal = null
            if (isClosed || broken) {
                pool.remove(entry)
                toClose = listOf(entry.reader)
            } else {
                entry.inUse = false
                entry.lastUsedSeq = ++useSeq
                // 若有同轨后续请求正等着接管该游标，先保留不淘汰；否则超出池容量上限时收敛回 MAX_POOLED_READERS
                if (waiterToWake == null && pool.size > MAX_POOLED_READERS) {
                    val victims = pool.filter { !it.inUse }
                        .sortedBy { it.lastUsedSeq }
                        .take(pool.size - MAX_POOLED_READERS)
                    victims.forEach { victim ->
                        victim.idleJob?.cancel()
                        victim.idleJob = null
                        pool.remove(victim)
                    }
                    toClose = victims.map { it.reader }
                }
                if (entry in pool && scope != null) {
                    entry.idleJob = scope.launch {
                        delay(IDLE_READER_KEEP_ALIVE)
                        val expired = synchronized(poolLock) {
                            if (!entry.inUse && pool.remove(entry)) {
                                entry.idleJob = null
                                entry.reader
                            } else null
                        }
                        expired?.let { runCatching { it.close() } }
                    }
                }
            }
        }
        waiterToWake?.complete(Unit)
        toClose.forEach { runCatching { it.close() } }
    }

    /**
     * 播放器读文件头时顺手取文件尾。MKV 的 Cues、moov 放在末尾的 MP4，索引都在文件尾，
     * 解复用器读完头要跳过去再跳回来，每一跳都是一次往返；与读头并行取，跳过去时已在缓存里。
     * 索引在文件头的 MP4 白取这一截，512 KiB 不值得为它先探一次格式。
     */
    private suspend fun requestTail() {
        if (tailRequested || size <= TAIL_BYTES * 2) return
        tailRequested = true
        val priority = if (role == StreamRole.FOREGROUND) PikPakStreamReader.INDEX_PRIORITY else null
        source.prefetch(listOf(size - TAIL_BYTES until size), role, priority)
    }

    private suspend fun pump(reader: ProxyReader, start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var position = start
        var attempt = 0
        var lastFailure: Exception? = null
        while (position < endExclusive) {
            currentCoroutineContext().ensureActive()
            if (isClosed) throw CancellationException("会话已关闭")
            val want = minOf(buffer.size.toLong(), endExclusive - position).toInt()
            val read = try {
                reader.read(buffer, 0, want)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                if (attempt >= READ_RETRY_DELAYS_MILLIS.size) throw e
                val delayMillis = READ_RETRY_DELAYS_MILLIS[attempt++]
                PikoLog.d(TAG, "读取 $position 暂时失败，${delayMillis}ms 后第 $attempt 次重试：${e::class.simpleName}")
                runCatching { reader.seekTo(position) }
                delay(delayMillis)
                continue
            }
            if (read == 0) {
                // 少数来源在暂时无数据时返回 0；只退避等待，不做无意义的同位置 seek。
                if (attempt >= READ_RETRY_DELAYS_MILLIS.size) {
                    throw IllegalStateException("字节来源在 $position 处持续无数据，应到 $endExclusive")
                }
                val delayMillis = READ_RETRY_DELAYS_MILLIS[attempt++]
                delay(delayMillis)
                continue
            }
            if (read < 0) {
                // 底层只在文件尾返回 -1；这里还没到 endExclusive，说明是上游提前断开，不能把半截响应当正常结束。
                val reason = lastFailure ?: IllegalStateException("字节来源在 $position 处提前结束，应到 $endExclusive")
                if (attempt >= READ_RETRY_DELAYS_MILLIS.size) throw reason
                val delayMillis = READ_RETRY_DELAYS_MILLIS[attempt++]
                PikoLog.d(TAG, "字节来源在 $position 处提前结束，${delayMillis}ms 后第 $attempt 次重试")
                runCatching { reader.seekTo(position) }
                delay(delayMillis)
                continue
            }
            attempt = 0
            lastFailure = null
            try {
                sink(buffer, 0, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ClientGone(e)
            }
            position += read
        }
    }

    override fun close() {
        val toClose: List<ProxyReader>
        synchronized(poolLock) {
            if (isClosed) return
            isClosed = true
            pool.forEach { it.idleJob?.cancel(); it.idleJob = null }
            toClose = pool.map { it.reader }
            pool.clear()
        }
        toClose.forEach { runCatching { it.close() } }
        runCatching { source.close() }
    }

    private class ClientGone(cause: Exception) : Exception("播放器断开", cause)

    private companion object {
        const val TAG = "Proxy"

        // 每次往本机回环 socket 推送 64 KiB，降低非交错 MP4 高频切轨时单次超前塞入内核回环缓冲区的冗余拷贝
        const val CHUNK_BYTES = 64 * 1024

        const val TAIL_BYTES = 512L * 1024

        // 上游短暂断流（服务端瞬断、反向代理超时、连接被回收）时先在原位置重连几次，
        // 避免把已经声明 Content-Length 的响应提前收尾，导致播放器整段重连甚至换源。
        val READ_RETRY_DELAYS_MILLIS = longArrayOf(200L, 500L, 1_000L, 2_000L)

        // 覆盖交错 MP4 的视频轨、音轨、字幕轨及尾部探测，互不挤占预读窗口
        const val MAX_POOLED_READERS = 4
        const val FORWARD_REUSE_GAP_BYTES = 4L * 1024 * 1024
        const val BACKWARD_REUSE_GAP_BYTES = 512L * 1024
        val IDLE_READER_KEEP_ALIVE = 10_000.milliseconds
    }
}
