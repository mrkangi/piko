package dev.piko.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.RandomAccessMediaSource
import dev.piko.ui.screens.player.SeekPreviewExtractor
import dev.piko.ui.screens.player.SeekPreviewFrame
import dev.piko.ui.screens.player.SeekPreviewSource
import dev.piko.ui.screens.player.SeekPreviewSupport
import io.github.nihildigit.pikpak.StreamRole
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android 的拖动预览抽帧。
 *
 * 本地文件由 [MediaMetadataRetriever] 直接读路径或 SAF 描述符；网盘视频经
 * [PikoMediaRepository.openRandomAccess] 取得随机读源，再包成 [MediaDataSource]。
 * 因此网盘预览复用播放器同一份分块缓存，且以 [StreamRole.BACKGROUND] 低优先级读取，
 * 不与主播放器抢前台连接。
 */
internal class AndroidSeekPreviewSupport(
    private val context: Context,
    private val repository: PikoMediaRepository,
) : SeekPreviewSupport {
    override suspend fun open(source: SeekPreviewSource): SeekPreviewExtractor? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var dataSource: RandomAccessDataSource? = null
        try {
            when {
                source.localPath != null -> {
                    val path = source.localPath.orEmpty()
                    when {
                        path.startsWith("content:", ignoreCase = true) -> retriever.setDataSource(context, Uri.parse(path))
                        path.startsWith("file:", ignoreCase = true) -> retriever.setDataSource(Uri.parse(path).path ?: path)
                        else -> retriever.setDataSource(path)
                    }
                }

                source.preferRandomAccess && source.fileId.isNotBlank() -> {
                    val randomAccess = repository.openRandomAccess(
                        fileId = source.fileId,
                        role = StreamRole.BACKGROUND,
                        readAheadLimit = 0L,
                    ).getOrNull()
                    if (randomAccess != null) {
                        dataSource = RandomAccessDataSource(randomAccess).also { retriever.setDataSource(it) }
                    } else if (source.remoteUrl != null) {
                        // 分块缓存打开失败时仍可退到当前直链，不让预览整块消失。
                        retriever.setDataSource(source.remoteUrl.orEmpty(), REMOTE_HEADERS)
                    } else {
                        retriever.release()
                        return@withContext null
                    }
                }

                !source.remoteUrl.isNullOrBlank() -> {
                    retriever.setDataSource(source.remoteUrl.orEmpty(), REMOTE_HEADERS)
                }

                else -> {
                    retriever.release()
                    return@withContext null
                }
            }
            AndroidSeekPreviewExtractor(retriever, dataSource)
        } catch (cancel: CancellationException) {
            retriever.release()
            dataSource?.close()
            throw cancel
        } catch (e: Exception) {
            retriever.release()
            dataSource?.close()
            PikoLog.w(TAG, "打开拖动预览取帧源失败：${source.kindLabel()} fileId=${source.fileId}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SeekPreview"
        val REMOTE_HEADERS = mapOf("User-Agent" to "Piko/1.0")
    }
}

private fun SeekPreviewSource.kindLabel(): String = when {
    isLocal -> "本地"
    preferRandomAccess -> "PikPak"
    else -> "HTTP 挂载"
}

private class AndroidSeekPreviewExtractor(
    private val retriever: MediaMetadataRetriever,
    private val dataSource: RandomAccessDataSource?,
) : SeekPreviewExtractor {
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun frameAt(positionMillis: Long, targetWidthPx: Int): SeekPreviewFrame? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed.get()) return@withLock null
            try {
                extractFrame(positionMillis, targetWidthPx)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                PikoLog.w(TAG, "拖动预览抽帧失败", e)
                null
            }
        }
    }

    private fun extractFrame(positionMillis: Long, targetWidthPx: Int): SeekPreviewFrame? {
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val safeWidth = rawWidth.takeIf { it > 0 } ?: 16
        val safeHeight = rawHeight.takeIf { it > 0 } ?: 9
        val scale = (targetWidthPx.toFloat() / max(safeWidth, safeHeight)).coerceAtMost(1f)
        val requestWidth = (safeWidth * scale).roundToInt().coerceAtLeast(1)
        val requestHeight = (safeHeight * scale).roundToInt().coerceAtLeast(1)
        val timeUs = (positionMillis.coerceAtLeast(0L) * 1_000L)
        val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                requestWidth,
                requestHeight,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { frame -> scaleFrame(frame, requestWidth, requestHeight) }
        } ?: return null
        val frame = rotateFrame(rawFrame, rotation)
        return SeekPreviewFrame(
            image = frame.asImageBitmap(),
            aspectRatio = frame.width.toFloat() / frame.height.coerceAtLeast(1).toFloat(),
        )
    }

    private fun scaleFrame(frame: Bitmap, width: Int, height: Int): Bitmap =
        if (frame.width == width && frame.height == height) frame
        else Bitmap.createScaledBitmap(frame, width, height, true)

    private fun rotateFrame(frame: Bitmap, rotation: Int): Bitmap {
        val normalized = ((rotation % 360) + 360) % 360
        if (normalized == 0) return frame
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { retriever.release() }
        dataSource?.close()
    }

    private companion object {
        const val TAG = "SeekPreview"
    }
}

/**
 * MediaMetadataRetriever 只认同步 [MediaDataSource]。readAt 在它自己的读取线程上调用，
 * 这里把挂起的随机读桥接成阻塞调用。
 */
private class RandomAccessDataSource(
    private val source: RandomAccessMediaSource,
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0 || closed.get()) return if (closed.get()) -1 else 0
        return runBlocking { source.readAt(position, buffer, offset, size) }
    }

    override fun getSize(): Long = source.size

    override fun close() {
        if (closed.compareAndSet(false, true)) source.close()
    }
}
