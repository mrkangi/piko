package dev.piko.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.piko.PikoApplication
import dev.piko.ui.screens.player.SeekPreviewSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机系统解码器对本地文件抽帧。网盘来源另经随机读桥接，走同一套 MediaMetadataRetriever。 */
@RunWith(AndroidJUnit4::class)
class AndroidSeekPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun localFileFrameAtTwoSeconds() = runBlocking {
        val file = File(context.cacheDir, "seek-preview/control-mpeg4-aac.mp4").also { target ->
            target.parentFile?.mkdirs()
            instrumentation.context.assets.open("control-mpeg4-aac.mp4").use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
        val support = AndroidSeekPreviewSupport(context, PikoApplication.instance.mediampMediaRepository)
        val extractor = support.open(
            SeekPreviewSource(fileId = file.path, localPath = file.path, isLocal = true),
        ) ?: error("无法打开本地抽帧器")
        try {
            val frame = extractor.frameAt(2_000L, targetWidthPx = 384)
                ?: error("本地样片抽帧为空")
            assertTrue(frame.image.width > 0 && frame.image.height > 0)
            assertTrue(frame.aspectRatio > 1f)
        } finally {
            extractor.close()
        }
    }
}
